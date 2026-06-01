package dspy4s.typed

import dspy4s.core.contracts.{
  DspyError, DynamicValues, FieldRole, FieldSpec, NotFoundError, TypeRef, ValidationError
}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveType, PrimitiveValue, Reflect, Schema}

/** Bridge between zio-blocks `Schema[A]` and dspy4s's typed `Shape[A]`.
  *
  *   - [[derivedFromZioSchema]] -- the `Shape[A]` factory wired up to a `Schema[A]`. Encode goes through
  *     `Schema.toDynamicValue` directly; decode normalizes the incoming record (coercing LM-shaped string
  *     primitives like `"true"` / `"42"` into the target primitive types) and then calls
  *     `Schema.fromDynamicValue`.
  *   - [[fieldSpecsFromReflect]] -- derive `FieldSpec` list from a `Reflect.Record`.
  *   - [[normalize]] -- DynamicValue → DynamicValue coercion guided by a target `Reflect`. Public for the rare
  *     caller that needs to pre-coerce a record before decoding.
  *
  * No `Map[String, Any]` round-trip: the spine is `DynamicValue.Record` end-to-end.
  */
private[typed] object ZioSchemaCodec:

  /** Coerce a `DynamicValue` into the shape the target `Reflect` expects. The two coercions that matter in
    * practice:
    *
    *   - LM-shaped strings: `Primitive(String("true"))` → `Primitive(Boolean(true))` when the target field is
    *     `Boolean`; same for `Int` / `Long` / `Double` / `Float`.
    *   - Variant fields: a bare `Primitive(String("joy"))` becomes `Variant("joy", Record.empty)` so
    *     `Schema.fromDynamicValue` can pick the right case.
    *
    * Records are walked recursively against the target's field list (fields not present in the target are
    * dropped); sequences are walked element-wise. Everything else (and values whose target shape doesn't match
    * the dispatch above) passes through unchanged. */
  def normalize(dv: DynamicValue, target: Reflect[?, ?]): DynamicValue = (dv, target) match
    case (_: DynamicValue.Null.type, _)                          => DynamicValue.Null
    case (prim @ DynamicValue.Primitive(_), p: Reflect.Primitive[?, ?]) =>
      coercePrimitive(prim, p.primitiveType)
    case (DynamicValue.Primitive(PrimitiveValue.String(s)), _: Reflect.Variant[?, ?]) =>
      DynamicValue.Variant(s, DynamicValue.Record.empty)
    case (rec: DynamicValue.Record, recTarget: Reflect.Record[?, ?]) =>
      mapRecordFields(rec, recTarget)
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

  private def mapRecordFields(rec: DynamicValue.Record, target: Reflect.Record[?, ?]): DynamicValue.Record =
    val targetByName = target.fields.iterator.map(t => t.name -> t.value).toMap
    val out = rec.fields.iterator.map { (name, value) =>
      targetByName.get(name) match
        case Some(fieldReflect) => name -> normalize(value, fieldReflect)
        case None               => name -> value
    }.toSeq
    DynamicValue.Record(Chunk.from(out))

  private def coercePrimitive(dv: DynamicValue, prim: PrimitiveType[?]): DynamicValue =
    dv match
      case DynamicValue.Primitive(PrimitiveValue.String(s)) =>
        prim match
          case _: PrimitiveType.Boolean =>
            s.trim.toLowerCase match
              case "true"  => DynamicValue.Primitive(PrimitiveValue.Boolean(true))
              case "false" => DynamicValue.Primitive(PrimitiveValue.Boolean(false))
              case _       => dv
          case _: PrimitiveType.Int =>
            s.trim.toIntOption.fold(dv)(i => DynamicValue.Primitive(PrimitiveValue.Int(i)))
          case _: PrimitiveType.Long =>
            s.trim.toLongOption.fold(dv)(l => DynamicValue.Primitive(PrimitiveValue.Long(l)))
          case _: PrimitiveType.Double =>
            s.trim.toDoubleOption.fold(dv)(d => DynamicValue.Primitive(PrimitiveValue.Double(d)))
          case _: PrimitiveType.Float =>
            s.trim.toFloatOption.fold(dv)(f => DynamicValue.Primitive(PrimitiveValue.Float(f)))
          case _ => dv
      case DynamicValue.Primitive(PrimitiveValue.Int(i)) =>
        prim match
          case _: PrimitiveType.Long   => DynamicValue.Primitive(PrimitiveValue.Long(i.toLong))
          case _: PrimitiveType.Double => DynamicValue.Primitive(PrimitiveValue.Double(i.toDouble))
          case _: PrimitiveType.Float  => DynamicValue.Primitive(PrimitiveValue.Float(i.toFloat))
          case _                       => dv
      case DynamicValue.Primitive(PrimitiveValue.Long(l)) if isInt(l) =>
        prim match
          case _: PrimitiveType.Int    => DynamicValue.Primitive(PrimitiveValue.Int(l.toInt))
          case _                       => dv
      case _ => dv

  private def isInt(l: Long): Boolean = l >= Int.MinValue && l <= Int.MaxValue

  /** Walk a `Reflect.Record` and produce the FieldSpec list with role applied. */
  def fieldSpecsFromReflect(reflect: Reflect[?, ?], role: FieldRole): Vector[FieldSpec] = reflect match
    case rec: Reflect.Record[?, ?] =>
      rec.fields.toVector.map(term => FieldSpec(name = term.name, role = role, typeRef = typeRefFor(term.value)))
    case _ => Vector.empty

  private def typeRefFor(reflect: Reflect[?, ?]): TypeRef = reflect match
    case prim: Reflect.Primitive[?, ?] => primitiveTypeRef(prim.primitiveType)
    case _: Reflect.Variant[?, ?]      => TypeRef.string
    case _: Reflect.Record[?, ?]       => TypeRef.json
    case _: Reflect.Sequence[?, ?, ?]  => TypeRef.json
    case _: Reflect.Map[?, ?, ?, ?]    => TypeRef.json
    case _                              => TypeRef.json

  private def primitiveTypeRef(prim: PrimitiveType[?]): TypeRef = prim match
    case _: PrimitiveType.String  => TypeRef.string
    case _: PrimitiveType.Char    => TypeRef.string
    case _: PrimitiveType.Boolean => TypeRef.bool
    case _: PrimitiveType.Int     => TypeRef.int
    case _: PrimitiveType.Long    => TypeRef.int
    case _: PrimitiveType.Short   => TypeRef.int
    case _: PrimitiveType.Byte    => TypeRef.int
    case _: PrimitiveType.Float   => TypeRef.double
    case _: PrimitiveType.Double  => TypeRef.double
    case _: PrimitiveType.Unit.type => TypeRef.json
    case _                         => TypeRef.json

  /** Build a `Shape[A]` from a zio-blocks `Schema[A]`. Encode goes through `Schema.toDynamicValue` directly;
    * decode normalizes the input record against the target Reflect (so LM-shaped string primitives become the
    * right primitive types) and then delegates to `Schema.fromDynamicValue`. */
  def derivedFromZioSchema[A](role: FieldRole)(using schema: Schema[A]): Shape[A] =
    val rootReflect = schema.reflect
    val specs       = fieldSpecsFromReflect(rootReflect, role)

    new Shape[A]:
      override val fieldSpecs: Vector[FieldSpec] = specs

      override lazy val jsonSchemaString: Option[String] =
        Some(schema.toJsonSchema.toJson.toString)

      override def encode(value: A): DynamicValue.Record =
        schema.toDynamicValue(value) match
          case rec: DynamicValue.Record => rec
          case other =>
            throw new IllegalStateException(
              s"zio-blocks Schema did not encode to a Record: $other"
            )

      override def decode(raw: DynamicValue.Record): Either[DspyError, A] =
        val present = DynamicValues.recordKeys(raw).toSet
        val missing = fieldSpecs.iterator.map(_.name).filterNot(present.contains).toList
        if missing.nonEmpty then
          Left(NotFoundError(
            resource = "prediction_field",
            message  = s"Missing required fields: ${missing.mkString(", ")}"
          ))
        else
          val normalized = normalize(raw, rootReflect)
          schema.fromDynamicValue(normalized).left.map(err => ValidationError(err.toString))
