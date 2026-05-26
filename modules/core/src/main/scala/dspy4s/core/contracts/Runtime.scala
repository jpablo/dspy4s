package dspy4s.core.contracts

import java.time.Instant

/** Marker trait for the language-model setting value. Empty by design -- this trait lives in `core` so
  * [[SettingKeys.languageModel]] can name its value type, while the concrete `LanguageModel` trait (with `call` /
  * `acall`) lives in `lm/contracts` and cannot be referenced from here without inverting the module dependency
  * graph. The real [[dspy4s.lm.contracts.LanguageModel]] extends `LanguageModelRef`; downstream code (e.g.
  * `ProgramRuntime.resolveModel`) reads the setting and narrows back to `LanguageModel` via a pattern match. */
trait LanguageModelRef

/** Marker trait for the adapter setting value. Same cycle-breaking pattern as [[LanguageModelRef]]: the concrete
  * `Adapter` trait lives in `adapters/contracts` and extends `AdapterRef`, so [[SettingKeys.adapter]] can hold
  * adapters without `core` depending on the adapters module. */
trait AdapterRef

/** A typed key into [[Settings]] -- pairs a string name with the expected value type `A`.
  *
  * The type parameter is phantom at the store level (settings are backed by a `Map[String, Any]`), but used by
  * [[Settings.get]] / [[Settings.updated]] to make read and write call sites type-safe at the API surface. The
  * runtime cast in `get` is the only place the unsafety surfaces: as long as every writer goes through `updated`
  * with the matching key, reads via `get` return the right type. */
final case class SettingKey[A](name: String)

/** Immutable settings store: a `Map[String, Any]` of configuration values keyed by string name, with typed access
  * via [[SettingKey]]. Used by [[RuntimeContext]] to carry effective configuration through the `using
  * RuntimeContext` parameter every program / adapter / LM call receives.
  *
  * `get` performs an `asInstanceOf` cast back to the key's declared type. This is sound by construction because
  * writes go through [[updated]], which takes a matching `SettingKey[A]` and `value: A` -- as long as no caller
  * pokes the underlying `entries` map directly, the cast never fails. (The map is exposed for cache-key hashing
  * and snapshot inspection; treat it as read-only.) */
final case class Settings(entries: Map[String, Any] = Map.empty):
  def get[A](key: SettingKey[A]): Option[A] = entries.get(key.name).map(_.asInstanceOf[A])

  def updated[A](key: SettingKey[A], value: A): Settings =
    copy(entries = entries.updated(key.name, value))

/** Catalog of well-known [[SettingKey]]s used by dspy4s itself. User code freely defines its own `SettingKey`s for
  * custom settings; the keys below are the ones the runtime / programs / LM stack read or write directly.
  *
  *   - [[languageModel]] / [[adapter]] -- resolved at the start of every `Predict` call by `ProgramRuntime`.
  *   - [[callbacks]] -- the vector of [[CallbackHandler]]s the dispatcher emits events to.
  *   - [[asyncTaskId]] -- present inside a `RuntimeEnvironment.withAsyncTask` scope; used to correlate work that
  *     spans threads.
  *   - [[activeCallId]] / [[callStack]] -- the active and accumulated call ids that `CallbackDispatcher` uses to
  *     correlate Start / End events.
  *   - [[numThreads]] / [[maxErrors]] -- concurrency knobs read by `ParallelExecutor` and `Evaluate`.
  *   - [[maxHistorySize]] -- cap on the per-thread history ring (default 10000 in `RuntimeEnvironment`).
  *   - [[disableHistory]] / [[trackUsage]] -- flags read by `ManagedLanguageModel` to skip history append / usage
  *     accumulation per call.
  */
object SettingKeys:
  val languageModel: SettingKey[LanguageModelRef] = SettingKey("lm")
  val adapter: SettingKey[AdapterRef] = SettingKey("adapter")
  val callbacks: SettingKey[Vector[CallbackHandler]] = SettingKey("callbacks")
  val asyncTaskId: SettingKey[String] = SettingKey("async_task_id")
  val activeCallId: SettingKey[String] = SettingKey("active_call_id")
  val callStack: SettingKey[Vector[String]] = SettingKey("call_stack")
  val numThreads: SettingKey[Int] = SettingKey("num_threads")
  val maxErrors: SettingKey[Int] = SettingKey("max_errors")
  val maxHistorySize: SettingKey[Int] = SettingKey("max_history_size")
  val disableHistory: SettingKey[Boolean] = SettingKey("disable_history")
  val trackUsage: SettingKey[Boolean] = SettingKey("track_usage")

/** A single observed module call -- one entry per `BasePredictProgram.run` (or any other module that records
  * itself). `component` is the module's `moduleName` (`"predict"`, `"chain_of_thought"`, ...), `inputs` is the
  * `ProgramCall.inputs` map, `outputs` is the module's `tracePayload(prediction)` (defaults to
  * `prediction.values`).
  *
  * Appended to [[RuntimeContext.trace]] only when the underlying `ProgramCall.traceEnabled` is `true` AND the
  * call succeeds. Failed calls produce no trace entry. */
final case class TraceEntry(
    component: String,
    inputs: Map[String, Any],
    outputs: Map[String, Any],
    timestamp: Instant = Instant.now()
)

/** A single observed LM (or module) call's payload, kept in [[RuntimeContext.history]] for inspection and -- for
  * LM entries -- usage accounting. `component` is the module name or LM id; `payload` is the
  * caller-defined snapshot (typically `Map("inputs" -> ..., "outputs" -> ...)`).
  *
  * The history ring is capped by [[SettingKeys.maxHistorySize]] in `RuntimeEnvironment.appendHistory`. */
final case class HistoryEntry(component: String, payload: Map[String, Any], timestamp: Instant = Instant.now())

/** The unit of execution context: everything an in-flight call needs to know about its environment. Threaded as
  * `using RuntimeContext` on every `Module.run`, `Adapter.format` / `parse`, `LanguageModel.call`, and
  * `CallbackHandler.onEvent`. Holds the live [[Settings]] (LM, adapter, callbacks, per-feature flags), the
  * accumulated [[trace]] / [[history]], and a flattened [[callbacks]] vector cached for hot-path event dispatch
  * (also reachable via `settings.get(SettingKeys.callbacks)`, but the field avoids a per-event map lookup).
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
    settings: Settings = Settings(),
    trace: Vector[TraceEntry] = Vector.empty,
    history: Vector[HistoryEntry] = Vector.empty,
    callbacks: Vector[CallbackHandler] = Vector.empty
):
  def withSettings(updated: Settings): RuntimeContext = copy(settings = updated)
  def withCallbacks(updated: Vector[CallbackHandler]): RuntimeContext = copy(callbacks = updated)
  def withHistory(updated: Vector[HistoryEntry]): RuntimeContext = copy(history = updated)
  def appendTrace(entry: TraceEntry): RuntimeContext = copy(trace = trace :+ entry)
  def appendHistory(entry: HistoryEntry): RuntimeContext = copy(history = history :+ entry)
