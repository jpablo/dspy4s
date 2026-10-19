package dspy4s.signatures

import dspy4s.core.contracts.{
  DspyError,
  DynamicValues,
  FieldSpec,
  History,
  NotFoundError,
  ToolCall,
  TypeRef,
  ValidationError
}
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

/** Bridge between zio-blocks `Schema[A]` and dspy4s's `Shape[A]`.
  *
  *   - [[derivedFromZioSchema]] -- the `Shape[A]` factory wired up to a `Schema[A]`. Encode goes through
  *     `Schema.toDynamicValue` directly; decode normalizes the incoming record (coercing LM-shaped string primitives
  *     like `"true"` / `"42"` into the target primitive types) and then calls `Schema.fromDynamicValue`.
  *   - [[fieldSpecsFromReflect]] -- derive `FieldSpec` list from a `Reflect.Record`.
  *   - [[normalize]] -- DynamicValue → DynamicValue coercion guided by a target `Reflect`. Public for the rare caller
  *     that needs to pre-coerce a record before decoding.
  *   - [[jsonSchemaForModel]] -- the JSON schema of a `Schema[A]` for a prompt, with no `id` in a `ToolCall`, and with
  *     `$defs` for a recursive type.
  *
  * No `Map[String, Any]` round-trip: the interchange format is `DynamicValue.Record` end-to-end.
  */
private[signatures] object ZioSchemaCodec:

  /** Coerce a `DynamicValue` into the shape the target `Reflect` expects. The two coercions that matter in practice:
    *
    *   - LM-shaped strings: `Primitive(String("true"))` → `Primitive(Boolean(true))` when the target field is
    *     `Boolean`; same for `Int` / `Long` / `Double` / `Float`.
    *   - Variant fields: a bare `Primitive(String("joy"))` becomes `Variant("joy", Record.empty)` so
    *     `Schema.fromDynamicValue` can pick the right case.
    *
    * Records are walked recursively against the target's field list (fields not present in the target are dropped);
    * sequences are walked element-wise. Everything else (and values whose target shape doesn't match the dispatch
    * above) passes through unchanged.
    *
    * A record gets `None` for each `Option` field of the target that it lacks, at each level of nesting. A required
    * field that is absent stays absent, so the decode fails.
    */
  def normalize(dv: DynamicValue, target: Reflect[?, ?]): DynamicValue =
    (dv, target) match
      // zio-blocks defers the reflect of some nested fields. The coercion needs the reflect behind it.
      case (value, deferred: Reflect.Deferred[?, ?])                      => normalize(value, deferred.value)
      case (prim @ DynamicValue.Primitive(_), p: Reflect.Primitive[?, ?]) => coercePrimitive(prim, p.primitiveType)
      // Option-shaped variant: wrap bare/Null/string values into Some/None before the generic rules below.
      case (value, variant: Reflect.Variant[?, ?]) if optionSomeValueReflect(variant).isDefined =>
        normalizeOption(value, optionSomeValueReflect(variant).get)
      case (_: DynamicValue.Null.type, _)                                               => DynamicValue.Null
      case (DynamicValue.Primitive(PrimitiveValue.String(s)), _: Reflect.Variant[?, ?]) =>
        DynamicValue.Variant(s, DynamicValue.Record.empty)
      case (rec: DynamicValue.Record, recTarget: Reflect.Record[?, ?])        => mapRecordFields(rec, recTarget)
      case (seq: DynamicValue.Sequence, seqTarget: Reflect.Sequence[?, ?, ?]) =>
        DynamicValue.Sequence(Chunk.from(seq.elements.iterator.map(normalize(_, seqTarget.element)).toSeq))
      case (m: DynamicValue.Map, mapTarget: Reflect.Map[?, ?, ?, ?]) =>
        DynamicValue.Map(Chunk.from(m.entries.iterator.map((k, v) =>
          normalize(k, mapTarget.key) -> normalize(v, mapTarget.value)
        ).toSeq))
      case (rec: DynamicValue.Record, mapTarget: Reflect.Map[?, ?, ?, ?]) =>
        // dspy4s lifts string-keyed maps into `DynamicValue.Record` (see DynamicValues.fromAny),
        // whereas zio-blocks `Schema[Map[K, V]]` consumes a `DynamicValue.Map`. Bridge field -> entry.
        DynamicValue.Map(Chunk.from(rec.fields.iterator.map { (k, v) =>
          normalize(DynamicValue.Primitive(PrimitiveValue.String(k)), mapTarget.key) -> normalize(v, mapTarget.value)
        }.toSeq))
      case _ => dv

  /** Coerce an incoming value into the `Some`/`None` Variant shape zio-blocks expects for an `Option` field.
    * `valueReflect` is the `Some` case's `value` field reflect, so a wrapped payload routes back through [[normalize]]
    * and inherits the usual LM-shaped coercion (e.g. `"2399.0"` string → Double).
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
    val targetByName = target.fields.iterator.map(t => t.name -> t.value).toMap
    val out          = rec.fields.iterator.map { (name, value) =>
      targetByName.get(name) match
        case Some(fieldReflect) => name -> normalize(value, fieldReflect)
        case None               => name -> value
    }.toSeq
    // An Option field is not required on the wire: a record that omits the key means None, the same as an
    // explicit null/"none".
    val present         = rec.fields.iterator.map(_._1).toSet
    val absentOptionals = target.fields.iterator
      .filter(term => !present.contains(term.name) && optionSomeValueReflect(term.value).isDefined)
      .map(_.name -> none)
      .toSeq
    DynamicValue.Record(Chunk.from(out ++ absentOptionals))

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
      case DynamicValue.Primitive(PrimitiveValue.Long(l)) if isInt(l) =>
        prim match
          case _: PrimitiveType.Int => DynamicValue.Primitive(PrimitiveValue.Int(l.toInt))
          case _                    => dv
      case _ => dv

  private def isInt(l: Long): Boolean = l >= Int.MinValue && l <= Int.MaxValue

  /** Strip LM-shaped numeric noise so `toIntOption` / `toDoubleOption` etc. can parse the value: trim surrounding
    * whitespace, drop a leading currency symbol (`$ € £ ¥`) and a leading `+`, and remove thousands-separator commas.
    * The decimal point and a leading `-` are preserved. If the result still doesn't parse, the caller keeps the
    * original `DynamicValue` (graceful fallback).
    */
  private def cleanNumeric(s: String): String =
    val trimmed  = s.trim
    val noSymbol = if trimmed.nonEmpty && "$€£¥".contains(trimmed.head) then trimmed.tail.trim else trimmed
    val noSign   = if noSymbol.startsWith("+") then noSymbol.tail else noSymbol
    noSign.filter(_ != ',')

  /** Walk a `Reflect.Record` and produce its role-free field metadata. An `Option` field is an optional field. */
  def fieldSpecsFromReflect(reflect: Reflect[?, ?]): Vector[FieldSpec] =
    reflect match
      case rec: Reflect.Record[?, ?] => rec.fields.toVector.map(term =>
          FieldSpec(
            name = term.name,
            typeRef = typeRefFor(term.value),
            enumValues = enumValuesOf(term.value),
            optional = optionSomeValueReflect(term.value).isDefined
          )
        )
      case _ => Vector.empty

  /** Allowed case names for an enum field, i.e. a `Reflect.Variant`'s cases. Empty for any non-variant reflect. The
    * wire `typeRef` for a variant stays `TypeRef.string` (the LM sees a flat label); these values ride alongside so
    * adapters can tell the LM which labels are valid.
    */
  private def enumValuesOf(reflect: Reflect[?, ?]): Vector[String] =
    reflect match
      case variant: Reflect.Variant[?, ?] =>
        val cases            = variant.cases.toVector
        val allParameterless = cases.forall { term =>
          term.value match
            case rec: Reflect.Record[?, ?] => rec.fields.isEmpty
            case _                         => false
        }
        if allParameterless then cases.map(_.name) else Vector.empty
      case _ => Vector.empty

  /** Detect an `Option`-shaped `Reflect.Variant`: a `None` case (empty record) and a `Some` case whose record carries
    * exactly one field named `value`. Returns the `Some` case's value-field reflect when matched, so [[normalize]] can
    * route the wrapped payload through the existing coercion against the right target.
    */
  private def optionSomeValueReflect(reflect: Reflect[?, ?]): Option[Reflect[?, ?]] =
    reflect match
      case deferred: Reflect.Deferred[?, ?] => optionSomeValueReflect(deferred.value)
      case variant: Reflect.Variant[?, ?]   =>
        val cases = variant.cases.toVector
        if cases.size != 2 then None
        else
          val noneCase = cases.find(_.name == "None").flatMap { term =>
            term.value match
              case rec: Reflect.Record[?, ?] if rec.fields.isEmpty => Some(rec)
              case _                                               => None
          }
          val someValue = cases.find(_.name == "Some").flatMap { term =>
            term.value match
              case rec: Reflect.Record[?, ?] => rec.fields.toVector match
                  case Vector(field) if field.name == "value" => Some(field.value)
                  case _                                      => None
              case _ => None
          }
          if noneCase.isDefined then someValue else None
      case _ => None

  /** Derive the wire `TypeRef` for a single value type from its `Schema`. This is the same mapping
    * `fieldSpecsFromReflect` applies to each record field, exposed for the programmatic [[SignatureBuilder]] (which
    * types one field at a time rather than deriving a whole record).
    */
  def typeRefForSchema[A](using schema: Schema[A]): TypeRef = typeRefFor(schema.reflect)

  /** `true` when `A` is an `Option` type. A field of such a type is an optional field, as in [[fieldSpecsFromReflect]].
    */
  def isOptionSchema[A](using schema: Schema[A]): Boolean = optionSomeValueReflect(schema.reflect).isDefined

  private def typeRefFor(reflect: Reflect[?, ?]): TypeRef =
    reflect match
      case prim: Reflect.Primitive[?, ?]                                  => primitiveTypeRef(prim.primitiveType)
      case _: Reflect.Variant[?, ?]                                       => TypeRef.string
      case rec: Reflect.Record[?, ?] if isType(rec.typeId, historyTypeId) => TypeRef.history
      case _: Reflect.Record[?, ?]                                        => TypeRef.json
      case _: Reflect.Sequence[?, ?, ?]                                   => TypeRef.list
      case _: Reflect.Map[?, ?, ?, ?]                                     => TypeRef.json
      case _                                                              => TypeRef.json

  // The framework types that the derivation knows by their identity (the type name of the record reflect), not by
  // their structure.
  private val historyTypeId: TypeId[History]   = summon[Schema[History]].reflect.typeId
  private val toolCallTypeId: TypeId[ToolCall] = summon[Schema[ToolCall]].reflect.typeId

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
      case _: PrimitiveType.String    => TypeRef.string
      case _: PrimitiveType.Char      => TypeRef.string
      case _: PrimitiveType.Boolean   => TypeRef.bool
      case _: PrimitiveType.Int       => TypeRef.int
      case _: PrimitiveType.Long      => TypeRef.int
      case _: PrimitiveType.Short     => TypeRef.int
      case _: PrimitiveType.Byte      => TypeRef.int
      case _: PrimitiveType.Float     => TypeRef.double
      case _: PrimitiveType.Double    => TypeRef.double
      case _: PrimitiveType.Unit.type => TypeRef.json
      case _                          => TypeRef.json

  /** Build a `Shape[A]` from a zio-blocks `Schema[A]`. Encode goes through `Schema.toDynamicValue` directly; decode
    * normalizes the input record against the target Reflect (so LM-shaped string primitives become the right primitive
    * types) and then delegates to `Schema.fromDynamicValue`.
    */
  def derivedFromZioSchema[A](using schema: Schema[A]): RoundTripShape[A] =
    val rootReflect = schema.reflect
    val specs       = fieldSpecsFromReflect(rootReflect)
    // Option-fields are not required on the wire: an LM that omits the key entirely means None, the
    // same as an explicit null/"none". Collected once so decode can exempt them from the missing-field check.
    val optionalFieldNames: Set[String] = specs.filter(_.optional).map(_.name).toSet

    new RoundTripShape[A]:
      override val fieldSpecs: Vector[FieldSpec] = specs

      override lazy val jsonSchemaString: Option[String] = Some(jsonSchemaForModel(schema))

      override def encode(value: A): DynamicValue.Record =
        schema.toDynamicValue(value) match
          case rec: DynamicValue.Record => rec
          case other                    => throw new IllegalStateException(
              s"zio-blocks Schema did not encode to a Record: $other"
            )

      override def decode(raw: DynamicValue.Record): Either[DspyError, A] =
        val present = DynamicValues.recordKeys(raw).toSet
        val missing = fieldSpecs.iterator
          .map(_.name)
          .filterNot(present.contains)
          .filterNot(optionalFieldNames.contains)
          .toList
        if missing.nonEmpty then
          Left(NotFoundError(
            resource = "prediction_field",
            message = s"Missing required fields: ${missing.mkString(", ")}"
          ))
        else
          // `normalize` gives None to each absent optional field, at each level of nesting.
          val normalized = normalize(raw, rootReflect)
          schema.fromDynamicValue(normalized).left.map(err => ValidationError(err.toString))
