package dspy4s.programs.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Executed
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

/** The base type for every dspy4s program — a port of Python DSPy's `dspy.Module`. It is generic in the call input `I`
  * and result `O` so the *same* base serves both layers of dspy4s:
  *
  *   - the untyped spine, `Module[ProgramCall[DynamicValue.Record], DynamicPrediction]` (see [[DynamicModule]]), which
  *     every engine program (`DynamicPredict`, `ReAct`, `CodeAct`, ...) extends; and
  *   - the typed surface, `Module[ProgramCall[I], Prediction[O]]`, which `Predict[I, O]` / `ChainOfThought[I, O]`
  *     extend — matching Python, where `Predict` / `ChainOfThought` / `ReAct` are all `Module`s.
  *
  * A program implements [[forward]]; [[apply]] is the `final` caller entry (Scala's `__call__`) that wraps `forward`
  * with the module lifecycle — the callback `ModuleStart`/`ModuleEnd` scope plus trace/history recording. That
  * bookkeeping is the runtime's responsibility (`RuntimeEnvironment` / `CallbackDispatcher`), not the program's;
  * subclasses implement only `forward`. Because `apply` is `final`, leaf modules cannot bypass the wrapping. Structural
  * combinators use [[TransparentModule]] so association and identity wrappers remain operationally invisible: their
  * children still receive the normal lifecycle, while the syntax used to compose those children does not add callbacks,
  * trace, or history entries of its own.
  *
  * Callbacks, trace, and history all record `DynamicValue.Record`s, not the static `I` / `O`. [[lifecycle]] provides
  * the explicit [[ModuleLifecycle]] strategy that bridges the generic call/result into those records, or marks a
  * structural module as lifecycle-transparent.
  *
  * [[moduleName]] is the public identity (snake_case: `"predict"`, `"chain_of_thought"`, `"react"`), used by callbacks,
  * trace entries, and stream-listener routing. [[applyAsync]] is the value-only async entry; [[applyAsyncExecuted]]
  * additionally returns the worker's trace/history delta. Both propagate runtime services, configuration, scope, and
  * registered carriers across the thread boundary.
  */
trait Module[I, O]:
  def moduleName: String

  /** How this module boundary participates in callbacks, trace, and history. */
  protected val lifecycle: ModuleLifecycle[I, O]

  /** The program's actual computation, minus the module lifecycle. Subclasses implement this; callers invoke [[apply]]
    * (or [[applyAsync]]), never `forward`.
    */
  protected def forward(input: I)(using RuntimeContext): Either[DspyError, O]

  final def apply(input: I)(using RuntimeContext): Either[DspyError, O] =
    lifecycle match
      case ModuleLifecycle.Transparent() => forward(input)
      case ModuleLifecycle.Observed(observation) =>
        val inputBag = observation.inputs(input)
        CallbackDispatcher.withModule(moduleName, inputBag) {
          val result = forward(input)
          if observation.traceEnabled(input) then
            result match
              case Right(output) =>
                val outputs = observation.outputs(output)
                RuntimeEnvironment.appendTrace(
                  TraceEntry(component = moduleName, inputs = inputBag, outputs = outputs)
                )
                RuntimeEnvironment.appendHistory(
                  HistoryEntry(
                    component = moduleName,
                    payload = DynamicValues.record("inputs" -> inputBag, "outputs" -> outputs)
                  )
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
                    TraceEntry(
                      component = moduleName,
                      inputs = inputBag,
                      outputs = rawOutputs,
                      failure = Some(error.message)
                    )
                  )
          result
        }

  /** Async value-only compatibility entry. Worker trace/history is isolated; use [[applyAsyncExecuted]] when the
    * observable runtime output must be retained and explicitly joined into another execution.
    */
  def applyAsync(input: I)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, O]] =
    applyAsyncExecuted(input).map(_.value)(using ExecutionContext.parasitic)

  /** Async writer entry: returns the program result together with the worker-produced runtime delta. */
  def applyAsyncExecuted(input: I)(using RuntimeContext, ExecutionContext): Future[Executed[Either[DspyError, O]]] =
    ContextPropagation.futureExecuted(apply(input))

/** A structural program node whose own identity is not part of execution observability. Its children remain ordinary
  * [[Module]]s and therefore still emit callbacks, trace, and history. Keeping this distinction in the base lifecycle
  * prevents `AndThen(AndThen(a, b), c)` and `AndThen(a, AndThen(b, c))` from producing different runtime observations
  * solely because their syntax trees are associated differently.
  */
private[programs] trait TransparentModule[I, O] extends Module[I, O]:
  final override protected val lifecycle: ModuleLifecycle[I, O] = ModuleLifecycle.transparent

/** The untyped program spine: `Module[ProgramCall[DynamicValue.Record], DynamicPrediction]` with a lifecycle strategy
  * for the spine record shapes (`call.input` / `prediction.values`).
  * [[dspy4s.programs.DynamicPredict DynamicPredict]] is the untyped prediction module on this spine; user-defined
  * data-bag programs may extend it too. The typed [[dspy4s.programs.Predict Predict]] is a sibling module over the
  * shared `PredictEngine`, not a wrapper around `DynamicPredict`. Subclasses implement only `forward` + `moduleName`;
  * `lifecycle` stays overridable for programs that need a different observation. (The typed programs — `Predict` /
  * `ChainOfThought` / `ReAct` / `CodeAct` / `ProgramOfThought` / `MultiChainComparison` / `BestOfN` / `Refine` —
  * instead extend `Module[ProgramCall[I], Prediction[…]]`.)
  */
trait DynamicModule extends Module[ProgramCall[DynamicValue.Record], DynamicPrediction]:
  override protected val lifecycle: ModuleLifecycle[ProgramCall[DynamicValue.Record], DynamicPrediction] =
    ModuleLifecycle.dynamic
