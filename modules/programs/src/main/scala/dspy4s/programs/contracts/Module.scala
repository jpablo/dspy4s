package dspy4s.programs.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment
import zio.blocks.schema.DynamicValue

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** The base type for every dspy4s program — a port of Python DSPy's `dspy.Module`. It is generic in the call
  * input `I` and result `O` so the *same* base serves both layers of dspy4s:
  *
  *   - the untyped spine, `Module[ProgramCall, DynamicPrediction]` (see [[DynamicModule]]), which every engine
  *     program (`DynamicPredict`, `ReAct`, `CodeAct`, ...) extends; and
  *   - the typed surface, `Module[TypedCall[I], Prediction[O]]`, which `Predict[I, O]` / `ChainOfThought[I, O]`
  *     extend — matching Python, where `Predict` / `ChainOfThought` / `ReAct` are all `Module`s.
  *
  * A program is a pure [[forward]]; [[apply]] is the `final` caller entry (Scala's `__call__`) that wraps
  * `forward` with the module lifecycle — the callback `ModuleStart`/`ModuleEnd` scope plus trace/history recording.
  * That bookkeeping is the runtime's responsibility (`RuntimeEnvironment` / `CallbackDispatcher`), not the
  * program's; subclasses implement only `forward`. Because `apply` is `final`, the wrapping is universal and cannot
  * be bypassed — every `Module`, typed or untyped, is observed identically (the typed layer is no longer an
  * un-wrapped facade beside the spine).
  *
  * Callbacks, trace, and history all record `DynamicValue.Record`s, not the static `I` / `O`. The three
  * projection hooks bridge the generic `I` / `O` into those records:
  *
  *   - [[callInputs]] — the input bag for the callback scope and the trace/history `inputs`;
  *   - [[callTraceEnabled]] — whether this call records a trace/history entry;
  *   - [[tracePayload]] — the output bag recorded as the trace/history `outputs` on success.
  *
  * [[moduleName]] is the public identity (snake_case: `"predict"`, `"chain_of_thought"`, `"react"`), used by
  * callbacks, trace entries, and stream-listener routing. [[applyAsync]] is the async entry; it propagates the
  * callback / trace / `ActivePredictContext` thread-locals across the thread boundary via
  * [[dspy4s.core.runtime.ContextPropagation.future]]. */
trait Module[I, O]:
  def moduleName: String

  /** The program's actual computation, minus the module lifecycle. Subclasses implement this; callers invoke
    * [[apply]] (or [[applyAsync]]), never `forward`. */
  protected def forward(input: I)(using RuntimeContext): Either[DspyError, O]

  /** The input record recorded for the callback scope and the trace/history `inputs`. */
  protected def callInputs(input: I): DynamicValue.Record

  /** Whether this call records a trace/history entry. */
  protected def callTraceEnabled(input: I): Boolean

  /** What gets recorded as the trace/history `outputs` for a successful call. */
  protected def tracePayload(output: O): DynamicValue.Record

  final def apply(input: I)(using RuntimeContext): Either[DspyError, O] =
    val inputBag = callInputs(input)
    CallbackDispatcher.withModule(moduleName, inputBag) {
      val result = forward(input)
      if callTraceEnabled(input) then
        result match
          case Right(output) =>
            val outputs = tracePayload(output)
            RuntimeEnvironment.appendTrace(
              TraceEntry(component = moduleName, inputs = inputBag, outputs = outputs)
            )
            RuntimeEnvironment.appendHistory(
              HistoryEntry(component = moduleName, payload = DynamicValues.record("inputs" -> inputBag, "outputs" -> outputs))
            )
          case Left(error) =>
            // P-a (G-12): normally a failure leaves no trace; under `captureFailureTraces` (GEPA's reflective
            // evaluation) record a failure entry so the failed trajectory is visible — surfacing the raw model
            // response from a parse error so reflection can see what the model actually produced.
            if summon[RuntimeContext].captureFailureTraces then
              val rawOutputs = error match
                case ParseError(_, _, Some(raw)) => DynamicValues.record("raw_response" := raw)
                case _                           => DynamicValue.Record.empty
              RuntimeEnvironment.appendTrace(
                TraceEntry(component = moduleName, inputs = inputBag, outputs = rawOutputs, failure = Some(error.message))
              )
      result
    }

  def applyAsync(input: I)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, O]] =
    ContextPropagation.future(apply(input))

/** The untyped program spine: `Module[ProgramCall, DynamicPrediction]` with the projection hooks defaulted to the
  * spine record shapes (`call.inputs` / `prediction.values`). `DynamicPredict` is the engine program on this
  * spine (it's the runtime substrate the typed programs build their inner predicts from); user-defined data-bag
  * programs may extend it too. Subclasses implement only `forward` + `moduleName`; `tracePayload` stays
  * overridable for programs that record a projection. (The typed programs — `Predict` / `ChainOfThought` /
  * `ReAct` / `CodeAct` / `ProgramOfThought` / `MultiChainComparison` / `BestOfN` / `Refine` — instead extend
  * `Module[TypedCall[I], Prediction[…]]`.) */
trait DynamicModule extends Module[ProgramCall, DynamicPrediction]:
  protected def callInputs(call: ProgramCall): DynamicValue.Record = call.inputs
  protected def callTraceEnabled(call: ProgramCall): Boolean      = call.traceEnabled
  protected def tracePayload(prediction: DynamicPrediction): DynamicValue.Record = prediction.values
