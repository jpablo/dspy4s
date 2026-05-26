package dspy4s.typed

import dspy4s.core.contracts.{
  DspyError, DynamicValues, FieldRole, FieldSpec, NotFoundError, ValidationError
}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, Schema}

/** A schema-aware view of a user type `A`, used as the input or output of a `Signature`. Lists fields in
  * declaration order, converts each to a `FieldSpec` for the untyped `SignatureLayout`, and encodes / decodes
  * typed values against the `DynamicValue.Record` spine carried through `ProgramCall`, adapters, and
  * `DynamicPrediction`.
  *
  * The codec spine is `DynamicValue.Record` end-to-end: `Shape.encode` produces one, `Shape.decode` consumes one,
  * and adapters speak the same intermediate. There is no `Map[String, Any]` round-trip.
  */
trait Shape[A]:
  def fieldSpecs: Vector[FieldSpec]

  /** Encode a typed value to a `DynamicValue.Record` (the codec-spine intermediate). */
  def encode(value: A): DynamicValue.Record

  /** Decode a typed value from a `DynamicValue.Record` produced by an adapter or supplied by user code. */
  def decode(raw: DynamicValue.Record): Either[DspyError, A]

  /** Render this shape as a JSON Schema string suitable for prompt instructions to LMs that follow
    * structured-output hints. Returns `None` for shapes that don't have a backing `zio.blocks.schema.Schema`
    * (e.g. `MapShape` from `Signature.fromString`); adapters that use this fall back to their default
    * natural-language instruction in that case. */
  def jsonSchemaString: Option[String] = None

object Shape:

  /** A `Shape[DynamicValue.Record]` whose `fieldSpecs` are provided at construction. Used by the trait-spec macro
    * and any other surface that produces field metadata without an accompanying case class.
    *
    * When `decoders` is non-empty, every declared field is run through its `FieldCodec` during `decode` (so a raw
    * adapter value like the string `"joy"` becomes the typed enum value `Sentiment.joy`, then re-encoded back to a
    * DynamicValue for the spine), and inputs are similarly encoded through the same decoders on the way out.
    *
    * Fields not present in `decoders` pass through unchanged. `decode` always validates that every field listed in
    * `fieldSpecs` is present in the raw record. */
  final class MapShape(
      override val fieldSpecs: Vector[FieldSpec],
      decoders: Map[String, FieldCodec[Any]] = Map.empty
  ) extends Shape[DynamicValue.Record]:

    def encode(value: DynamicValue.Record): DynamicValue.Record =
      if decoders.isEmpty then value
      else
        val out = value.fields.iterator.map { (k, v) =>
          decoders.get(k) match
            case Some(dec) =>
              // Round-trip via the decoder: lift DynamicValue → Any → encoded → DynamicValue.
              val asAny  = DynamicValues.toAny(v)
              val encVal = dec.encode(asAny)
              k -> DynamicValues.fromAny(encVal)
            case None => k -> v
        }.toSeq
        DynamicValue.Record(Chunk.from(out))

    def decode(raw: DynamicValue.Record): Either[DspyError, DynamicValue.Record] =
      val present = DynamicValues.recordKeys(raw).toSet
      val missing = fieldSpecs.iterator.map(_.name).filterNot(present.contains).toList
      if missing.nonEmpty then
        Left(NotFoundError(
          resource = "prediction_field",
          message  = s"Missing required fields: ${missing.mkString(", ")}"
        ))
      else if decoders.isEmpty then
        Right(raw)
      else
        val builder = Vector.newBuilder[(String, DynamicValue)]
        val it = fieldSpecs.iterator
        while it.hasNext do
          val fs = it.next()
          val rawValue = DynamicValues.recordGet(raw, fs.name).get
          decoders.get(fs.name) match
            case Some(dec) =>
              dec.decode(DynamicValues.toAny(rawValue)) match
                case Right(decoded) =>
                  builder += (fs.name -> DynamicValues.fromAny(decoded))
                case Left(err) =>
                  return Left(ValidationError(s"Field '${fs.name}': ${err.message}"))
            case None =>
              builder += (fs.name -> rawValue)
        Right(DynamicValue.Record(Chunk.from(builder.result())))

  /** A tuple-backed shape used by the trait-spec macro. The macro gives callers a named-tuple type, e.g.
    * `(sentence: String)` for inputs and `(sentiment: Emotion)` for outputs. At runtime named tuples erase to
    * ordinary tuples, so this shape pairs values with `fieldSpecs` by declaration order. */
  final class TupleShape[A](
      override val fieldSpecs: Vector[FieldSpec],
      decoders: Map[String, FieldCodec[Any]]
  ) extends Shape[A]:

    def encode(value: A): DynamicValue.Record =
      val values = value.asInstanceOf[Product].productIterator.toVector
      val entries = fieldSpecs.zip(values).map { (fs, raw) =>
        val encoded = decoders.get(fs.name).fold(raw)(_.encode(raw.asInstanceOf[Any]))
        fs.name -> DynamicValues.fromAny(encoded)
      }
      DynamicValue.Record(Chunk.from(entries))

    def decode(raw: DynamicValue.Record): Either[DspyError, A] =
      val present = DynamicValues.recordKeys(raw).toSet
      val missing = fieldSpecs.iterator.map(_.name).filterNot(present.contains).toList
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
          val rawValue = DynamicValues.recordGet(raw, fs.name).get
          decoders.get(fs.name) match
            case Some(dec) =>
              dec.decode(DynamicValues.toAny(rawValue)) match
                case Right(decoded) => builder += decoded
                case Left(err)      =>
                  return Left(ValidationError(s"Field '${fs.name}': ${err.message}"))
            case None =>
              builder += DynamicValues.toAny(rawValue)
        Right(Tuple.fromArray(builder.result()).asInstanceOf[A])

  /** Derives a `Shape[A]` from any case class / product type with a `zio.blocks.schema.Schema[A]` in scope.
    * zio-blocks owns product encode/decode; dspy4s derives the DSPy-facing field metadata from the same
    * structural Reflect description (see [[ZioSchemaCodec]] for the metadata story). */
  inline def derived[A <: Product](using schema: Schema[A]): Shape[A] =
    ZioSchemaCodec.derivedFromZioSchema[A](FieldRole.Output)

  /** Derives a `Shape[A]` and stamps every field with the given role. Use `FieldRole.Input` for input case
    * classes, `FieldRole.Output` for output case classes. `derived` defaults to `Output`; `Signature` builders
    * that need inputs invoke this explicitly. */
  inline def derivedWithRole[A <: Product](role: FieldRole)(using schema: Schema[A]): Shape[A] =
    ZioSchemaCodec.derivedFromZioSchema[A](role)

  private[typed] inline def derivedProductWithRole[A](role: FieldRole)(using schema: Schema[A]): Shape[A] =
    ZioSchemaCodec.derivedFromZioSchema[A](role)

