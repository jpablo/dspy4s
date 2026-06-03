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

/** The base type for every dspy4s program — a port of Python DSPy's `dspy.Module`, fixed to the program spine
  * `ProgramCall => Either[DspyError, DynamicPrediction]`.
  *
  * A program is a pure [[forward]]; [[apply]] is the `final` caller entry (Scala's `__call__`) that wraps
  * `forward` with the module lifecycle — the callback `ModuleStart`/`ModuleEnd` scope plus trace/history recording.
  * That bookkeeping is the runtime's responsibility (`RuntimeEnvironment` / `CallbackDispatcher`), not the
  * program's; subclasses implement only `forward`. Because `apply` is `final`, the wrapping is universal and cannot
  * be bypassed — every program that extends `Module` is observed identically.
  *
  *   - [[moduleName]] is the public identity (snake_case: `"predict"`, `"chain_of_thought"`, `"react"`), used by
  *     callbacks, trace entries, and stream-listener routing.
  *   - [[applyAsync]] is the async entry; it propagates the callback / trace / `ActivePredictContext` thread-locals
  *     across the thread boundary via [[dspy4s.core.runtime.ContextPropagation.future]]. */
trait Module:
  def moduleName: String

  /** The program's actual computation, minus the module lifecycle. Subclasses implement this; callers invoke
    * [[apply]] (or [[applyAsync]]), never `forward`. */
  protected def forward(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction]

  /** What gets recorded as the trace/history `outputs` for a successful call. Defaults to the prediction values;
    * overridable for programs that want to record a projection. */
  protected def tracePayload(prediction: DynamicPrediction): DynamicValue.Record = prediction.values

  final def apply(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    val inputBag = input.inputs
    CallbackDispatcher.withModule(moduleName, inputBag) {
      val result = forward(input)
      if input.traceEnabled then
        result match
          case Right(prediction) =>
            val outputs = tracePayload(prediction)
            RuntimeEnvironment.appendTrace(
              TraceEntry(component = moduleName, inputs = inputBag, outputs = outputs)
            )
            RuntimeEnvironment.appendHistory(
              HistoryEntry(component = moduleName, payload = DynamicValues.record("inputs" -> inputBag, "outputs" -> outputs))
            )
          case Left(_) => ()
      result
    }

  def applyAsync(input: ProgramCall)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, DynamicPrediction]] =
    ContextPropagation.future(apply(input))
