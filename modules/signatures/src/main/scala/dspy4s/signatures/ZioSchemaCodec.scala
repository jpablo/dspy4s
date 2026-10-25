package dspy4s.signatures

import dspy4s.core.contracts.{DspyError, FieldSpec, History, ToolCall, TypeRef, ValidationError}
import dspy4s.core.contracts.Reflects.{caseNames, withoutDeferred}
import dspy4s.core.decisions.{Choice, Noul, Score}
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
  * The interchange format is `DynamicValue.Record` from end to end. The codec does not use a `Map[String, Any]`.
  */
private[signatures] object ZioSchemaCodec:

  /** Coerce a `DynamicValue` to the form that the target `Reflect` expects. These are the two important coercions:
    *
    *   - LM text: `Primitive(String("true"))` becomes `Primitive(Boolean(true))` when the target field is `Boolean`.
    *     The same rule applies to `Int`, `Long`, `Double`, and `Float`.
    *   - A variant field: a bare `Primitive(String("joy"))` becomes `Variant("joy", Record.empty)`, so that
    *     `Schema.fromDynamicValue` can select the correct case.
    *
    * The coercion goes into each field of a record that the target has. A field that the target does not have stays as
    * it is. The coercion also goes into each element of a sequence and each entry of a map. Each other value, and a
    * value that does not agree with its target, stays as it is.
    *
    * A record gets `None` for each `Option` field of the target that it does not have, at each level. A required field
    * that is absent stays absent, thus the decode fails.
    */
  def normalize(dv: DynamicValue, target: Reflect[?, ?]): DynamicValue =
    // zio-blocks defers the reflect of some nested fields. The coercion needs the reflect behind it.
    (dv, withoutDeferred(target)) match
      case (prim @ DynamicValue.Primitive(_), p: Reflect.Primitive[?, ?]) => coercePrimitive(prim, p.primitiveType)
      // An `Option` field: put a bare value, a null, or a text into `Some` or `None` before the general rules below.
      case (value, OptionOf(element))                                                   => normalizeOption(value, element)
      case (_: DynamicValue.Null.type, _)                                               => DynamicValue.Null
      case (DynamicValue.Primitive(PrimitiveValue.String(s)), _: Reflect.Variant[?, ?]) =>
        DynamicValue.Variant(s, DynamicValue.Record.empty)
      case (rec: DynamicValue.Record, recTarget: Reflect.Record[?, ?])        => mapRecordFields(rec, recTarget)
      case (seq: DynamicValue.Sequence, seqTarget: Reflect.Sequence[?, ?, ?]) =>
        DynamicValue.Sequence(seq.elements.map(normalize(_, seqTarget.element)))
      case (m: DynamicValue.Map, mapTarget: Reflect.Map[?, ?, ?, ?]) =>
        DynamicValue.Map(m.entries.map((k, v) => normalize(k, mapTarget.key) -> normalize(v, mapTarget.value)))
      case (rec: DynamicValue.Record, mapTarget: Reflect.Map[?, ?, ?, ?]) =>
        // dspy4s keeps a map with text keys as a `DynamicValue.Record` (see `DynamicValues.fromAny`), but the zio-blocks
        // `Schema[Map[K, V]]` reads a `DynamicValue.Map`. Each field becomes an entry.
        DynamicValue.Map(rec.fields.map { (k, v) =>
          normalize(DynamicValue.Primitive(PrimitiveValue.String(k)), mapTarget.key) -> normalize(v, mapTarget.value)
        })
      case _ => dv

  /** Coerce a value to the `Some` or `None` variant that zio-blocks expects for an `Option` field. `valueReflect` is
    * the reflect of the `value` field of `Some`. Thus a value in `Some` goes through [[normalize]] again and gets the
    * usual coercion (for example, the text `"2399.0"` becomes a `Double`).
    */
  private def normalizeOption(value: DynamicValue, valueReflect: Reflect[?, ?]): DynamicValue =
    def some(v: DynamicValue): DynamicValue =
      DynamicValue.Variant("Some", DynamicValue.Record(Chunk.single("value" -> normalize(v, valueReflect))))
    value match
      case _: DynamicValue.Null.type     => none
      case variant: DynamicValue.Variant => variant
      case DynamicValue.Primitive(PrimitiveValue.String(s))
          if s.trim.isEmpty
            || s.trim.equalsIgnoreCase("null")
            || s.trim.equalsIgnoreCase("none")
            || s.trim.equalsIgnoreCase("n/a") => none
      case other => some(other)

  /** The `None` case of an `Option`, in the Variant shape that zio-blocks expects. */
  private def none: DynamicValue = DynamicValue.Variant("None", DynamicValue.Record.empty)

  private def mapRecordFields(rec: DynamicValue.Record, target: Reflect.Record[?, ?]): DynamicValue.Record =
    val fields = rec.fields.map { (name, value) =>
      name -> target.fieldByName(name).fold(value)(term => normalize(value, term.value))
    }
    // The wire does not require an `Option` field: a record with no such key means `None`, the same as an explicit
    // null or "none".
    val absentOptionals = target.fields.collect {
      case term if isOption(term.value) && !rec.fields.exists(_._1 == term.name) => term.name -> none
    }
    if absentOptionals.isEmpty then DynamicValue.Record(fields)
    else DynamicValue.Record(fields ++ Chunk.from(absentOptionals))

  private def coercePrimitive(dv: DynamicValue, prim: PrimitiveType[?]): DynamicValue =
    dv match
      case DynamicValue.Primitive(PrimitiveValue.String(s)) => prim match
          case _: PrimitiveType.Boolean => s.trim.toLowerCase match
              case "true"  => DynamicValue.Primitive(PrimitiveValue.Boolean(true))
              case "false" => DynamicValue.Primitive(PrimitiveValue.Boolean(false))
              case _       => dv
          case _: PrimitiveType.Int =>
            cleanNumeric(s).toIntOption.fold(dv)(i => DynamicValue.Primitive(PrimitiveValue.Int(i)))
          case _: PrimitiveType.Long =>
            cleanNumeric(s).toLongOption.fold(dv)(l => DynamicValue.Primitive(PrimitiveValue.Long(l)))
          case _: PrimitiveType.Double =>
            cleanNumeric(s).toDoubleOption.fold(dv)(d => DynamicValue.Primitive(PrimitiveValue.Double(d)))
          case _: PrimitiveType.Float =>
            cleanNumeric(s).toFloatOption.fold(dv)(f => DynamicValue.Primitive(PrimitiveValue.Float(f)))
          case _ => dv
      case DynamicValue.Primitive(PrimitiveValue.Int(i)) => prim match
          case _: PrimitiveType.Long   => DynamicValue.Primitive(PrimitiveValue.Long(i.toLong))
          case _: PrimitiveType.Double => DynamicValue.Primitive(PrimitiveValue.Double(i.toDouble))
          case _: PrimitiveType.Float  => DynamicValue.Primitive(PrimitiveValue.Float(i.toFloat))
          case _                       => dv
      case DynamicValue.Primitive(PrimitiveValue.Long(l)) => prim match
          case _: PrimitiveType.Int if isInt(l) => DynamicValue.Primitive(PrimitiveValue.Int(l.toInt))
          case _: PrimitiveType.Double          => DynamicValue.Primitive(PrimitiveValue.Double(l.toDouble))
          case _: PrimitiveType.Float           => DynamicValue.Primitive(PrimitiveValue.Float(l.toFloat))
          case _                                => dv
      // The dynamic JSON codec of zio-blocks gives a `BigDecimal` for each number that is not an integer.
      case DynamicValue.Primitive(PrimitiveValue.BigDecimal(n)) => prim match
          case _: PrimitiveType.Double                => DynamicValue.Primitive(PrimitiveValue.Double(n.toDouble))
          case _: PrimitiveType.Float                 => DynamicValue.Primitive(PrimitiveValue.Float(n.toFloat))
          case _: PrimitiveType.Int if n.isValidInt   => DynamicValue.Primitive(PrimitiveValue.Int(n.toInt))
          case _: PrimitiveType.Long if n.isValidLong => DynamicValue.Primitive(PrimitiveValue.Long(n.toLong))
          case _                                      => dv
      case _ => dv

  private def isInt(l: Long): Boolean = l >= Int.MinValue && l <= Int.MaxValue

  /** Remove the LM text around a number, so that `toIntOption`, `toDoubleOption`, and the other parsers can read it.
    * The function removes the spaces at the two ends, a currency symbol (`$ € £ ¥`) and a `+` at the start, and each
    * comma that separates the thousands. It keeps the decimal point and a `-` at the start. When a parser cannot read
    * the result, the caller keeps the original `DynamicValue`.
    */
  private def cleanNumeric(s: String): String =
    val trimmed  = s.trim
    val noSymbol = if trimmed.nonEmpty && "$€£¥".contains(trimmed.head) then trimmed.tail.trim else trimmed
    val noSign   = if noSymbol.startsWith("+") then noSymbol.tail else noSymbol
    noSign.filter(_ != ',')

  /** The field specs of a `Reflect.Record`, in the order of its fields, with no input or output role. Empty for each
    * other reflect.
    */
  def fieldSpecsFromReflect(reflect: Reflect[?, ?]): Vector[FieldSpec] =
    reflect match
      case rec: Reflect.Record[?, ?] => rec.fields.toVector.map(term => fieldSpecOf(term.name, term.value))
      case _                         => Vector.empty

  /** The field spec of the field `name` with the reflect `reflect`. An `Option` field is an optional field, with the
    * type and the enum values of its element.
    */
  def fieldSpecOf(name: String, reflect: Reflect[?, ?]): FieldSpec =
    FieldSpec(
      name = name,
      typeRef = typeRefFor(reflect),
      enumValues = enumValuesOf(reflect),
      optional = isOption(reflect)
    )

  /** The case names that an enum field accepts: the cases of a `Reflect.Variant`. Empty for each reflect that is not a
    * variant. The wire `typeRef` of a variant stays `TypeRef.string`, because the LM sees a plain label. The adapters
    * use these values to tell the LM which labels are valid. An `Option` has the enum values of its element. A `Score`
    * field has its levels, and a `Choice` field has its option labels (see [[decisionField]]).
    */
  private def enumValuesOf(reflect: Reflect[?, ?]): Vector[String] =
    reflect match
      case OptionOf(element) => enumValuesOf(element)
      case _                 => decisionField(reflect).fold(caseNames(reflect))(_._2)

  /** Matches the reflect of an `Option`: a variant with a `None` case (an empty record) and a `Some` case, whose record
    * has one field with the name `value`. The result is the reflect of that `value` field, so that [[normalize]] can
    * coerce the value in `Some` to the correct target.
    */
  private object OptionOf:
    def unapply(reflect: Reflect[?, ?]): Option[Reflect[?, ?]] =
      withoutDeferred(reflect) match
        case variant: Reflect.Variant[?, ?] if variant.cases.size == 2 =>
          val hasNone = variant.caseByName("None").exists(term => isEmptyRecord(term.value))
          val value   = variant.caseByName("Some").flatMap { term =>
            term.value match
              case rec: Reflect.Record[?, ?] if rec.fields.size == 1 => rec.fieldByName("value").map(_.value)
              case _                                                 => None
          }
          if hasNone then value else None
        case _ => None

    private def isEmptyRecord(reflect: Reflect[?, ?]): Boolean =
      reflect match
        case rec: Reflect.Record[?, ?] => rec.fields.isEmpty
        case _                         => false

  private def isOption(reflect: Reflect[?, ?]): Boolean = OptionOf.unapply(reflect).isDefined

  /** The wire `TypeRef` of a value type, from its `Schema`. [[fieldSpecsFromReflect]] uses the same rules for each
    * field of a record. `SchemaInterop` gives it to the callers outside this module.
    */
  def typeRefForSchema[A](using schema: Schema[A]): TypeRef = typeRefFor(schema.reflect)

  /** The wire type of a reflect. An `Option` has the type of its element: an `Option[Int]` field is an optional int
    * field.
    */
  private def typeRefFor(reflect: Reflect[?, ?]): TypeRef =
    reflect match
      case OptionOf(element) => typeRefFor(element)
      case _                 => decisionField(reflect).fold(typeRefOfValue(reflect))(_._1)

  /** The names of the fields of a `Reflect.Record` whose type holds a decision type inside an `Option`, a collection,
    * or a map (experimental, upstream `resolve_adapter` and `extract_custom_type_from_annotation`). A field of a
    * decision type is a decision output, thus it is not in the result. The walk does not go into the fields of a
    * record, as upstream does not go into the fields of a model.
    */
  def nestedDecisionFieldsOf(reflect: Reflect[?, ?]): Vector[String] =
    withoutDeferred(reflect) match
      case rec: Reflect.Record[?, ?] => rec.fields.toVector.collect {
          case term if decisionField(term.value).isEmpty && holdsDecision(term.value) => term.name
        }
      case _ => Vector.empty

  /** `true` when `reflect` is a decision type, or an `Option`, a collection, or a map that holds one. */
  private def holdsDecision(reflect: Reflect[?, ?]): Boolean =
    withoutDeferred(reflect) match
      case OptionOf(element)                       => holdsDecision(element)
      case found if decisionField(found).isDefined => true
      case seq: Reflect.Sequence[?, ?, ?]          => holdsDecision(seq.element)
      case map: Reflect.Map[?, ?, ?, ?]            => holdsDecision(map.key) || holdsDecision(map.value)
      case _                                       => false

  /** The type and the enum values of a decision field (experimental, upstream `dspy.experimental`): a [[Noul]] has no
    * enum values, a [[Score]] has its levels (the cases of `L`, which the schema of `Score[L]` holds), and a [[Choice]]
    * has its option labels (the cases of the reflect of its `value` field). `None` for each other reflect. The
    * derivation knows the three types by their type id, not by their structure.
    */
  private def decisionField(reflect: Reflect[?, ?]): Option[(TypeRef, Vector[String])] =
    withoutDeferred(reflect) match
      case rec: Reflect.Record[?, ?] if isType(rec.typeId, noulTypeId)   => Some(TypeRef.noul -> Vector.empty)
      case rec: Reflect.Record[?, ?] if isType(rec.typeId, scoreTypeId)  => Some(TypeRef.score -> Score.levelsOf(rec))
      case rec: Reflect.Record[?, ?] if isType(rec.typeId, choiceTypeId) =>
        val options = rec.fieldByName("value").fold(Vector.empty[String])(term => caseNames(term.value))
        Some(TypeRef.choice -> options)
      case _ => None

  private def typeRefOfValue(reflect: Reflect[?, ?]): TypeRef =
    withoutDeferred(reflect) match
      case prim: Reflect.Primitive[?, ?]                                  => primitiveTypeRef(prim.primitiveType)
      case _: Reflect.Variant[?, ?]                                       => TypeRef.string
      case rec: Reflect.Record[?, ?] if isType(rec.typeId, historyTypeId) => TypeRef.history
      case _: Reflect.Sequence[?, ?, ?]                                   => TypeRef.list
      case _                                                              => TypeRef.json

  // The framework types that the derivation knows by their identity (the type name of the record reflect), not by
  // their structure.
  private val historyTypeId: TypeId[History]   = summon[Schema[History]].reflect.typeId
  private val toolCallTypeId: TypeId[ToolCall] = summon[Schema[ToolCall]].reflect.typeId
  private val noulTypeId: TypeId[Noul]         = summon[Schema[Noul]].reflect.typeId
  // `Score` and `Choice` are generic. The full name of a type id does not include the type arguments.
  private val scoreTypeId: TypeId[Score]   = TypeId.of[Score]
  private val choiceTypeId: TypeId[Choice] = TypeId.of[Choice]

  private def isType(typeId: TypeId[?], known: TypeId[?]): Boolean = typeId.fullName == known.fullName

  /** Marks the `id` field of each [[ToolCall]] record as transient, so the JSON schema does not show that field. Each
    * other part of the reflect stays as it is.
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
        if !isType(typeId, toolCallTypeId) then fields
        else
          fields.map { term =>
            if term.name == "id" then term.copy(modifiers = term.modifiers :+ Modifier.transient()) else term
          }
      super.transformRecord(path, shown, typeId, metadata, doc, modifiers, storedDefaultValue, storedExamples)

  /** The JSON schema of `schema` as a model sees it. The schema does not ask for the `id` of a [[ToolCall]] (upstream
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
