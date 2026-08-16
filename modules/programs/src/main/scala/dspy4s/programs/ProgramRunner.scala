package dspy4s.programs

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.core.data.RawPrediction
import dspy4s.programs.contracts.{DynamicModule, Module, ProgramCall}
import dspy4s.signatures.Shape
import zio.blocks.schema.DynamicValue

/** Capability for evaluating a program from the dynamic record boundary.
  *
  * Optimizers, evaluation, and streaming all consume this same boundary: a record-valued [[ProgramCall]] goes in and
  * the program's [[RawPrediction]] comes out. The capability is external to `Module` because record decoding is not
  * available on every module representation; third-party programs can supply their own instance.
  *
  * For BARE modules the program's own signature is the canonical decode boundary (no identity morphism is in sight, so
  * no coherence question arises); the framework leaves and composites carry signature-backed instances below. For a
  * packaged `Program`, decoding is likewise resolved canonically from [[RecordCodec]] at this boundary rather than
  * stored in the morphism or required by the program algebra.
  */
trait LegacyProgramRunner[P]:
  def run(program: P, call: ProgramCall[DynamicValue.Record])(using
      RuntimeContext
  ): Either[DspyError, RawPrediction]

  final def run(program: P, inputs: DynamicValue.Record)(using RuntimeContext): Either[DspyError, RawPrediction] =
    run(program, ProgramCall(inputs))

private[programs] trait LowPriorityLegacyProgramRunner:
  /** Any module whose input type carries a [[RecordCodec]] (user composites without a hand-written runner). Lower
    * priority than the signature-backed instances in the companion.
    */
  given fromRecordCodec[I, O, P <: Module[I, O]](using
      codec: RecordCodec[I]
  ): LegacyProgramRunner[P] with
    def run(program: P, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      codec.decode(call.input).flatMap { decoded =>
        program(call.mapInput(_ => decoded)).map(_.raw)
      }

object LegacyProgramRunner extends LowPriorityLegacyProgramRunner:

  /** Dynamic modules already inhabit the record boundary. */
  given fromDynamicModule[P <: DynamicModule]: LegacyProgramRunner[P] with
    def run(program: P, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      program(call).map(_.raw)

  private def signatureBacked[I, O, P <: Module[I, O]](
      inputShapeOf: P => Shape[I]
  ): LegacyProgramRunner[P] =
    new LegacyProgramRunner[P]:
      def run(program: P, call: ProgramCall[DynamicValue.Record])(using
          RuntimeContext
      ): Either[DspyError, RawPrediction] =
        inputShapeOf(program).decode(call.input).flatMap { decoded =>
          program(call.mapInput(_ => decoded)).map(_.raw)
        }

  // ── The framework leaves and composites, decoded through their own (base) signature ──────────────────────
  given fromPredict[I, O]: LegacyProgramRunner[Predict[I, O]] =
    signatureBacked[I, O, Predict[I, O]](_.signature.inputShape)

  given fromChainOfThought[I, O]: LegacyProgramRunner[ChainOfThought[I, O]] =
    signatureBacked[I, ChainOfThought.WithReasoning[O], ChainOfThought[I, O]](_.baseSignature.inputShape)

  given fromReAct[I, O]: LegacyProgramRunner[ReAct[I, O]] =
    signatureBacked[I, ReAct.WithReasoning[O], ReAct[I, O]](_.baseSignature.inputShape)

  given fromCodeAct[I, O]: LegacyProgramRunner[CodeAct[I, O]] =
    signatureBacked[I, CodeAct.WithReasoning[O], CodeAct[I, O]](_.baseSignature.inputShape)

  given fromProgramOfThought[I, O]: LegacyProgramRunner[ProgramOfThought[I, O]] =
    signatureBacked[I, ProgramOfThought.WithReasoning[O], ProgramOfThought[I, O]](_.baseSignature.inputShape)

  given fromRLM[I, O]: LegacyProgramRunner[RLM[I, O]] =
    signatureBacked[I, O, RLM[I, O]](_.baseSignature.inputShape)

  def apply[P](using runner: LegacyProgramRunner[P]): LegacyProgramRunner[P] = runner
