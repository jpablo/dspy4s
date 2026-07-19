package dspy4s.programs.para

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldRole
import dspy4s.programs.Predict
import dspy4s.typed.Shape
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema

/** Evidence that a category object can be decoded from a data-bag record.
  *
  * [[Program]] uses this capability to synthesize the decoder for identity morphisms and, through [[ProgramInput]], to
  * package typed programs whose input is codec-equipped.
  */
trait RecordCodec[A]:
  def decode(record: DynamicValue.Record): Either[DspyError, A]

object RecordCodec:
  /** Named carrier so the given below does not mint an anonymous class per summon site. */
  private final class ShapeBacked[A](shape: Shape[A]) extends RecordCodec[A]:
    def decode(record: DynamicValue.Record): Either[DspyError, A] = shape.decode(record)

  /** Decode products through the same input-role `Shape` path used by typed signatures. */
  given fromSchema[A <: Product](using Schema[A]): RecordCodec[A] =
    ShapeBacked(Shape.derivedWithRole[A](FieldRole.Input))

/** A strategy for obtaining a record-to-`I` decoder from a program at packaging time.
  *
  * Program-specific instances may use their signature. The low-priority fallback works for any typed program whose
  * input type has a [[RecordCodec]]. Composition needs no instance because it threads the first leg's packaged decoder.
  */
trait ProgramInput[F, I]:
  def decoder(program: F): DynamicValue.Record => Either[DspyError, I]

trait LowPriorityProgramInput:
  given fromRecordCodec[F, I](using codec: RecordCodec[I]): ProgramInput[F, I] with
    def decoder(program: F): DynamicValue.Record => Either[DspyError, I] = codec.decode

object ProgramInput extends LowPriorityProgramInput:
  given forPredict[I, O]: ProgramInput[Predict[I, O], I] with
    def decoder(program: Predict[I, O]): DynamicValue.Record => Either[DspyError, I] =
      program.signature.inputShape.decode
