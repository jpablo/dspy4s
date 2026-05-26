package dspy4s.typed

import dspy4s.core.contracts.{
  DspyError, FieldRole, FieldSpec, NotFoundError, ValidationError
}
import zio.blocks.schema.Schema
import scala.deriving.Mirror

/** A schema-aware view of a user type `A`, used as the input or output of a
  * `Signature`. Lists fields in declaration order, converts each to a
  * `FieldSpec` for the untyped `SignatureLayout`, encodes typed values into the
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

  /** Render this shape as a JSON Schema string suitable for prompt instructions to LMs that follow
    * structured-output hints. Returns `None` for shapes that don't have a backing `zio.blocks.schema.Schema`
    * (e.g. `MapShape` from `Signature.fromString`); adapters that use this fall back to their default
    * natural-language instruction in that case. */
  def jsonSchemaString: Option[String] = None

object Shape:

  /** A `Shape[Map[String, Any]]` whose `fieldSpecs` are provided at
    * construction. Used by the trait-spec macro and any other surface
    * that produces field metadata without an accompanying case class.
    *
    * When `decoders` is non-empty, every declared field is run through
    * its `FieldCodec` during `decode` (so a raw adapter value like the
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
      decoders: Map[String, FieldCodec[Any]] = Map.empty
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
      decoders: Map[String, FieldCodec[Any]]
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

  /** Derives a `Shape[A]` from any case class / product type with a `zio.blocks.schema.Schema[A]` in scope.
    * zio-blocks owns product encode/decode; dspy4s derives the DSPy-facing field metadata from the same
    * structural Reflect description (see [[ZioSchemaCodec]] for the converter + metadata story). */
  inline def derived[A <: Product](using schema: Schema[A]): Shape[A] =
    ZioSchemaCodec.derivedFromZioSchema[A](FieldRole.Output)

  /** Derives a `Shape[A]` and stamps every field with the given role. Use `FieldRole.Input` for input case
    * classes, `FieldRole.Output` for output case classes. `derived` defaults to `Output`; `Signature` builders
    * that need inputs invoke this explicitly. */
  inline def derivedWithRole[A <: Product](role: FieldRole)(using schema: Schema[A]): Shape[A] =
    ZioSchemaCodec.derivedFromZioSchema[A](role)

  private[typed] inline def derivedProductWithRole[A](role: FieldRole)(using schema: Schema[A]): Shape[A] =
    ZioSchemaCodec.derivedFromZioSchema[A](role)
