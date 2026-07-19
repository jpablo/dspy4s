package dspy4s.programs.para

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldRole
import dspy4s.programs.ChainOfThought
import dspy4s.programs.CodeAct
import dspy4s.programs.Predict
import dspy4s.programs.ReAct
import dspy4s.typed.Shape
import dspy4s.typed.Signature
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
  *
  * Instances are expected to be coherent with the program's actual typed-call boundary: decoding a record and running
  * the program must mean the same thing as supplying the decoded `I` directly. This is a capability typeclass rather
  * than an inherited program member because third-party module types can provide the capability independently.
  */
trait ProgramInput[F, I]:
  def decoder(program: F): DynamicValue.Record => Either[DspyError, I]

trait LowPriorityProgramInput:
  given fromRecordCodec[F, I](using codec: RecordCodec[I]): ProgramInput[F, I] with
    def decoder(program: F): DynamicValue.Record => Either[DspyError, I] = codec.decode

object ProgramInput extends LowPriorityProgramInput:
  /** Build input-decoding evidence for a module that exposes its authoritative typed signature. */
  private def signatureBacked[F, I, O](signature: F => Signature[I, O]): ProgramInput[F, I] =
    new ProgramInput[F, I]:
      def decoder(program: F): DynamicValue.Record => Either[DspyError, I] =
        signature(program).inputShape.decode

  given forPredict[I, O]: ProgramInput[Predict[I, O], I] =
    signatureBacked(_.signature)

  given forChainOfThought[I, O]: ProgramInput[ChainOfThought[I, O], I] =
    signatureBacked(_.signature)

  given forReAct[I, O]: ProgramInput[ReAct[I, O], I] =
    signatureBacked(_.baseSignature)

  given forCodeAct[I, O]: ProgramInput[CodeAct[I, O], I] =
    signatureBacked(_.baseSignature)
