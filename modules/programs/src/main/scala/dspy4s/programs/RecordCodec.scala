package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.signatures.Shape
import zio.blocks.schema.DynamicValue

/** Evidence that a program input can be decoded from the dynamic record boundary.
  *
  * This is independent of any particular program representation. [[ProgramRunner]] requires it only when execution
  * begins from a [[DynamicValue.Record]], so two programs with the same input type cannot disagree about decoding. The
  * coherence that previously needed a per-package law is definitional (one decoder per type; runtime-string signatures
  * get their own types via [[DynamicSignature]]).
  */
sealed trait RecordCodec[A]:
  def decode(record: DynamicValue.Record): Either[DspyError, A]

object RecordCodec:
  private final class ShapeBacked[A](shape: Shape[A]) extends RecordCodec[A]:
    def decode(record: DynamicValue.Record): Either[DspyError, A] = shape.decode(record)

  /** Internal construction gate for legitimate non-derived objects: fresh runtime/custom-schema bundles and focused
    * law-test fixtures. Keeping the carrier sealed prevents application code from shadowing a type's canonical derived
    * decoder with an unrelated instance.
    */
  private[programs] def fromShape[A](shape: Shape[A]): RecordCodec[A] = ShapeBacked(shape)

  private[programs] def fromDecoder[A](
      decoder: DynamicValue.Record => Either[DspyError, A]
  ): RecordCodec[A] =
    new RecordCodec[A]:
      def decode(record: DynamicValue.Record): Either[DspyError, A] = decoder(record)

  /** Decode products through the same closed structural input shape used by signatures. Ambient schemas cannot change
    * this instance's behavior; custom schema semantics need a freshly branded object type.
    */
  inline given fromProduct[A <: Product](using
      scala.deriving.Mirror.ProductOf[A],
      scala.util.NotGiven[A =:= DynamicValue.Record]
  ): RecordCodec[A] =
    fromShape(Shape.canonicalDerived[A])

  /** Decode named tuples through the same `SchemaTupleShape` path the `fromString` / `fromType` / `of[Spec]` macros use
    * for their input shapes, so codec-derived decoding coheres definitionally with those signatures' own decode.
    */
  inline given fromNamedTupleSchema[A <: scala.NamedTuple.AnyNamedTuple]: RecordCodec[A] =
    fromShape(Shape.SchemaTupleShape[A](Shape.canonicalSchema[A]))
