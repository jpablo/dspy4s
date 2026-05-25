package dspy4s.typed

import dspy4s.core.contracts.{
  DspyError, FieldMetadata, FieldRole, FieldSpec, NotFoundError, TypeRef,
  ValidationError
}
import kyo.{Chunk, Schema, Structure}
import scala.deriving.Mirror

/** A schema-aware view of a user type `A`, used as the input or output of a
  * `TypedSignature`. Lists fields in declaration order, converts each to a
  * `FieldSpec` for the untyped `Signature`, encodes typed values into the
  * `Map[String, Any]` carried through `ProgramCall`, and decodes a raw
  * `Map[String, Any]` produced by an adapter back into the typed `A`.
  *
  * Phase 2 supports only `A <: Product` (case classes). Phase 3 will add the
  * builder API for non-case-class shapes.
  */
trait Shape[A]:
  def fieldSpecs: Vector[FieldSpec]
  def encode(value: A): Map[String, Any]
  def decode(raw: Map[String, Any]): Either[DspyError, A]

object Shape:

  /** A `Shape[Map[String, Any]]` whose `fieldSpecs` are provided at
    * construction. Used by the trait-spec macro and any other surface
    * that produces field metadata without an accompanying case class.
    *
    * When `decoders` is non-empty, every declared field is run through
    * its `ValueDecoder` during `decode` (so a raw adapter value like the
    * string `"joy"` becomes the typed enum value `Sentiment.joy`), and
    * inputs are encoded through the same decoders on the way out (so a
    * typed enum value becomes its case-name string for the adapter).
    *
    * Fields not present in `decoders` pass through unchanged — that
    * preserves the original identity-shape behavior when callers don't
    * supply per-field decoders. `decode` always validates that every
    * field listed in `fieldSpecs` is present in the raw map. */
  final class MapShape(
      override val fieldSpecs: Vector[FieldSpec],
      decoders: Map[String, ValueDecoder[Any]] = Map.empty
  ) extends Shape[Map[String, Any]]:

    def encode(value: Map[String, Any]): Map[String, Any] =
      if decoders.isEmpty then value
      else value.map { (k, v) =>
        k -> decoders.get(k).fold(v: Any)(_.encode(v.asInstanceOf[Any]))
      }

    def decode(raw: Map[String, Any]): Either[DspyError, Map[String, Any]] =
      val missing = fieldSpecs.iterator.map(_.name).filterNot(raw.contains).toList
      if missing.nonEmpty then
        Left(NotFoundError(
          resource = "prediction_field",
          message  = s"Missing required fields: ${missing.mkString(", ")}"
        ))
      else if decoders.isEmpty then
        Right(raw)
      else
        // Decode each declared field through its decoder, short-circuit
        // on the first failure.
        val builder = Map.newBuilder[String, Any]
        val it = fieldSpecs.iterator
        while it.hasNext do
          val fs = it.next()
          val rawValue = raw(fs.name)
          decoders.get(fs.name) match
            case Some(dec) =>
              dec.decode(rawValue) match
                case Right(decoded) => builder += (fs.name -> decoded)
                case Left(err) =>
                  return Left(ValidationError(
                    s"Field '${fs.name}': ${err.message}"
                  ))
            case None =>
              builder += (fs.name -> rawValue)
        Right(builder.result())

  /** A tuple-backed shape used by the trait-spec macro. The macro gives
    * callers a named-tuple type, e.g. `(sentence: String)` for inputs and
    * `(sentiment: Emotion)` for outputs. At runtime named tuples erase to
    * ordinary tuples, so this shape pairs values with `fieldSpecs` by
    * declaration order.
    */
  final class TupleShape[A](
      override val fieldSpecs: Vector[FieldSpec],
      decoders: Map[String, ValueDecoder[Any]]
  ) extends Shape[A]:

    def encode(value: A): Map[String, Any] =
      val values = value.asInstanceOf[Product].productIterator.toVector
      fieldSpecs.zip(values).map { (fs, raw) =>
        val encoded = decoders.get(fs.name).fold(raw)(_.encode(raw.asInstanceOf[Any]))
        fs.name -> encoded
      }.toMap

    def decode(raw: Map[String, Any]): Either[DspyError, A] =
      val missing = fieldSpecs.iterator.map(_.name).filterNot(raw.contains).toList
      if missing.nonEmpty then
        Left(NotFoundError(
          resource = "prediction_field",
          message  = s"Missing required fields: ${missing.mkString(", ")}"
        ))
      else
        val builder = Array.newBuilder[Any]
        builder.sizeHint(fieldSpecs.size)
        val it = fieldSpecs.iterator
        while it.hasNext do
          val fs = it.next()
          val rawValue = raw(fs.name)
          decoders.get(fs.name) match
            case Some(dec) =>
              dec.decode(rawValue) match
                case Right(decoded) => builder += decoded
                case Left(err) =>
                  return Left(ValidationError(
                    s"Field '${fs.name}': ${err.message}"
                  ))
            case None =>
              builder += rawValue
        Right(Tuple.fromArray(builder.result()).asInstanceOf[A])

  /** Derives a `Shape[A]` from any case class with a `kyo.Schema[A]` in
    * scope. kyo-schema owns product encode/decode; dspy4s derives the
    * DSPy-facing field metadata from the same structural description. */
  inline def derived[A <: Product](using
      m: Mirror.ProductOf[A],
      schema: Schema[A]
  ): Shape[A] =
    derivedWithRole[A](FieldRole.Output)

  /** Derives a `Shape[A]` and stamps every field with the given role. Use
    * `FieldRole.Input` for input case classes, `FieldRole.Output` for
    * output case classes. `derived` defaults to `Output`; `TypedSignature`
    * builders that need inputs invoke this explicitly. */
  inline def derivedWithRole[A <: Product](role: FieldRole)(using
      m: Mirror.ProductOf[A],
      schema: Schema[A]
  ): Shape[A] =
    derivedProductWithRole[A](role)

  private[typed] inline def derivedProductWithRole[A](role: FieldRole)(using
      m: Mirror.ProductOf[A],
      schema: Schema[A]
  ): Shape[A] =
    new KyoProductShape[A](schema, schema.structure, role)

  // ── Internal: kyo-schema-backed Shape implementation ─────────────────────

  private[typed] final class KyoProductShape[A](
      schema: Schema[A],
      structure: Structure.Type,
      role: FieldRole
  ) extends Shape[A]:

    private val fields: Chunk[Structure.Field] =
      structure match
        case Structure.Type.Product(_, _, _, fields) => fields
        case other =>
          throw new IllegalArgumentException(
            s"Shape derivation requires a product type, got ${other.name}"
          )

    val fieldSpecs: Vector[FieldSpec] =
      fields.map(field => fieldSpec(field, role)).toVector

    def encode(value: A): Map[String, Any] =
      KyoSchemaValueDecoder.fromStructure(
        Structure.encode[A](value)(using schema, summon),
        structure
      ) match
        case map: Map[?, ?] =>
          map.asInstanceOf[Map[String, Any]]
        case other =>
          throw new IllegalStateException(
            s"Expected schema encode of ${structure.name} to produce a record, got: $other"
          )

    def decode(raw: Map[String, Any]): Either[DspyError, A] =
      val builder = Vector.newBuilder[(String, Structure.Value)]
      val it = fields.iterator
      while it.hasNext do
        val field = it.next()
        raw.get(field.name) match
          case None =>
            return Left(NotFoundError(
              resource = "prediction_field",
              message  = s"Required field '${field.name}' is missing from the raw prediction"
            ))
          case Some(value) =>
            builder += field.name -> KyoSchemaValueDecoder.toStructure(value, field.fieldType)
      val record = Structure.Value.Record(Chunk.from(builder.result()))
      KyoSchemaValueDecoder.toEither(Structure.decode[A](record)(using schema, summon))

  private def fieldSpec(field: Structure.Field, role: FieldRole): FieldSpec =
    val (typeRef, metadata) = metadataFor(field.fieldType)
    FieldSpec(
      name     = field.name,
      role     = role,
      typeRef  = typeRef,
      metadata = metadata
    )

  private def metadataFor(tpe: Structure.Type): (TypeRef, Map[String, String]) =
    tpe match
      case Structure.Type.Primitive(kind, _) =>
        kind match
          case Structure.PrimitiveKind.String | Structure.PrimitiveKind.Char =>
            TypeRef.string -> Map.empty
          case Structure.PrimitiveKind.Boolean =>
            TypeRef.bool -> Map.empty
          case Structure.PrimitiveKind.Int | Structure.PrimitiveKind.Long |
              Structure.PrimitiveKind.Short | Structure.PrimitiveKind.Byte |
              Structure.PrimitiveKind.BigInt =>
            TypeRef.int -> Map.empty
          case Structure.PrimitiveKind.Float | Structure.PrimitiveKind.Double |
              Structure.PrimitiveKind.BigDecimal =>
            TypeRef.double -> Map.empty
          case Structure.PrimitiveKind.Unit =>
            TypeRef.json -> Map.empty
      case Structure.Type.Sum(name, _, _, _, enumValues) if enumValues.nonEmpty =>
        TypeRef.string -> Map(
          FieldMetadata.EnumCases -> enumValues.mkString(","),
          FieldMetadata.EnumName  -> name
        )
      case _ =>
        TypeRef.json -> Map.empty
