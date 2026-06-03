package dspy4s.core.contracts

import zio.blocks.schema.DynamicValue

import java.time.Instant

/** Marker trait for the language-model context value. Empty by design -- this trait lives in `core` so
  * [[RuntimeContext]] can name its `lm` field type, while the concrete `LanguageModel` trait (with `call` /
  * `acall`) lives in `lm/contracts` and cannot be referenced from here without inverting the module dependency
  * graph. The real [[dspy4s.lm.contracts.LanguageModel]] extends `LanguageModelRef`; downstream code (e.g.
  * `ProgramRuntime.resolveModel`) reads `ctx.lm` and narrows back to `LanguageModel` via a pattern match. */
trait LanguageModelRef

/** Marker trait for the adapter context value. Same cycle-breaking pattern as [[LanguageModelRef]]: the concrete
  * `Adapter` trait lives in `adapters/contracts` and extends `AdapterRef`, so [[RuntimeContext]] can hold an
  * adapter without `core` depending on the adapters module. */
trait AdapterRef

/** A single observed module call -- one entry per `BasePredictProgram.apply` (or any other module that records
  * itself). `component` is the module's `moduleName` (`"predict"`, `"chain_of_thought"`, ...), `inputs` is the
  * `ProgramCall.inputs` map, `outputs` is the module's `tracePayload(prediction)` (defaults to
  * `prediction.values`).
  *
  * Appended to [[RuntimeContext.trace]] only when the underlying `ProgramCall.traceEnabled` is `true` AND the
  * call succeeds. Failed calls produce no trace entry. */
final case class TraceEntry(
    component: String,
    inputs: DynamicValue.Record,
    outputs: DynamicValue.Record,
    timestamp: Instant = Instant.now()
)

/** A single observed LM (or module) call's payload, kept in [[RuntimeContext.history]] for inspection and -- for
  * LM entries -- usage accounting. `component` is the module name or LM id; `payload` is the
  * caller-defined snapshot (typically `Map("inputs" -> ..., "outputs" -> ...)`).
  *
  * The history ring is capped by [[RuntimeContext.maxHistorySize]] in `RuntimeEnvironment.appendHistory`. */
final case class HistoryEntry(component: String, payload: DynamicValue.Record, timestamp: Instant = Instant.now())

/** The unit of execution context: everything an in-flight call needs to know about its environment. Threaded as
  * `using RuntimeContext` on every `Module.run`, `Adapter.format` / `parse`, `LanguageModel.call`, and
  * `CallbackHandler.onEvent`.
  *
  * '''Configured fields''' (set by `RuntimeEnvironment.configure(...)` globally or `withSettings` / `withCallbacks`
  * scoped per thread):
  *
  *   - [[lm]] / [[adapter]] -- the active language model and adapter; resolved at the start of every `Predict`
  *     call by `ProgramRuntime`.
  *   - [[callbacks]] -- the vector of [[CallbackHandler]]s the dispatcher emits events to.
  *   - [[numThreads]] / [[maxErrors]] -- concurrency knobs read by `ParallelExecutor` and `Evaluate` (defaults
  *     8 / 10).
  *   - [[maxHistorySize]] -- cap on the per-thread history ring (default 10000).
  *   - [[disableHistory]] / [[trackUsage]] -- flags read by `ManagedLanguageModel` to skip history append /
  *     usage accumulation per call (defaults false / true).
  *
  * '''Accumulated fields''' (set by lifecycle scopes, not by `configure`):
  *
  *   - [[asyncTaskId]] -- present inside a `RuntimeEnvironment.withAsyncTask` scope; used to correlate work that
  *     spans threads.
  *   - [[activeCallId]] / [[callStack]] -- the active and accumulated call ids that `CallbackDispatcher` uses to
  *     correlate Start / End events.
  *   - [[trace]] / [[history]] -- the running trace and LM-call history.
  *
  * Construction is rare in user code -- `RuntimeEnvironment.current` returns the active thread-local context, and
  * `RuntimeEnvironment.withSettings` / `withCallbacks` / `withContext` are the scoped-update entry points.
  * `RuntimeContext()` (all defaults) is fine for tests; production code generally starts from
  * `RuntimeEnvironment.current`.
  *
  * The `with*` and `append*` methods are pure: each returns a new `RuntimeContext` with the change applied.
  * `RuntimeEnvironment` is what makes those updates visible to the rest of the call by replacing the active
  * thread-local context.
  */
final case class RuntimeContext(
    // Configured
    lm:             Option[LanguageModelRef] = None,
    adapter:        Option[AdapterRef]       = None,
    callbacks:      Vector[CallbackHandler]  = Vector.empty,
    numThreads:     Option[Int]              = None,
    maxErrors:      Option[Int]              = None,
    maxHistorySize: Option[Int]              = None,
    disableHistory: Option[Boolean]          = None,
    trackUsage:     Option[Boolean]          = None,
    // Accumulated
    asyncTaskId:    Option[String]           = None,
    activeCallId:   Option[String]           = None,
    callStack:      Vector[String]           = Vector.empty,
    trace:          Vector[TraceEntry]       = Vector.empty,
    history:        Vector[HistoryEntry]     = Vector.empty
):

  def withCallbacks(updated: Vector[CallbackHandler]): RuntimeContext = copy(callbacks = updated)
  def withHistory(updated: Vector[HistoryEntry]): RuntimeContext = copy(history = updated)
  def appendTrace(entry: TraceEntry): RuntimeContext = copy(trace = trace :+ entry)
  def appendHistory(entry: HistoryEntry): RuntimeContext = copy(history = history :+ entry)

  /** Fill in any configured field that's unset on this context with the corresponding field from `defaults`.
    * `this` wins for any field it has set; `defaults` only supplies fall-backs. Trace and history are NOT
    * touched -- they're per-call accumulations, owned by the live thread-local context.
    *
    * Used by `RuntimeEnvironment.current` to project a thread-local context against the global configuration
    * (`localContext.fillFrom(global)`). */
  def fillFrom(defaults: RuntimeContext): RuntimeContext =
    copy(
      lm             = lm.orElse(defaults.lm),
      adapter        = adapter.orElse(defaults.adapter),
      callbacks      = if callbacks.nonEmpty then callbacks else defaults.callbacks,
      numThreads     = numThreads.orElse(defaults.numThreads),
      maxErrors      = maxErrors.orElse(defaults.maxErrors),
      maxHistorySize = maxHistorySize.orElse(defaults.maxHistorySize),
      disableHistory = disableHistory.orElse(defaults.disableHistory),
      trackUsage     = trackUsage.orElse(defaults.trackUsage),
      asyncTaskId    = asyncTaskId.orElse(defaults.asyncTaskId),
      activeCallId   = activeCallId.orElse(defaults.activeCallId),
      callStack      = if callStack.nonEmpty then callStack else defaults.callStack
    )
