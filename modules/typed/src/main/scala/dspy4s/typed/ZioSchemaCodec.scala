package dspy4s.typed

import dspy4s.core.contracts.{
  DspyError, FieldMetadata, FieldRole, FieldSpec, NotFoundError, TypeRef, ValidationError
}
import zio.blocks.schema.{DynamicValue, PrimitiveType, PrimitiveValue, Reflect, Schema, Validation}

/** Migration glue between zio-blocks Schema and dspy4s's adapter intermediate (`Map[String, Any]`).
  *
  * `Shape[A]` today is backed by kyo-schema via `KyoProductShape`. This module provides the parallel
  * zio-blocks-backed implementation:
  *
  *   - [[dynamicToAny]] / [[anyToDynamic]] -- the converter between `DynamicValue` and the
  *     `Map[String, Any]` / collection / primitive tree adapters consume.
  *   - [[fieldSpecsFromReflect]] -- derives `FieldSpec` list from a `Reflect.Record`, matching the
  *     metadata story the existing kyo-backed shape produces (`FieldMetadata.EnumCases` / `EnumName`
  *     for variant-typed fields).
  *   - [[derivedFromZioSchema]] -- the new `Shape[A]` factory; the migration target.
  *
  * Once all callers move off `Shape.derived` (kyo) onto [[derivedFromZioSchema]], the kyo pieces
  * (`KyoSchemaFieldCodec`, `KyoProductShape`, `Shape.derived`'s kyo dependency) are deleted.
  */
private[typed] object ZioSchemaCodec:

  /** Convert a zio-blocks `DynamicValue` into the `Map[String, Any]` / `List` / primitive tree that
    * dspy4s adapters consume. Enum-style `Variant`s flatten to their case-name string -- this is the
    * DSPy wire-format convention (`"joy"` rather than `{"joy": {}}`). */
  def dynamicToAny(dv: DynamicValue): Any = dv match
    case DynamicValue.Primitive(value) => primitiveValueToAny(value)
    case rec: DynamicValue.Record      =>
      rec.fields.iterator.map { (name, value) => name -> dynamicToAny(value) }.toMap
    case variant: DynamicValue.Variant =>
      // Flatten to the case name (DSPy convention). Drop the wrapped value if any; for parameter-less
      // enum cases (the common case) the wrapped value is Null anyway.
      variant.caseName.getOrElse(dynamicToAny(variant.value))
    case seq: DynamicValue.Sequence    => seq.elements.iterator.map(dynamicToAny).toList
    case map: DynamicValue.Map         =>
      map.entries.iterator.map((k, v) => dynamicToAny(k) -> dynamicToAny(v)).toMap
    case DynamicValue.Null             => null

  private def primitiveValueToAny(pv: PrimitiveValue): Any = pv match
    case PrimitiveValue.String(v)  => v
    case PrimitiveValue.Boolean(v) => v
    case PrimitiveValue.Int(v)     => v
    case PrimitiveValue.Long(v)    => v
    case PrimitiveValue.Float(v)   => v
    case PrimitiveValue.Double(v)  => v
    case PrimitiveValue.Short(v)   => v
    case PrimitiveValue.Byte(v)    => v
    case PrimitiveValue.Char(v)    => v
    case PrimitiveValue.Unit       => ()
    case other                     => other.toString // BigInt, BigDecimal, temporal types -- stringify for now

  /** Convert a `Map[String, Any]` / collection / primitive value into a `DynamicValue`, guided by the
    * target `Reflect`. The target tells us the expected primitive kind (`PrimitiveType.Boolean` etc.)
    * so we can do coercive parsing: a raw `String("true")` becomes `PrimitiveValue.Boolean(true)`
    * when the field expects a Boolean.
    *
    * Variant targets accept a bare string (`"joy"`) and lift it to
    * `DynamicValue.Variant(caseName = Some("joy"), value = DynamicValue.Null)` -- the inverse of the
    * Variant-flattening in [[dynamicToAny]]. */
  def anyToDynamic(value: Any, target: Reflect[?, ?]): DynamicValue = (value, target) match
    case (null, _)                                  => DynamicValue.Null
    case (dv: DynamicValue, _)                      => dv
    case (_, prim: Reflect.Primitive[?, ?])         => coercePrimitive(value, prim.primitiveType)
    case (m: collection.Map[?, ?], rec: Reflect.Record[?, ?])    => mapToRecord(m, rec)
    case (p: Product, rec: Reflect.Record[?, ?]) if p.productElementNames.nonEmpty =>
      mapToRecord(p.productElementNames.zip(p.productIterator).toMap, rec)
    case (s: String, v: Reflect.Variant[?, ?])      =>
      // Parameter-less enum case: payload is an empty record (zio-blocks expects a Record, not Null).
      DynamicValue.Variant(s, DynamicValue.Record(zio.blocks.chunk.Chunk.empty))
    case (m: collection.Map[?, ?], v: Reflect.Variant[?, ?]) if m.size == 1 =>
      // Discriminated-object form {"joy": {}} -- pull the single key as the case name.
      val (caseName, _) = m.iterator.next()
      DynamicValue.Variant(caseName.toString, DynamicValue.Record(zio.blocks.chunk.Chunk.empty))
    case (seq: Seq[?], coll: Reflect.Sequence[?, ?, ?]) =>
      DynamicValue.Sequence(zio.blocks.chunk.Chunk.from(seq.map(anyToDynamic(_, coll.element))))
    case (m: collection.Map[?, ?], mp: Reflect.Map[?, ?, ?, ?]) =>
      val entries = m.iterator.map((k, v) => anyToDynamic(k, mp.key) -> anyToDynamic(v, mp.value)).toSeq
      DynamicValue.Map(zio.blocks.chunk.Chunk.from(entries))
    case _                                          =>
      // Fallback: best-effort primitive wrapping; let fromDynamicValue surface the type error.
      anyToDynamicLoose(value)

  private def mapToRecord(m: collection.Map[?, ?], rec: Reflect.Record[?, ?]): DynamicValue =
    val byName: collection.Map[String, Any] = m.iterator.collect { case (k: String, v) => k -> v }.toMap
    val converted = rec.fields.iterator.flatMap { term =>
      byName.get(term.name).map { raw =>
        term.name -> anyToDynamic(raw, term.value)
      }
    }.toSeq
    DynamicValue.Record(zio.blocks.chunk.Chunk.from(converted))

  /** Best-effort: wrap a value as a `DynamicValue` without target-type guidance. Used for the
    * fallback branch in [[anyToDynamic]] when no `Reflect` is available, and also from the
    * `Shape.encodeToDynamic` default for shapes without a backing `Schema[A]`. */
  def anyToDynamicLoose(value: Any): DynamicValue = value match
    case null         => DynamicValue.Null
    case s: String    => DynamicValue.Primitive(PrimitiveValue.String(s))
    case b: Boolean   => DynamicValue.Primitive(PrimitiveValue.Boolean(b))
    case i: Int       => DynamicValue.Primitive(PrimitiveValue.Int(i))
    case l: Long      => DynamicValue.Primitive(PrimitiveValue.Long(l))
    case f: Float     => DynamicValue.Primitive(PrimitiveValue.Float(f))
    case d: Double    => DynamicValue.Primitive(PrimitiveValue.Double(d))
    case seq: Seq[?]  => DynamicValue.Sequence(zio.blocks.chunk.Chunk.from(seq.map(anyToDynamicLoose)))
    case m: collection.Map[?, ?] =>
      val fields = m.iterator.collect { case (k: String, v) => k -> anyToDynamicLoose(v) }.toSeq
      DynamicValue.Record(zio.blocks.chunk.Chunk.from(fields))
    case other => DynamicValue.Primitive(PrimitiveValue.String(other.toString))

  private def coercePrimitive(value: Any, prim: PrimitiveType[?]): DynamicValue =
    prim match
      case _: PrimitiveType.String  => DynamicValue.Primitive(PrimitiveValue.String(value.toString))
      case _: PrimitiveType.Boolean =>
        value match
          case b: Boolean => DynamicValue.Primitive(PrimitiveValue.Boolean(b))
          case s: String  =>
            s.trim.toLowerCase match
              case "true"  => DynamicValue.Primitive(PrimitiveValue.Boolean(true))
              case "false" => DynamicValue.Primitive(PrimitiveValue.Boolean(false))
              case _       => DynamicValue.Primitive(PrimitiveValue.String(s))
          case _          => DynamicValue.Primitive(PrimitiveValue.String(value.toString))
      case _: PrimitiveType.Int =>
        value match
          case i: Int                                            => DynamicValue.Primitive(PrimitiveValue.Int(i))
          case l: Long if l >= Int.MinValue && l <= Int.MaxValue => DynamicValue.Primitive(PrimitiveValue.Int(l.toInt))
          case s: String                                         =>
            s.trim.toIntOption
              .map(i => DynamicValue.Primitive(PrimitiveValue.Int(i)))
              .getOrElse(DynamicValue.Primitive(PrimitiveValue.String(s)))
          case _                                                 => anyToDynamicLoose(value)
      case _: PrimitiveType.Long =>
        value match
          case l: Long   => DynamicValue.Primitive(PrimitiveValue.Long(l))
          case i: Int    => DynamicValue.Primitive(PrimitiveValue.Long(i.toLong))
          case s: String =>
            s.trim.toLongOption
              .map(l => DynamicValue.Primitive(PrimitiveValue.Long(l)))
              .getOrElse(DynamicValue.Primitive(PrimitiveValue.String(s)))
          case _ => anyToDynamicLoose(value)
      case _: PrimitiveType.Double =>
        value match
          case d: Double => DynamicValue.Primitive(PrimitiveValue.Double(d))
          case f: Float  => DynamicValue.Primitive(PrimitiveValue.Double(f.toDouble))
          case i: Int    => DynamicValue.Primitive(PrimitiveValue.Double(i.toDouble))
          case l: Long   => DynamicValue.Primitive(PrimitiveValue.Double(l.toDouble))
          case s: String =>
            s.trim.toDoubleOption
              .map(d => DynamicValue.Primitive(PrimitiveValue.Double(d)))
              .getOrElse(DynamicValue.Primitive(PrimitiveValue.String(s)))
          case _ => anyToDynamicLoose(value)
      case _ => anyToDynamicLoose(value)

  /** Walk a Reflect.Record and produce the FieldSpec list with role applied and standard metadata
    * (`EnumCases` / `EnumName` for variants) attached. Mirrors what `Shape.metadataFor` does today
    * for the kyo-schema-backed product shape. */
  def fieldSpecsFromReflect(reflect: Reflect[?, ?], role: FieldRole): Vector[FieldSpec] = reflect match
    case rec: Reflect.Record[?, ?] =>
      rec.fields.toVector.map(term => fieldSpec(term.name, term.value, role))
    case _ => Vector.empty

  private def fieldSpec(name: String, reflect: Reflect[?, ?], role: FieldRole): FieldSpec =
    val (typeRef, metadata) = metadataFor(reflect)
    FieldSpec(name = name, role = role, typeRef = typeRef, metadata = metadata)

  private def metadataFor(reflect: Reflect[?, ?]): (TypeRef, Map[String, String]) =
    reflect match
      case prim: Reflect.Primitive[?, ?] => primitiveTypeRef(prim.primitiveType) -> Map.empty
      case variant: Reflect.Variant[?, ?] =>
        val caseNames = variant.cases.toVector.map(_.name)
        val typeName  = variant.typeId.name
        TypeRef.string -> Map(
          FieldMetadata.EnumCases -> caseNames.mkString(","),
          FieldMetadata.EnumName  -> typeName
        )
      case _: Reflect.Record[?, ?]    => TypeRef.json -> Map.empty
      case _: Reflect.Sequence[?, ?, ?] => TypeRef.json -> Map.empty
      case _: Reflect.Map[?, ?, ?, ?]   => TypeRef.json -> Map.empty
      case _                             => TypeRef.json -> Map.empty

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
    case PrimitiveType.Unit       => TypeRef.json
    case _                         => TypeRef.json

  /** Build a `Shape[A]` from a zio-blocks `Schema[A]`. The shape walks the schema's `Reflect.Record`
    * for `fieldSpecs`, encodes via `Schema.toDynamicValue` + [[dynamicToAny]], and decodes via
    * [[anyToDynamic]] + `Schema.fromDynamicValue` -- with the target Reflect guiding coercive
    * normalization of LM-shaped strings (`"true"` -> Boolean etc.). */
  def derivedFromZioSchema[A](role: FieldRole)(using schema: Schema[A]): Shape[A] =
    val rootReflect = schema.reflect
    val specs       = fieldSpecsFromReflect(rootReflect, role)

    new Shape[A]:
      override val fieldSpecs: Vector[FieldSpec] = specs

      override lazy val jsonSchemaString: Option[String] =
        Some(schema.toJsonSchema.toJson.toString)

      // Native DynamicValue path -- no Map round-trip.

      override def encodeToDynamic(value: A): DynamicValue =
        schema.toDynamicValue(value)

      override def decodeFromDynamic(dyn: DynamicValue): Either[DspyError, A] =
        schema.fromDynamicValue(dyn).left.map(err => ValidationError(err.toString))

      // Map-based shims for callers that produce / consume `Map[String, Any]`.

      override def encode(value: A): Map[String, Any] =
        dynamicToAny(encodeToDynamic(value)) match
          case map: Map[?, ?] => map.asInstanceOf[Map[String, Any]]
          case other =>
            throw new IllegalStateException(
              s"zio-blocks Schema did not encode to a record: $other"
            )

      override def decode(raw: Map[String, Any]): Either[DspyError, A] =
        val missing = fieldSpecs.iterator.map(_.name).filterNot(raw.contains).toList
        if missing.nonEmpty then
          Left(NotFoundError(
            resource = "prediction_field",
            message  = s"Missing required fields: ${missing.mkString(", ")}"
          ))
        else
          decodeFromDynamic(anyToDynamic(raw, rootReflect))
