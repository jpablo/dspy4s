package dspy4s.programs

import dspy4s.core.contracts.{DspyError, FieldRole}
import dspy4s.typed.Shape
import zio.blocks.schema.{DynamicValue, Schema}

/** Evidence that a typed program input can be decoded from the dynamic record boundary.
  *
  * This is independent of any particular program representation. [[ProgramInput]] uses it as the generic fallback,
  * while the Para category uses it as its object constraint for identity morphisms.
  */
trait RecordCodec[A]:
  def decode(record: DynamicValue.Record): Either[DspyError, A]

object RecordCodec:
  private final class ShapeBacked[A](shape: Shape[A]) extends RecordCodec[A]:
    def decode(record: DynamicValue.Record): Either[DspyError, A] = shape.decode(record)

  /** Decode products through the same input-role `Shape` path used by typed signatures. */
  given fromSchema[A <: Product](using Schema[A]): RecordCodec[A] =
    ShapeBacked(Shape.derivedWithRole[A](FieldRole.Input))
