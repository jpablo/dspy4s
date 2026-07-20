package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.typed.Signature
import zio.blocks.schema.DynamicValue

/** Strategy for decoding the dynamic record boundary into the typed input expected by a program.
  *
  * Program-specific instances use the program's authoritative signature. The low-priority fallback works for any
  * representation whose input has a [[RecordCodec]]. This is a capability typeclass rather than an inherited member:
  * third-party modules can provide coherent decoding without extending a framework base class.
  */
trait ProgramInput[F, I]:
  def decoder(program: F): DynamicValue.Record => Either[DspyError, I]

private[programs] trait LowPriorityProgramInput:
  given fromRecordCodec[F, I](using codec: RecordCodec[I]): ProgramInput[F, I] with
    def decoder(program: F): DynamicValue.Record => Either[DspyError, I] = codec.decode

object ProgramInput extends LowPriorityProgramInput:
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

  given forProgramOfThought[I, O]: ProgramInput[ProgramOfThought[I, O], I] =
    signatureBacked(_.baseSignature)

  given forRLM[I, O]: ProgramInput[RLM[I, O], I] =
    signatureBacked(_.baseSignature)
