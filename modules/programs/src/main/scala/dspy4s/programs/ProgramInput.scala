package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.typed.Signature
import zio.blocks.schema.DynamicValue

/** Strategy for decoding the dynamic record boundary into the typed input expected by a program.
  *
  * Program-specific instances use the program's authoritative signature. The low-priority fallback works for any
  * representation whose input has a [[RecordCodec]]. This is a capability typeclass rather than an inherited member:
  * third-party modules can provide coherent decoding without extending a framework base class.
  *
  * ==Coherence law==
  * An instance is lawful when `decoder(p)` agrees with `p`'s own typed input boundary: a record it accepts decodes
  * to the same `I` that `p`'s signature `inputShape` (or, for plain codec-equipped inputs, the type's
  * [[RecordCodec]]) would produce. This is an instance obligation in the usual typeclass sense (like `Monoid`
  * associativity): it cannot be checked mechanically, because decoder agreement is function equality. The Para
  * category over packaged programs is lawful GIVEN lawful instances; `ParaCategoryLawSuite` pins the left-unit
  * counterexample an unlawful instance produces. Supplying a custom instance (e.g. adapting dataset records whose
  * keys differ from the signature's field names) is legitimate exactly when this law is honored for the records
  * that program will be evaluated on.
  */
trait ProgramInput[F, I]:
  def decoder(program: F): DynamicValue.Record => Either[DspyError, I]

private[programs] trait LowPriorityProgramInput:
  given fromRecordCodec[F, I](using codec: RecordCodec[I]): ProgramInput[F, I] with
    def decoder(program: F): DynamicValue.Record => Either[DspyError, I] = codec.decode

object ProgramInput extends LowPriorityProgramInput:
  private def signatureBacked[F, I, O](signature: F => Signature[I, O]): ProgramInput[F, I] =
    (program: F) => signature(program).inputShape.decode

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
