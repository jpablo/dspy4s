package dspy4s.signatures

import dspy4s.core.contracts.{DspyError, FieldSpec, LenientValues, TypeMarker, TypeRef, ValidationError}
import dspy4s.core.contracts.Reflects.{caseNames, withoutDeferred}
import zio.blocks.chunk.{Chunk, ChunkMap, NonEmptyChunk}
import zio.blocks.docs.Doc
import zio.blocks.schema.binding.{Binding, BindingType}
import zio.blocks.schema.json.{JsonCodec, JsonFormat, JsonReader, JsonSchema, JsonWriter}
import zio.blocks.schema.{
  DynamicOptic,
  DynamicValue,
  Lazy,
  Modifier,
  PrimitiveType,
  PrimitiveValue,
  Reflect,
  ReflectTransformer,
  Schema,
  Term
}
import zio.blocks.typeid.{Owner, TypeId}

/** The bridge between a zio-blocks `Schema[A]` and a dspy4s `Shape[A]`.
  *
  *   - [[derivedFromZioSchema]] makes the `Shape[A]` of a `Schema[A]`. The encode uses `Schema.toDynamicValue`. The
  *     decode first coerces the record (for example, the LM text `"true"` or `"42"` becomes a primitive of the target
  *     type), and then uses `Schema.fromDynamicValue`.
  *   - [[fieldSpecsFromReflect]] gives the `FieldSpec` list of a `Reflect.Record`.
  *   - [[normalize]] coerces a `DynamicValue` to a target `Reflect`.
  *   - [[jsonSchemaForModel]] gives the JSON schema of a `Schema[A]` for a prompt, with no `id` in a `ToolCall`, and
  *     with `$defs` for a recursive type.
  *
  * The codec finds the framework types (`History`, `ToolCall`, and the decision types) by their marker ([[TypeMarker]])
  * in the modifiers of a reflect, not by their structure or their type id.
  *
  * The interchange format is `DynamicValue.Record` from end to end. The codec does not use a `Map[String, Any]`.
  */
private[signatures] object ZioSchemaCodec:

  /** Coerce a `DynamicValue` to the form that the target `Reflect` expects. These are the two important coercions:
    *
    *   - A primitive: the lenient rules of [[LenientValues]] apply. For example, the LM text `"true"` becomes
    *     `Primitive(Boolean(true))` when the target field is `Boolean`, and the text `"$1,200"` becomes an `Int`.
    *   - A variant field: a bare `Primitive(String("joy"))` becomes `Variant("joy", Record.empty)`, so that
    *     `Schema.fromDynamicValue` can select the correct case.
    *
    * The coercion goes into each field of a record that the target has. A field that the target does not have stays as
    * it is. The coercion also goes into each element of a sequence and each entry of a map. Each other value, and a
    * value that does not agree with its target, stays as it is.
    *
    * At each level, a record gets the default value of each field of the target that it does not have, and `None` for
    * each `Option` field with no default. A required field that is absent stays absent, thus the decode fails.
    */
  def normalize(dv: DynamicValue, target: Reflect.Bound[?]): DynamicValue =
    // zio-blocks defers the reflect of some nested fields. The coercion needs the reflect behind it.
    (dv, withoutDeferred(target)) match
      case (prim @ DynamicValue.Primitive(_), p: Reflect.Primitive[Binding, ?]) =>
        LenientValues.coerce(prim, p.primitiveType)
      // An `Option` field: put a bare value, a null, or a text into `Some` or `None` before the general rules below.
      case (value, OptionOf(element))                                                   => normalizeOption(value, element)
      case (_: DynamicValue.Null.type, _)                                               => DynamicValue.Null
      case (DynamicValue.Primitive(PrimitiveValue.String(s)), _: Reflect.Variant[?, ?]) =>
        DynamicValue.Variant(s, DynamicValue.Record.empty)
      case (rec: DynamicValue.Record, recTarget: Reflect.Record[Binding, ?])        => mapRecordFields(rec, recTarget)
      case (seq: DynamicValue.Sequence, seqTarget: Reflect.Sequence[Binding, ?, ?]) =>
        DynamicValue.Sequence(seq.elements.map(normalize(_, seqTarget.element)))
      case (m: DynamicValue.Map, mapTarget: Reflect.Map[Binding, ?, ?, ?]) =>
        DynamicValue.Map(m.entries.map((k, v) => normalize(k, mapTarget.key) -> normalize(v, mapTarget.value)))
      case (rec: DynamicValue.Record, mapTarget: Reflect.Map[Binding, ?, ?, ?]) =>
        // dspy4s keeps a map with text keys as a `DynamicValue.Record` (see `DynamicValues.fromAny`), but the zio-blocks
        // `Schema[Map[K, V]]` reads a `DynamicValue.Map`. Each field becomes an entry.
        DynamicValue.Map(rec.fields.map { (k, v) =>
          normalize(DynamicValue.Primitive(PrimitiveValue.String(k)), mapTarget.key) -> normalize(v, mapTarget.value)
        })
      case _ => dv

  /** Coerce a value to the `Some` or `None` variant that zio-blocks expects for an `Option` field. `valueReflect` is
    * the reflect of the `value` field of `Some`. Thus a value in `Some` goes through [[normalize]] again and gets the
    * usual coercion (for example, the text `"2399.0"` becomes a `Double`). A null, and a null text of
    * [[LenientValues.NullTexts]], become `None`.
    */
  private def normalizeOption(value: DynamicValue, valueReflect: Reflect.Bound[?]): DynamicValue =
    def some(v: DynamicValue): DynamicValue =
      DynamicValue.Variant("Some", DynamicValue.Record(Chunk.single("value" -> normalize(v, valueReflect))))
    value match
      case _: DynamicValue.Null.type                                                       => none
      case variant: DynamicValue.Variant                                                   => variant
      case DynamicValue.Primitive(PrimitiveValue.String(s)) if LenientValues.isNullText(s) => none
      case other                                                                           => some(other)

  /** The `None` case of an `Option`, in the Variant shape that zio-blocks expects. */
  private def none: DynamicValue = DynamicValue.Variant("None", DynamicValue.Record.empty)

  private def mapRecordFields(rec: DynamicValue.Record, target: Reflect.Record[Binding, ?]): DynamicValue.Record =
    val fields = rec.fields.map { (name, value) =>
      name -> target.fieldByName(name).fold(value)(term => normalize(value, term.value))
    }
    // The wire does not require a field with a default value or an `Option` field. A record with no such key gets the
    // default value. For an `Option` field with no default, it gets `None`, the same as an explicit null or "none".
    val absent = target.fields.filterNot(term => rec.fields.exists(_._1 == term.name)).flatMap { term =>
      wireDefault(term.value)
        .map(default => term.name -> normalize(default, term.value))
        .orElse(Option.when(isOption(term.value))(term.name -> none))
    }
    if absent.isEmpty then DynamicValue.Record(fields)
    else DynamicValue.Record(fields ++ Chunk.from(absent))

  /** The default value of a field in the wire form, from the reflect of the field: zio-blocks keeps the default value
    * of a case-class parameter there. The wire form of an `Option` is its element, thus a default `Some(x)` is `x`, and
    * a default `None` is no default (an absent `Option` field is `None`).
    */
  private def wireDefault(reflect: Reflect.Bound[?]): Option[DynamicValue] =
    storedDefault(reflect).flatMap { default =>
      if !isOption(reflect) then Some(default)
      else
        default match
          case DynamicValue.Variant("Some", DynamicValue.Record(fields)) => fields.collectFirst {
              case ("value", value) => value
            }
          case _ => None
    }

  private def storedDefault[A](reflect: Reflect.Bound[A]): Option[DynamicValue] =
    reflect.getDefaultValue.map(reflect.toDynamicValue)

  /** The field specs of a `Reflect.Record`, in the order of its fields, with no input or output role. Empty for each
    * other reflect.
    */
  def fieldSpecsFromReflect(reflect: Reflect.Bound[?]): Vector[FieldSpec] =
    reflect match
      case rec: Reflect.Record[Binding, ?] => rec.fields.toVector.map(term => fieldSpecOf(term.name, term.value))
      case _                               => Vector.empty

  /** The field spec of the field `name` with the reflect `reflect`. An `Option` field is an optional field, with the
    * type and the enum values of its element. A field with a default value (a case-class parameter with a default) has
    * that value in the wire form as its `defaultValue` (see [[wireDefault]]).
    */
  def fieldSpecOf(name: String, reflect: Reflect.Bound[?]): FieldSpec =
    FieldSpec(
      name = name,
      typeRef = typeRefFor(reflect),
      defaultValue = wireDefault(reflect),
      enumValues = enumValuesOf(reflect),
      optional = isOption(reflect)
    )

  /** The case names that an enum field accepts: the cases of a `Reflect.Variant`. Empty for each reflect that is not a
    * variant. The wire `typeRef` of a variant stays `TypeRef.string`, because the LM sees a plain label. The adapters
    * use these values to tell the LM which labels are valid. An `Option` has the enum values of its element. A `Score`
    * field has its levels, and a `Choice` field has its option labels (see [[decisionField]]).
    */
  private def enumValuesOf(reflect: Reflect.Bound[?]): Vector[String] =
    reflect match
      case OptionOf(element) => enumValuesOf(element)
      case _                 => decisionField(reflect).fold(caseNames(reflect))(_._2)

  /** Matches the reflect of an `Option`: a variant with a `None` case (an empty record) and a `Some` case, whose record
    * has one field with the name `value`. The result is the reflect of that `value` field, so that [[normalize]] can
    * coerce the value in `Some` to the correct target.
    */
  private object OptionOf:
    def unapply(reflect: Reflect.Bound[?]): Option[Reflect.Bound[?]] =
      withoutDeferred(reflect) match
        case variant: Reflect.Variant[Binding, ?] if variant.cases.size == 2 =>
          val hasNone = variant.caseByName("None").exists(term => isEmptyRecord(term.value))
          val value   = variant.caseByName("Some").flatMap { term =>
            term.value match
              case rec: Reflect.Record[Binding, ?] if rec.fields.size == 1 => rec.fieldByName("value").map(_.value)
              case _                                                       => None
          }
          if hasNone then value else None
        case _ => None

    private def isEmptyRecord(reflect: Reflect[?, ?]): Boolean =
      reflect match
        case rec: Reflect.Record[?, ?] => rec.fields.isEmpty
        case _                         => false

  private def isOption(reflect: Reflect.Bound[?]): Boolean = OptionOf.unapply(reflect).isDefined

  /** The wire `TypeRef` of a value type, from its `Schema`. [[fieldSpecsFromReflect]] uses the same rules for each
    * field of a record. `SchemaInterop` gives it to the callers outside this module.
    */
  def typeRefForSchema[A](using schema: Schema[A]): TypeRef = typeRefFor(schema.reflect)

  /** The wire type of a reflect. An `Option` has the type of its element: an `Option[Int]` field is an optional int
    * field.
    */
  private def typeRefFor(reflect: Reflect.Bound[?]): TypeRef =
    reflect match
      case OptionOf(element) => typeRefFor(element)
      case _                 => decisionField(reflect).fold(typeRefOfValue(reflect))(_._1)

  /** The names of the fields of a `Reflect.Record` whose type holds a decision type inside an `Option`, a collection,
    * or a map (experimental, upstream `resolve_adapter` and `extract_custom_type_from_annotation`). A field of a
    * decision type is a decision output, thus it is not in the result. The walk does not go into the fields of a
    * record, as upstream does not go into the fields of a model.
    */
  def nestedDecisionFieldsOf(reflect: Reflect.Bound[?]): Vector[String] =
    withoutDeferred(reflect) match
      case rec: Reflect.Record[Binding, ?] => rec.fields.toVector.collect {
          case term if decisionField(term.value).isEmpty && holdsDecision(term.value) => term.name
        }
      case _ => Vector.empty

  /** `true` when `reflect` is a decision type, or an `Option`, a collection, or a map that holds one. */
  private def holdsDecision(reflect: Reflect.Bound[?]): Boolean =
    unwrapped(reflect) match
      case OptionOf(element)                       => holdsDecision(element)
      case found if decisionField(found).isDefined => true
      case seq: Reflect.Sequence[Binding, ?, ?]    => holdsDecision(seq.element)
      case map: Reflect.Map[Binding, ?, ?, ?]      => holdsDecision(map.key) || holdsDecision(map.value)
      case _                                       => false

  /** The reflect behind a deferred reflect, with a wildcard value type. A match on the result of `withoutDeferred`
    * itself has the captured value type of `reflect`, and with it the compiler finds a sequence case or a map case
    * unreachable.
    */
  private def unwrapped(reflect: Reflect.Bound[?]): Reflect.Bound[?] = withoutDeferred(reflect)

  /** The type and the enum values of a decision field (experimental, upstream `dspy.experimental`), from the marker of
    * its type ([[TypeMarker]]): a `Noul` has no enum values, a `Score` has its levels (the cases of the element of its
    * `level` field), and a `Choice` has its option labels (the cases of its `value` field). `None` for each other
    * reflect.
    */
  private def decisionField(reflect: Reflect.Bound[?]): Option[(TypeRef, Vector[String])] =
    TypeMarker.of(reflect) match
      case Some(TypeMarker.noul)   => Some(TypeRef.noul -> Vector.empty)
      case Some(TypeMarker.score)  => Some(TypeRef.score -> casesOfField(reflect, "level"))
      case Some(TypeMarker.choice) => Some(TypeRef.choice -> casesOfField(reflect, "value"))
      case _                       => None

  /** The enum values of the field `name` of the record `reflect` (see [[enumValuesOf]]): a field of type `Option[E]`
    * has the cases of `E`. Empty when the record has no such field.
    */
  private def casesOfField(reflect: Reflect.Bound[?], name: String): Vector[String] =
    withoutDeferred(reflect) match
      case rec: Reflect.Record[Binding, ?] =>
        rec.fieldByName(name).fold(Vector.empty[String])(t => enumValuesOf(t.value))
      case _ => Vector.empty

  /** The wire type of a reflect that is not a decision type. A `History` record (by its marker) is [[TypeRef.history]],
    * and a sequence whose element is a `ToolCall` (by its marker) is [[TypeRef.toolCalls]].
    */
  private def typeRefOfValue(reflect: Reflect[?, ?]): TypeRef =
    withoutDeferred(reflect) match
      case prim: Reflect.Primitive[?, ?]                                                => primitiveTypeRef(prim.primitiveType)
      case _: Reflect.Variant[?, ?]                                                     => TypeRef.string
      case rec: Reflect.Record[?, ?] if isMarked(rec, TypeMarker.history)               => TypeRef.history
      case seq: Reflect.Sequence[?, ?, ?] if isMarked(seq.element, TypeMarker.toolCall) => TypeRef.toolCalls
      case _: Reflect.Sequence[?, ?, ?]                                                 => TypeRef.list
      case _                                                                            => TypeRef.json

  /** `true` when the type of `reflect` has the marker `marker`. */
  private def isMarked(reflect: Reflect[?, ?], marker: String): Boolean = TypeMarker.of(reflect).contains(marker)

  /** Marks the `id` field of each `ToolCall` record (by its marker) as transient, so the JSON schema does not show that
    * field. Each other part of the reflect stays as it is.
    */
  private object HideToolCallId extends ReflectTransformer.OnlyMetadata[Binding, Binding]:
    override def transformMetadata[K, A](f: Binding[K, A]): Lazy[Binding[K, A]] = Lazy(f)

    override def transformRecord[A](
        path              : DynamicOptic,
        fields            : IndexedSeq[Term[Binding, A, ?]],
        typeId            : TypeId[A],
        metadata          : Binding[BindingType.Record, A],
        doc               : Doc,
        modifiers         : Seq[Modifier.Reflect],
        storedDefaultValue: Option[DynamicValue],
        storedExamples    : collection.immutable.Seq[DynamicValue]
    ): Lazy[Reflect.Record[Binding, A]] =
      val shown =
        if !TypeMarker.in(modifiers).contains(TypeMarker.toolCall) then fields
        else
          fields.map { term =>
            if term.name == "id" then term.copy(modifiers = term.modifiers :+ Modifier.transient()) else term
          }
      super.transformRecord(path, shown, typeId, metadata, doc, modifiers, storedDefaultValue, storedExamples)

  /** The JSON schema of `schema` as a model sees it. The schema does not ask for the `id` of a `ToolCall` (upstream
    * removes `id` from the JSON schema of `ToolCalls.ToolCall`): the id comes from the provider, and a model that
    * writes a tool call as text does not supply it.
    *
    * A recursive type has its schema in `$defs`, and each reference to it is a `$ref` (upstream pydantic does the
    * same). zio-blocks writes no `$ref`. It puts the schema of each field in the schema of its record, so for a
    * recursive type its `toJsonSchema` (a `lazy val`) reads itself and waits forever. Thus this function cuts each
    * cycle before zio-blocks makes the schema. When the root type is recursive, the root is a `$ref` to its definition.
    */
  def jsonSchemaForModel[A](schema: Schema[A]): String =
    val shown     = schema.reflect.transform(DynamicOptic.root, HideToolCallId).force
    val recursive = recursiveTypes(shown)
    if recursive.isEmpty then Schema(shown).toJsonSchema.toJson.toString
    else withDefinitions(shown, recursive).toJson.toString

  /** The JSON schema of `root`, with a definition in `$defs` for each type in `recursive`. In each schema, each
    * reference to a type in `recursive` is a placeholder whose JSON schema is a `$ref` ([[RefSchemaCodec]]).
    */
  private def withDefinitions(root: Reflect.Bound[?], recursive: Vector[Reflect.Bound[?]]): JsonSchema =
    val names = definitionNames(recursive)
    val cut   = CutCycles(names)

    def jsonSchemaOf[B](reflect: Reflect.Bound[B]): JsonSchema =
      val builder = Schema(reflect.transform(DynamicOptic.root, cut).force).deriving(JsonFormat.deriver)
      names.valuesIterator
        .foldLeft(builder)((acc, name) => acc.instance(refTypeId[Any](name), RefSchemaCodec(name)))
        .derive
        .toJsonSchema

    val definitions = ChunkMap.from(recursive.map(reflect => names(reflect.typeId) -> jsonSchemaOf(reflect)))
    names.get(root.typeId).fold(jsonSchemaOf(root))(name => JsonSchema.refString(refOf(name))) match
      case obj: JsonSchema.Object => obj.copy($defs = Some(definitions))
      case other                  => JsonSchema.Object(allOf = Some(NonEmptyChunk.single(other)), $defs = Some(definitions))

  /** The types that occur again in themselves, in the order that the walk from `root` finds them. A type here is a
    * record, a variant, or a wrapper. A sequence, a map, and an `Option` are not types here: the walk goes into them.
    * Thus for `next: Option[Node]` the recursive type is `Node`, not `Option[Node]`.
    *
    * The type id identifies a type, as in zio-blocks. zio-blocks gives one type id to each instance of a generic type
    * (for example `Tree[Int]` and `Tree[String]`), so these instances share one definition. zio-blocks cannot encode a
    * value of such a type either.
    */
  private def recursiveTypes(root: Reflect.Bound[?]): Vector[Reflect.Bound[?]] =
    def visit(
        reflect: Reflect.Bound[?],
        path   : Set[TypeId[?]],
        found  : Vector[Reflect.Bound[?]]
    ): Vector[Reflect.Bound[?]] =
      def node(children: Iterable[Reflect.Bound[?]]): Vector[Reflect.Bound[?]] =
        if !path.contains(reflect.typeId) then
          children.foldLeft(found)((acc, child) => visit(child, path + reflect.typeId, acc))
        else if found.iterator.map(_.typeId).contains(reflect.typeId) then found
        else found :+ reflect
      reflect match
        case deferred: Reflect.Deferred[Binding, ?] => visit(deferred.value, path, found)
        case seq: Reflect.Sequence[Binding, ?, ?]   => visit(seq.element, path, found)
        case map: Reflect.Map[Binding, ?, ?, ?]     => visit(map.value, path, visit(map.key, path, found))
        // The `Some` and `None` records of an `Option` are not types here: the walk goes to the value in `Some`.
        case variant: Reflect.Variant[Binding, ?] if variant.typeId.isOption || variant.typeId.isMaybe =>
          variant.cases.foldLeft(found)((acc, term) =>
            term.value match
              case rec: Reflect.Record[Binding, ?] =>
                rec.fields.foldLeft(acc)((a, field) => visit(field.value, path, a))
              case other => visit(other, path, acc)
          )
        case rec: Reflect.Record[Binding, ?]         => node(rec.fields.map(_.value))
        case variant: Reflect.Variant[Binding, ?]    => node(variant.cases.map(_.value))
        case wrapper: Reflect.Wrapper[Binding, ?, ?] => node(Vector(wrapper.wrapped))
        case _                                       => found
    visit(root, Set.empty, Vector.empty)

  /** A name in `$defs` for each type in `types`: the simple name of the type, with a number when two types have the
    * same simple name.
    */
  private def definitionNames(types: Vector[Reflect.Bound[?]]): Map[TypeId[?], String] =
    types.foldLeft(Map.empty[TypeId[?], String]) { (names, reflect) =>
      val base  = reflect.typeId.name.map(c => if c.isLetterOrDigit || c == '_' then c else '_')
      val taken = names.valuesIterator.toSet
      val name  = if !taken.contains(base) then base else Iterator.from(2).map(n => s"$base$n").filterNot(taken).next()
      names.updated(reflect.typeId, name)
    }

  private def refOf(name: String): String = s"#/$$defs/$name"

  /** The type id of a placeholder for the type with the definition `name`. */
  private def refTypeId[A](name: String): TypeId[A] = TypeId.nominal[A](refOf(name), Owner.Root)

  /** Gives each type in `names` the type id of its placeholder ([[refTypeId]]), except at the root. Thus the reflect of
    * a recursive type is the root with its fields, and each reference to a type in `names` is a placeholder.
    */
  private final class CutCycles(names: Map[TypeId[?], String])
      extends ReflectTransformer.OnlyMetadata[Binding, Binding]:
    override def transformMetadata[K, A](f: Binding[K, A]): Lazy[Binding[K, A]] = Lazy(f)

    private def shownTypeId[A](path: DynamicOptic, typeId: TypeId[A]): TypeId[A] =
      names.get(typeId) match
        case Some(name) if path.nodes.nonEmpty => refTypeId[A](name)
        case _                                 => typeId

    override def transformRecord[A](
        path              : DynamicOptic,
        fields            : IndexedSeq[Term[Binding, A, ?]],
        typeId            : TypeId[A],
        metadata          : Binding[BindingType.Record, A],
        doc               : Doc,
        modifiers         : Seq[Modifier.Reflect],
        storedDefaultValue: Option[DynamicValue],
        storedExamples    : collection.immutable.Seq[DynamicValue]
    ): Lazy[Reflect.Record[Binding, A]] =
      val shown = shownTypeId(path, typeId)
      super.transformRecord(path, fields, shown, metadata, doc, modifiers, storedDefaultValue, storedExamples)

    override def transformVariant[A](
        path              : DynamicOptic,
        cases             : IndexedSeq[Term[Binding, A, ? <: A]],
        typeId            : TypeId[A],
        metadata          : Binding[BindingType.Variant, A],
        doc               : Doc,
        modifiers         : Seq[Modifier.Reflect],
        storedDefaultValue: Option[DynamicValue],
        storedExamples    : collection.immutable.Seq[DynamicValue]
    ): Lazy[Reflect.Variant[Binding, A]] =
      val shown = shownTypeId(path, typeId)
      super.transformVariant(path, cases, shown, metadata, doc, modifiers, storedDefaultValue, storedExamples)

    override def transformWrapper[A, B](
        path              : DynamicOptic,
        wrapped           : Reflect[Binding, B],
        typeId            : TypeId[A],
        metadata          : Binding[BindingType.Wrapper[A, B], A],
        doc               : Doc,
        modifiers         : Seq[Modifier.Reflect],
        storedDefaultValue: Option[DynamicValue],
        storedExamples    : collection.immutable.Seq[DynamicValue]
    ): Lazy[Reflect.Wrapper[Binding, A, B]] =
      val shown = shownTypeId(path, typeId)
      super.transformWrapper(path, wrapped, shown, metadata, doc, modifiers, storedDefaultValue, storedExamples)

  /** The JSON codec of a placeholder: its JSON schema is a `$ref` to the definition `name`. The codec is only for the
    * JSON schema, so its encoder and its decoder fail.
    */
  private final class RefSchemaCodec(name: String) extends JsonCodec[Any]:
    override def decodeValue(in: JsonReader): Any = error(s"The placeholder of '${refOf(name)}' has no decoder")

    override def encodeValue(x: Any, out: JsonWriter): Unit =
      error(s"The placeholder of '${refOf(name)}' has no encoder")

    override def toJsonSchema: JsonSchema = JsonSchema.refString(refOf(name))

  private def primitiveTypeRef(prim: PrimitiveType[?]): TypeRef =
    prim match
      case _: PrimitiveType.String | _: PrimitiveType.Char                                               => TypeRef.string
      case _: PrimitiveType.Boolean                                                                      => TypeRef.bool
      case _: PrimitiveType.Int | _: PrimitiveType.Long | _: PrimitiveType.Short | _: PrimitiveType.Byte => TypeRef.int
      case _: PrimitiveType.Float | _: PrimitiveType.Double                                              => TypeRef.double
      case _                                                                                             => TypeRef.json

  /** Decode `dv` as an `A`. [[normalize]] first coerces `dv` to the reflect of `schema`, then `Schema.fromDynamicValue`
    * decodes it. An error of zio-blocks becomes a `ValidationError`.
    */
  def decodeValue[A](dv: DynamicValue)(using schema: Schema[A]): Either[DspyError, A] =
    schema.fromDynamicValue(normalize(dv, schema.reflect)).left.map(err => ValidationError(err.toString))

  /** Make a `Shape[A]` from a zio-blocks `Schema[A]`. The encode uses `Schema.toDynamicValue`. The decode first checks
    * that the record has each required field, and then uses [[decodeValue]].
    */
  def derivedFromZioSchema[A](using schema: Schema[A]): RoundTripShape[A] =
    val rootReflect   = schema.reflect
    val specs         = fieldSpecsFromReflect(rootReflect)
    val requireFields = Shape.requiredFieldsCheck(specs)

    new RoundTripShape[A]:
      override val fieldSpecs: Vector[FieldSpec] = specs

      override lazy val jsonSchemaString: Option[String] = Some(jsonSchemaForModel(schema))

      override lazy val nestedDecisionFields: Vector[String] = nestedDecisionFieldsOf(rootReflect)

      override def encode(value: A): DynamicValue.Record =
        schema.toDynamicValue(value) match
          case rec: DynamicValue.Record => rec
          case other                    => throw new IllegalStateException(
              s"zio-blocks Schema did not encode to a Record: $other"
            )

      // `normalize` gives `None` to each absent optional field, at each level.
      override def decode(raw: DynamicValue.Record): Either[DspyError, A] =
        requireFields(raw).flatMap(_ => decodeValue[A](raw))
