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

  /** Internal construction gate for legitimate non-derived objects: fresh runtime-signature and custom-schema bundles.
    * Keeping the carrier sealed prevents application code from shadowing a type's canonical derived decoder with an
    * unrelated instance.
    */
  private[programs] def fromShape[A](shape: Shape[A]): RecordCodec[A] = ShapeBacked(shape)

  /** Decode a case class, a tuple, or a named tuple with `Shape.canonicalDerived`, the closed structural shape that the
    * signatures use for their inputs. Thus this decode is the decode of each typed signature with the same input type.
    * An ambient schema cannot change it. For a custom schema, use a new branded object type.
    */
  inline given fromProduct[A](using
      scala.deriving.Mirror.ProductOf[A],
      scala.util.NotGiven[A =:= DynamicValue.Record]
  ): RecordCodec[A] =
    fromShape(Shape.canonicalDerived[A])
