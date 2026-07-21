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

  /** `Shape[(I, Vector[String])]`: the base fields plus a RUNTIME-ARITY block of appended `String` fields — the
    * bridge for layouts whose field COUNT is a constructor parameter (`MultiChainComparison`'s `m` numbered
    * `reasoning_attempt_i` inputs). The static type carries the list; the field expansion is value-level:
    * `fields` (built where the arity is known, at program construction) fixes both the specs and the encoding
    * positions, so the wire format — numbered flat fields — is unchanged. The arity invariant
    * (`values.size == fields.size`) is a runtime concern the CALLER enforces before the predict runs (the
    * program's own `m`-validation), which is the honest cost of a runtime-arity signature: the list's length,
    * unlike its type, cannot be pinned at compile time. Encode zips (extra values beyond `fields` are dropped,
    * missing ones simply absent); decode requires every declared field. */
  def appendedStringInputs[I](base: Shape[I], fields: Vector[FieldSpec], label: String): Shape[(I, Vector[String])] =
    new Shape[(I, Vector[String])]:
      val fieldSpecs: Vector[FieldSpec] = base.fieldSpecs ++ fields

      def encode(value: (I, Vector[String])): DynamicValue.Record =
        fields.iterator.zip(value._2).foldLeft(base.encode(value._1)) { case (acc, (field, s)) =>
          acc.updated(field.name, DynamicValue.Primitive(PrimitiveValue.String(s)))
        }

      def decode(raw: DynamicValue.Record): Either[DspyError, (I, Vector[String])] =
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
