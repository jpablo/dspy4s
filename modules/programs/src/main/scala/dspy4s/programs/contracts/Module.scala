package dspy4s.programs.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry
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
          case Left(_) => ()
      result
    }

  def applyAsync(input: I)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, O]] =
    ContextPropagation.future(apply(input))

/** The untyped program spine: `Module[ProgramCall, DynamicPrediction]` with the projection hooks defaulted to the
  * spine record shapes (`call.inputs` / `prediction.values`). Every engine program (`DynamicPredict`, `ReAct`,
  * `CodeAct`, `ProgramOfThought`, `DynamicRefine`, `DynamicBestOfN`, `MultiChainComparison`) extends this and
  * implements only `forward` + `moduleName`. `tracePayload` stays overridable for programs that record a
  * projection. (The typed `Refine[I, O]` / `BestOfN[I, O]` instead extend `Module[TypedCall[I], Prediction[O]]`.) */
trait DynamicModule extends Module[ProgramCall, DynamicPrediction]:
  protected def callInputs(call: ProgramCall): DynamicValue.Record = call.inputs
  protected def callTraceEnabled(call: ProgramCall): Boolean      = call.traceEnabled
  protected def tracePayload(prediction: DynamicPrediction): DynamicValue.Record = prediction.values
