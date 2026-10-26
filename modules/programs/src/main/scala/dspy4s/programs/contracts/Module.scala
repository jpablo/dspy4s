package dspy4s.programs.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
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
import dspy4s.core.runtime.UsageScope
import zio.blocks.schema.DynamicValue

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** The base type for every dspy4s program — a port of Python DSPy's `dspy.Module`. Its type parameters are the semantic
  * input `I` and output `O`; the uniform execution boundary adds [[ProgramCall]] on input and [[Prediction]] on output.
  * The *same* base serves both layers of dspy4s:
  *
  *   - the record-valued surface, `Module[DynamicValue.Record, DynamicValue.Record]` (see [[DynamicModule]]), whose raw
  *     engine result is lifted by [[Prediction.dynamic]]; and
  *   - the domain-valued surface, `Module[I, O]`, used by `Predict[I, O]`, `ChainOfThought[I, O]`, and the other
  *     programs — matching Python, where `Predict` / `ChainOfThought` / `ReAct` are all `Module`s.
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
  * the explicit [[ModuleLifecycle]] strategy that bridges the generic call/prediction into those records, or marks a
  * structural module as lifecycle-transparent.
  *
  * [[moduleName]] is the public identity (snake_case: `"predict"`, `"chain_of_thought"`, `"react"`), used by callbacks,
  * trace entries, and stream-listener routing. [[applyAsync]] is the value-only async entry; [[applyAsyncExecuted]]
  * additionally returns the worker's trace/history delta. Both propagate runtime services, configuration, scope, and
  * registered carriers across the thread boundary.
  *
  * [[apply]] installs the context that it gets for the duration of the call (UPGRADE-3.2.1-to-3.4.0, decision D8: the
  * given context wins). Thus the callbacks, the workers, and the other parts that read the installed context see the
  * settings of the given context, also when the caller did not install it. Each field that the given context does not
  * set comes from the installed context. [[forward]] gets this effective context as its `using` value, so the program
  * resolves its model and its adapter from the same context. The trace and the history of the call reach the installed
  * context of the caller as before. A nested call that gets the effective context of its caller installs nothing.
  * [[applyAsync]] runs [[apply]] on the worker, so it installs the given context there.
  *
  * [[apply]] opens one usage scope for the call ([[UsageScope.measure]], decision D9 of UPGRADE-3.2.1-to-3.4.0). The LM
  * usage of a successful result (`raw.lmUsage`) is the total of each model call that the call made. This includes a
  * call whose reply failed to parse, a call inside a tool, a call on a worker thread, and each attempt of `BestOfN` and
  * `Refine`. A cache hit and a call that failed at the provider add nothing. The usage trackers of the user
  * (`UsageTracking`) get the same records ([[UsageScope]]), when `trackUsage` is not `false`.
  *
  * The total replaces the usage that [[forward]] gave. It is `None` when no model call gave a usage. A nested call has
  * the total of its own calls, and the outer total has them one time. The callbacks of the module end see the total.
  *
  * Upstream `Module.__call__` also opens a usage tracker and sets its total on the result (`dspy/primitives/module.py`,
  * `track_usage` and `_set_lm_usage`). But upstream does this only when `track_usage` is on, and only on the outermost
  * call. Its usage has one entry for each model. The port sets one total on each result, always.
  */
trait Module[I, O]:
  def moduleName: String

  /** How this module boundary participates in callbacks, trace, and history. */
  protected val lifecycle: ModuleLifecycle[I, O]

  /** The program's actual computation, minus the module lifecycle. Subclasses implement this; callers invoke [[apply]]
    * (or [[applyAsync]]), never `forward`.
    */
  protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]]

  final def apply(call: ProgramCall[I])(using context: RuntimeContext): Either[DspyError, Prediction[O]] =
    RuntimeEnvironment.withInstalled(context)(effective ?=> runLifecycle(call)(using effective))

  /** The module lifecycle around [[forward]], with the effective context of the call. */
  private def runLifecycle(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    lifecycle match
      case ModuleLifecycle.Transparent()         => measured(forward(call))
      case ModuleLifecycle.Observed(observation) =>
        val inputBag = observation.inputs(call)
        // The outermost predictor on the call path owns the key. A module that runs inside a predictor (the `Predict`
        // inside `ChainOfThought`) records no key, so one predictor call gives exactly one entry with a key. The scope
        // keeps the key, so the decision evidence of the call belongs to the same predictor.
        val nested       = RuntimeEnvironment.withinPredictor
        val predictorKey = observation.predictor.filterNot(_ => nested)
        CallbackDispatcher.withModule(moduleName, inputBag) {
          val result = measured {
            predictorKey match
              case Some(key) => RuntimeEnvironment.withinPredictor(key)(forward(call))
              case None      => forward(call)
          }
          if observation.traceEnabled(call) then
            result match
              case Right(output) =>
                val outputs = observation.outputs(output)
                RuntimeEnvironment.appendTrace(
                  TraceEntry(
                    component = moduleName,
                    inputs = inputBag,
                    outputs = outputs,
                    predictor = predictorKey,
                    withinPredictor = nested
                  )
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
                      failure = Some(error.message),
                      predictor = predictorKey,
                      withinPredictor = nested
                    )
                  )
          result
        }

  /** The result of `body` with the total of its usage scope as its LM usage (see [[apply]]). */
  private def measured(body: => Either[DspyError, Prediction[O]]): Either[DspyError, Prediction[O]] =
    val (result, usage) = UsageScope.measure(body)
    result.map(prediction => prediction.copy(raw = prediction.raw.copy(lmUsage = usage)))

  /** Convenience entry for an ordinary input. Constructs the uniform [[ProgramCall]] envelope and dispatches through
    * the lifecycle-wrapped [[apply]]. Call the envelope overload directly when setting controls such as `rolloutId`
    * that are not represented here.
    */
  final def apply(
      input       : I,
      config      : DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean             = true
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    apply(call = ProgramCall(input, config, traceEnabled))

  /** Async value-only compatibility entry. Worker trace/history is isolated; use [[applyAsyncExecuted]] when the
    * observable runtime output must be retained and explicitly joined into another execution.
    */
  def applyAsync(
      call: ProgramCall[I]
  )(using RuntimeContext, ExecutionContext): Future[Either[DspyError, Prediction[O]]] =
    applyAsyncExecuted(call).map(_.value)(using ExecutionContext.parasitic)

  /** Async writer entry: returns the program result together with the worker-produced runtime delta. */
  def applyAsyncExecuted(
      call: ProgramCall[I]
  )(using RuntimeContext, ExecutionContext): Future[Executed[Either[DspyError, Prediction[O]]]] =
    ContextPropagation.futureExecuted(apply(call))

/** A structural program node whose own identity is not part of execution observability. Its children remain ordinary
  * [[Module]]s and therefore still emit callbacks, trace, and history. Keeping this distinction in the base lifecycle
  * prevents `AndThen(AndThen(a, b), c)` and `AndThen(a, AndThen(b, c))` from producing different runtime observations
  * solely because their syntax trees are associated differently.
  */
private[programs] trait TransparentModule[I, O] extends Module[I, O]:
  final override protected val lifecycle: ModuleLifecycle[I, O] = ModuleLifecycle.transparent

/** Record-valued programs: `Module[DynamicValue.Record, DynamicValue.Record]` with a lifecycle strategy for the record
  * shapes. Subclasses implement [[forwardDynamic]] in terms of the raw engine envelope; this trait lifts that result
  * exactly once through [[Prediction.dynamic]], making the ordinary [[Module]] boundary uniform.
  *
  * [[dspy4s.programs.strategies.DynamicPredict DynamicPredict]] is the record-valued prediction module; user-defined
  * data-bag programs may extend it too. [[dspy4s.programs.strategies.Predict Predict]] is a sibling module over the
  * shared `PredictEngine`, not a wrapper around `DynamicPredict`.
  */
trait DynamicModule extends Module[DynamicValue.Record, DynamicValue.Record]:
  // A dynamic module that is an optimizable leaf overrides this with `ModuleLifecycle.dynamicPredictor(key)`.
  override protected val lifecycle: ModuleLifecycle[DynamicValue.Record, DynamicValue.Record] = ModuleLifecycle.dynamic

  protected def forwardDynamic(call: ProgramCall[DynamicValue.Record])(using
      RuntimeContext
  ): Either[DspyError, RawPrediction]

  final override protected def forward(call: ProgramCall[DynamicValue.Record])(using
      RuntimeContext
  ): Either[DspyError, Prediction[DynamicValue.Record]] =
    forwardDynamic(call).map(Prediction.dynamic)
