package dspy4s.programs

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.core.data.DynamicPrediction
import dspy4s.programs.contracts.{DynamicModule, Module, ProgramCall}
import dspy4s.typed.{Prediction, Shape}
import zio.blocks.schema.DynamicValue

/** Capability for evaluating a program from the dynamic record boundary.
  *
  * Optimizers, evaluation, and streaming all consume this same boundary: a record-valued [[ProgramCall]] goes in and
  * the program's raw [[DynamicPrediction]] comes out. The capability is external to `Module` because record decoding is
  * not available on every typed module representation; third-party programs can supply their own instance.
  *
  * For BARE typed modules the program's own signature is the canonical decode boundary (no identity morphism is
  * in sight, so no coherence question arises); the framework leaves and composites carry signature-backed
  * instances below. Inside the Para category, decoding is instead a property of the OBJECT ([[RecordCodec]]),
  * which is what makes the category's unit laws unconditional.
  */
trait ProgramRunner[P]:
  def run(program: P, call: ProgramCall[DynamicValue.Record])(using
      RuntimeContext
  ): Either[DspyError, DynamicPrediction]

  final def run(program: P, inputs: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    run(program, ProgramCall(inputs))

private[programs] trait LowPriorityProgramRunner:
  /** Any typed module whose input type carries a [[RecordCodec]] (user composites without a hand-written
    * runner). Lower priority than the signature-backed instances in the companion. */
  given fromRecordCodec[I, O, P <: Module[ProgramCall[I], Prediction[O]]](using
      codec: RecordCodec[I]
  ): ProgramRunner[P] with
    def run(program: P, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, DynamicPrediction] =
      codec.decode(call.input).flatMap { decoded =>
        program.apply(call.mapInput(_ => decoded)).map(_.raw)
      }

object ProgramRunner extends LowPriorityProgramRunner:

  /** Dynamic modules already inhabit the record boundary. */
  given fromDynamicModule[P <: DynamicModule]: ProgramRunner[P] with
    def run(program: P, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, DynamicPrediction] =
      program.apply(call)

  private def signatureBacked[I, O, P <: Module[ProgramCall[I], Prediction[O]]](
      inputShapeOf: P => Shape[I]
  ): ProgramRunner[P] = new ProgramRunner[P]:
    def run(program: P, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, DynamicPrediction] =
      inputShapeOf(program).decode(call.input).flatMap { decoded =>
        program.apply(call.mapInput(_ => decoded)).map(_.raw)
      }

  // ── The framework leaves and composites, decoded through their own (base) signature ──────────────────────
  given fromPredict[I, O]: ProgramRunner[Predict[I, O]] =
    signatureBacked[I, O, Predict[I, O]](_.signature.inputShape)

  given fromChainOfThought[I, O]: ProgramRunner[ChainOfThought[I, O]] =
    signatureBacked[I, ChainOfThought.WithReasoning[O], ChainOfThought[I, O]](_.signature.inputShape)

  given fromReAct[I, O]: ProgramRunner[ReAct[I, O]] =
    signatureBacked[I, ReAct.WithReasoning[O], ReAct[I, O]](_.baseSignature.inputShape)

  given fromCodeAct[I, O]: ProgramRunner[CodeAct[I, O]] =
    signatureBacked[I, CodeAct.WithReasoning[O], CodeAct[I, O]](_.baseSignature.inputShape)

  given fromProgramOfThought[I, O]: ProgramRunner[ProgramOfThought[I, O]] =
    signatureBacked[I, ProgramOfThought.WithReasoning[O], ProgramOfThought[I, O]](_.baseSignature.inputShape)

  given fromRLM[I, O]: ProgramRunner[RLM[I, O]] =
    signatureBacked[I, O, RLM[I, O]](_.baseSignature.inputShape)

  def apply[P](using runner: ProgramRunner[P]): ProgramRunner[P] = runner
