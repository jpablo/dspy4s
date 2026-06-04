package dspy4s.streaming

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.DynamicPredict
import dspy4s.programs.ReAct
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.TypedCall
import zio.blocks.schema.DynamicValue

/** What [[Streamify]] needs of a program in order to stream it, captured as a typeclass so that both untyped
  * `DynamicModule`s and typed `Module[TypedCall[I], …]` programs (which don't share a single callable base) can be
  * streamed through the same entry point. `Streamify` requires exactly two things of a program:
  *
  *   1. [[run]] — invoke the program from a record of inputs, yielding the raw `DynamicPrediction` for the final
  *      `PredictionEvent`. Token streaming itself is orthogonal: it's driven by the wrapped
  *      `StreamingLanguageModel` consulting `ActivePredictContext`, which the inner `DynamicPredict`s set as they
  *      run — independent of how the outer program is invoked.
  *   2. [[knownSignatures]] — best-effort `(predictName, signature)` pairs used *only* for stream-listener
  *      validation (warnings). An opaque program returns empty and validation is skipped. */
trait Streamable[P]:
  def run(program: P, inputs: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicPrediction]
  def knownSignatures(program: P): Vector[(String, SignatureLayout)]

object Streamable:

  /** Any untyped program: invoke via a `ProgramCall`; surface a leaf `DynamicPredict`'s signature for validation. */
  given dynamicModule[P <: DynamicModule]: Streamable[P] with
    def run(program: P, inputs: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      program.apply(ProgramCall(inputs = inputs))

    def knownSignatures(program: P): Vector[(String, SignatureLayout)] =
      program match
        case p: DynamicPredict => Vector((p.moduleName, p.layout))
        case _                 => Vector.empty

  /** Typed `ReAct`: decode the record into the typed input, run it, and emit the raw prediction. Its two
    * sub-predicts (the per-step react predict and the final extractor) are the stream-listener targets. */
  given reAct[I, O]: Streamable[ReAct[I, O]] with
    def run(program: ReAct[I, O], inputs: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      program.baseSignature.inputShape.decode(inputs).flatMap(i => program.apply(TypedCall(i)).map(_.raw))

    def knownSignatures(program: ReAct[I, O]): Vector[(String, SignatureLayout)] =
      Vector(
        (program.reactProgramName, program.reactSignature),
        (program.extractorProgramName, program.extractorSignature)
      )
