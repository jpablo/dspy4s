package dspy4s.signatures

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, ValidationError, updated}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Input augmentation, the input side of [[OutputAugmentation]]. The loop signatures of the composite programs use it
  * to add one `String` field (`trajectory`, and others) to a base input `I`.
  *
  * The augmented value is the pair `(I, String)`. The base [[Shape]] encodes the base fields with its schema, and the
  * augmentation adds the extra field at the end. For two extra fields, apply it two times (`((I, String), String)`).
  */
object InputAugmentation:

  /** `Shape[(I, String)]`: the fields that `base` encodes, and then `field` (the position of `SignatureOps.appendInput`
    * in the layout). The decode reads the extra field as a `String`, and `base` decodes the other fields.
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

  /** An input block with a number of extra fields that is known only at runtime. Its value type [[Values]] depends on
    * this shape. Only [[validate]] gives a [[Values]], thus `shape.encode` always gets one value for each field.
    */
  final class AppendedStringInputs[I] private[InputAugmentation] (
      base  : Shape[I],
      fields: Vector[FieldSpec],
      label : String
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
        val extra = fields.lazyZip(value._2).map { (field, text) =>
          field.name -> DynamicValue.Primitive(PrimitiveValue.String(text))
        }
        DynamicValues.mergeRecords(base.encode(value._1), DynamicValue.Record(Chunk.from(extra)))

      def decode(raw: DynamicValue.Record): Either[DspyError, (I, Values)] =
        for
          i  <- base.decode(raw)
          ss <- Traverse.either(fields)(field => DynamicValues.requireString(raw, field.name, label))
        yield (i, ss)

  /** Make an input block with a number of extra fields that is known only at runtime. */
  def appendedStringInputs[I](
      base  : Shape[I],
      fields: Vector[FieldSpec],
      label : String
  ): AppendedStringInputs[I] =
    new AppendedStringInputs(base, fields, label)
