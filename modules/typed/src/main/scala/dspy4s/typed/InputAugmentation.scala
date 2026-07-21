package dspy4s.typed

import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, updated}
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Typed INPUT augmentation: the input-side dual of [[OutputAugmentation]], for the composite programs' loop
  * signatures that extend a base input `I` with one hand-declared `String` field (`trajectory`, `previous_code`,
  * `error`, `final_generated_code`, ...).
  *
  * The augmented carrier is the plain pair `(I, String)` rather than a named-tuple surgery on `I`: the pair
  * delegates the base encoding to the base [[Shape]] UNCHANGED (schema-aware — no re-derivation, no `fromAny`
  * round-trip for structured base fields) and appends the extra field, which is exactly what the dynamic path did
  * with `record.updated(name, value)`. Apply twice for two appended fields (`((I, String), String)`).
  */
object InputAugmentation:

  /** `Shape[(I, String)]`: the base fields encoded by `base`, plus `field` appended LAST (matching
    * `SignatureOps.appendInput`'s layout position). Decode requires the appended field as a `String` and
    * delegates the rest to `base`. */
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
