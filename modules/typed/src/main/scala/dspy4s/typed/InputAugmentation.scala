package dspy4s.typed

import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, ValidationError, updated}
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Typed INPUT augmentation: the input-side dual of [[OutputAugmentation]], for the composite programs' loop signatures
  * that extend a base input `I` with one hand-declared `String` field (`trajectory`, `previous_code`, `error`,
  * `final_generated_code`, ...).
  *
  * The augmented carrier is the plain pair `(I, String)` rather than a named-tuple surgery on `I`: the pair delegates
  * the base encoding to the base [[Shape]] UNCHANGED (schema-aware — no re-derivation, no `fromAny` round-trip for
  * structured base fields) and appends the extra field, which is exactly what the dynamic path did with
  * `record.updated(name, value)`. Apply twice for two appended fields (`((I, String), String)`).
  */
object InputAugmentation:

  /** `Shape[(I, String)]`: the base fields encoded by `base`, plus `field` appended LAST (matching
    * `SignatureOps.appendInput`'s layout position). Decode requires the appended field as a `String` and delegates the
    * rest to `base`.
    */
  def appendedStringInput[I](base: Shape[I], field: FieldSpec, label: String): Shape[(I, String)] =
    new Shape[(I, String)]:
      val fieldSpecs: Vector[FieldSpec] = base.fieldSpecs :+ field

      def encode(value: (I, String)): DynamicValue.Record =
        base.encode(value._1).updated(field.name, DynamicValue.Primitive(PrimitiveValue.String(value._2)))

      def decode(raw: DynamicValue.Record): Either[DspyError, (I, String)] =
        for
          i <- base.decode(raw)
          s <- DynamicValues.requireString(raw, field.name, label)
        yield (i, s)

  /** A runtime-sized input block whose value carrier is path-dependent on this validated shape bundle. The opaque
    * [[Values]] type can only be obtained through [[validate]], so `shape.encode` cannot receive a short or oversized
    * vector and its former truncating `zip` is unnecessary.
    */
  final class AppendedStringInputs[I] private[InputAugmentation] (
      base: Shape[I],
      fields: Vector[FieldSpec],
      label: String
  ):
    opaque type Values = Vector[String]

    def validate(values: Vector[String]): Either[DspyError, Values] =
      if values.size == fields.size then Right(values)
      else
        Left(ValidationError(
          s"$label requires exactly ${fields.size} appended values, got ${values.size}"
        ))

    val shape: Shape[(I, Values)] = new Shape[(I, Values)]:
      val fieldSpecs: Vector[FieldSpec] = base.fieldSpecs ++ fields

      def encode(value: (I, Values)): DynamicValue.Record =
        fields.indices.foldLeft(base.encode(value._1)) { (acc, index) =>
          acc.updated(
            fields(index).name,
            DynamicValue.Primitive(PrimitiveValue.String(value._2(index)))
          )
        }

      def decode(raw: DynamicValue.Record): Either[DspyError, (I, Values)] =
        val values = fields.foldLeft[Either[DspyError, Vector[String]]](Right(Vector.empty)) { (acc, field) =>
          for
            collected <- acc
            s         <- DynamicValues.requireString(raw, field.name, label)
          yield collected :+ s
        }
        for
          i  <- base.decode(raw)
          ss <- values
        yield (i, ss)

  /** Build a path-branded, validated runtime-arity input block. */
  def appendedStringInputs[I](
      base: Shape[I],
      fields: Vector[FieldSpec],
      label: String
  ): AppendedStringInputs[I] =
    new AppendedStringInputs(base, fields, label)
