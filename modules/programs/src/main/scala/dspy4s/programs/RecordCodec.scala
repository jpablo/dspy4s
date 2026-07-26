package dspy4s.programs

import dspy4s.core.contracts.{DspyError, FieldRole}
import dspy4s.typed.Shape
import zio.blocks.schema.{DynamicValue, Schema}

/** Evidence that a typed program input can be decoded from the dynamic record boundary.
  *
  * This is independent of any particular program representation. The Para category uses it as its OBJECT
  * constraint: identity morphisms and record-boundary evaluation both decode through the object's codec, so
  * two programs at the same object cannot disagree about decoding. The coherence that previously needed a
  * per-package law is definitional (one decoder per type; runtime-string signatures get their own types via
  * [[DynamicSignature]]).
  */
sealed trait RecordCodec[A]:
  def decode(record: DynamicValue.Record): Either[DspyError, A]

object RecordCodec:
  private final class ShapeBacked[A](shape: Shape[A]) extends RecordCodec[A]:
    def decode(record: DynamicValue.Record): Either[DspyError, A] = shape.decode(record)

  /** Internal construction gate for the two legitimate non-derived objects: runtime-signature bundles and focused
    * law-test fixtures. Keeping the carrier sealed prevents application code from shadowing a type's canonical derived
    * decoder with an unrelated instance.
    */
  private[programs] def fromShape[A](shape: Shape[A]): RecordCodec[A] = ShapeBacked(shape)

  private[programs] def fromDecoder[A](
      decoder: DynamicValue.Record => Either[DspyError, A]
  ): RecordCodec[A] = new RecordCodec[A]:
    def decode(record: DynamicValue.Record): Either[DspyError, A] = decoder(record)

  /** Decode products through the same input-role `Shape` path used by typed signatures. */
  given fromSchema[A <: Product](using Schema[A]): RecordCodec[A] =
    fromShape(Shape.derivedWithRole[A](FieldRole.Input))

  /** Decode named tuples through the same `SchemaTupleShape` path the `fromString` / `fromType` / `of[Spec]`
    * macros use for their input shapes, so codec-derived decoding coheres definitionally with those
    * signatures' own decode. */
  inline given fromNamedTupleSchema[A <: scala.NamedTuple.AnyNamedTuple]: RecordCodec[A] =
    fromShape(Shape.SchemaTupleShape[A](FieldRole.Input, Schema.derived[A]))
