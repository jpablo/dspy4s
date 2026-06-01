package dspy4s.typed

import dspy4s.core.contracts.{
  DspyError, DynamicValues, FieldRole, FieldSpec, NotFoundError
}
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

  /** A `Shape[DynamicValue.Record]` for the dynamic path (`Signature.fromString`), where the DSL carries no
    * static schema so the "typed" value stays at the spine type. `encode` is the identity; `decode` only
    * validates that every field listed in `fieldSpecs` is present in the raw record (no per-field coercion --
    * that happens upstream in the adapter / `ZioSchemaCodec.normalize` for schema-backed shapes). */
  final class MapShape(
      override val fieldSpecs: Vector[FieldSpec]
  ) extends Shape[DynamicValue.Record]:

    def encode(value: DynamicValue.Record): DynamicValue.Record = value

    def decode(raw: DynamicValue.Record): Either[DspyError, DynamicValue.Record] =
      val present = DynamicValues.recordKeys(raw).toSet
      val missing = fieldSpecs.iterator.map(_.name).filterNot(present.contains).toList
      if missing.nonEmpty then
        Left(NotFoundError(
          resource = "prediction_field",
          message  = s"Missing required fields: ${missing.mkString(", ")}"
        ))
      else Right(raw)

  /** A `Shape` for a (named-)tuple type `A`, fully backed by a zio-blocks `Schema[A]` derived for that tuple.
    * Used by the `Signature.of[Spec]` / `from` / `fromType` macros, which hand callers a named-tuple type,
    * e.g. `(sentence: String)` for inputs and `(sentiment: Emotion)` for outputs. zio-blocks bridges
    * named-tuple <-> tuple internally (`NamedTuple.toTuple` + register construction), so there is no
    * reflective `productIterator` / `Tuple.fromArray` cast. `fieldSpecs`
    * (names, wire `typeRef`s) are derived from the schema's `Reflect`, with every field stamped `role`; the
    * decode path reuses [[ZioSchemaCodec]]'s LM-string coercion, the same path the case-class shapes use. */
  final class SchemaTupleShape[A](
      role: FieldRole,
      schema: Schema[A]
  ) extends Shape[A]:
    private val delegate: Shape[A] = ZioSchemaCodec.derivedFromZioSchema[A](role)(using schema)
    val fieldSpecs: Vector[FieldSpec]                         = delegate.fieldSpecs
    def encode(value: A): DynamicValue.Record                 = delegate.encode(value)
    def decode(raw: DynamicValue.Record): Either[DspyError, A] = delegate.decode(raw)
    override def jsonSchemaString: Option[String]             = delegate.jsonSchemaString

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

