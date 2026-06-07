package dspy4s.core.runtime

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.HistoryRenderer
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

/** The process's single source of truth for the active [[RuntimeContext]] -- the configured LM / adapter /
  * callbacks plus the per-call accumulated trace, history, and call stack. State lives in two tiers that
  * [[current]] merges on every read:
  *
  *   - a process-wide global default (set via [[configure]]), shared across all threads;
  *   - a per-thread overlay installed by the scoped `with*` helpers, which owns the mutable accumulated state
  *     (trace / history / call stack) and shadows the global for the duration of a thunk.
  *
  * `current` projects the thread-local overlay onto the global, so a thread sees its own scoped overrides on
  * top of the shared configuration. Every `with*` helper restores the prior overlay when its thunk returns, so
  * overrides never leak past their dynamic scope.
  *
  * This object also mints the monotonic ids that correlate observability events (`callId`, async-task id) and
  * is the fan-out point for callback delivery ([[emit]]).
  *
  * Thread-safety: the global default and the id counters are atomics; the overlay is a `ThreadLocal`, so
  * concurrent threads never share mutable context. Cross-thread / cross-async-task reconfiguration is guarded --
  * see [[configure]].
  */
object RuntimeEnvironment:
  // Process-wide default context, shared across threads and merged beneath every thread's overlay by `current`.
  private val globalRef = new AtomicReference[RuntimeContext](RuntimeContext())
  // configure() ownership latches: the first caller's thread id (and async-task id, if it runs under one).
  // Only that owner may reconfigure; -1L / null mean "unclaimed".
  private val configureOwnerThreadId = new AtomicLong(-1L)
  private val configureOwnerAsyncTaskId = new AtomicReference[String | Null](null)
  // Monotonic sources for generated async-task ids and callback callIds.
  private val asyncTaskCounter = new AtomicLong(0L)
  private val callbackCallCounter = new AtomicLong(0L)

  // Per-thread overlay: the live context holding scoped overrides plus accumulated trace / history / call stack.
  private val contextRef = new ThreadLocal[RuntimeContext]:
    override def initialValue(): RuntimeContext = RuntimeContext()

  private def localContext: RuntimeContext = contextRef.get()

  /** Enforce the single-owner rule for [[configure]]: the first thread to call it claims ownership, and only
    * that thread -- and, when it runs under an async task, only that same task -- may call `configure` again.
    * Returns `Left(ConfigurationError)` for any other caller. */
  @tailrec
  private def ensureConfigureAllowed(): Either[DspyError, Unit] =
    val caller = Thread.currentThread().threadId()
    val owner = configureOwnerThreadId.get()
    if owner == -1L then
      if configureOwnerThreadId.compareAndSet(-1L, caller) then ensureConfigureAllowed()
      else ensureConfigureAllowed()
    else if owner != caller then Left(ConfigurationError("Cannot call RuntimeEnvironment.configure from a non-owner thread"))
    else
      current.asyncTaskId match
        case None => Right(())
        case Some(taskId) =>
          val asyncOwner = configureOwnerAsyncTaskId.get()
          if asyncOwner == null then
            if configureOwnerAsyncTaskId.compareAndSet(null, taskId) then Right(())
            else ensureConfigureAllowed()
          else if asyncOwner == taskId then Right(())
          else
            Left(
              ConfigurationError(
                "RuntimeEnvironment.configure(...) can only be called from the same async task that called it first. Use RuntimeEnvironment.withSettings(...) in other async tasks."
              )
            )

  /** The currently-active [[RuntimeContext]] for this thread. Starts from the live thread-local context (which
    * owns trace / history / accumulated state) and fills in any unset configured field from the global. */
  def current: RuntimeContext = localContext.fillFrom(globalRef.get())

  /** Set the process-wide default context. Only the first thread to call `configure` may call it again; cross-task
    * mutation must use `withSettings` instead. */
  def configure(context: RuntimeContext): Either[DspyError, Unit] =
    ensureConfigureAllowed().map { _ =>
      globalRef.set(context)
      ()
    }

  /** Run `thunk` with `current` transformed by `update` -- a functional, thunk-scoped override of one or more
    * context fields. */
  def withSetting[A](update: RuntimeContext => RuntimeContext)(thunk: => A): A =
    withContext(update(current))(thunk)

  /** Run `thunk` tagged with the given async-task id, used to scope [[configure]] ownership and correlate work
    * that fans out across async tasks. */
  def withAsyncTask[A](taskId: String)(thunk: => A): A =
    withSetting(_.copy(asyncTaskId = Some(taskId)))(thunk)

  /** Like [[withAsyncTask]], but mints a fresh `"$prefix-N"` id from a monotonic counter. */
  def withGeneratedAsyncTask[A](prefix: String = "async-task")(thunk: => A): A =
    val taskId = s"$prefix-${asyncTaskCounter.incrementAndGet()}"
    withAsyncTask(taskId)(thunk)

  /** Mint a fresh monotonic `"$prefix-N"` id. [[dspy4s.core.runtime.CallbackDispatcher]] uses this to tag each
    * callback scope's `callId`. */
  def nextCallId(prefix: String = "call"): String =
    s"$prefix-${callbackCallCounter.incrementAndGet()}"

  /** The `callId`s of the scopes currently open on this thread, outermost first. */
  def activeCallStack: Vector[String] = current.callStack

  /** How many `with*`-call scopes enclose the current point. */
  def activeCallDepth: Int = activeCallStack.size

  /** The innermost open scope's `callId`, if any -- the `parentCallId` a newly-opened scope should inherit. */
  def activeCallId: Option[String] = current.callStack.lastOption.orElse(current.activeCallId)

  /** Push `callId` onto the call stack (and mark it the active call) for the duration of `thunk`, then unwind
    * just the call-tracking fields. Unlike [[withContext]], trace / history accumulated inside `thunk` are
    * preserved on exit, since those bubble up to the enclosing scope. */
  def withActiveCall[A](callId: String)(thunk: => A): A =
    val previous = localContext
    val updated = previous.copy(
      activeCallId = Some(callId),
      callStack    = previous.callStack :+ callId
    )
    contextRef.set(updated)
    try thunk
    finally
      val after = localContext
      contextRef.set(after.copy(activeCallId = previous.activeCallId, callStack = previous.callStack))

  /** Replace this thread's overlay context wholesale for the duration of `thunk`, restoring the previous overlay
    * on exit. The low-level primitive the other `with*` helpers build on. Because the restore is wholesale, any
    * state accumulated on the overlay inside the thunk (trace / history) does not outlive the scope. */
  def withContext[A](context: RuntimeContext)(thunk: => A): A =
    val previous = contextRef.get()
    contextRef.set(context)
    try thunk
    finally contextRef.set(previous)

  /** Run `thunk` with `settings` overlaid on `current`: `settings` wins for every field it sets and `current`
    * supplies the rest (`RuntimeContext.fillFrom`). The cross-task-safe way to install an LM / adapter /
    * callbacks for a scope, in contrast to the process-wide [[configure]]. */
  def withSettings[A](settings: RuntimeContext)(thunk: => A): A =
    withContext(settings.fillFrom(current))(thunk)

  /** Run `thunk` with the callback handler list replaced by `callbacks`, restoring it afterward. */
  def withCallbacks[A](callbacks: Vector[CallbackHandler])(thunk: => A): A =
    withContext(current.copy(callbacks = callbacks))(thunk)

  /** Append a trace entry to this thread's overlay; visible to [[current]] for the rest of the enclosing scope. */
  def appendTrace(entry: TraceEntry): Unit =
    contextRef.set(localContext.appendTrace(entry))

  /** Append a history entry, honoring the context's `disableHistory` flag and `maxHistorySize` cap (default
    * 10000; oldest entries drop once the cap is exceeded). No-op when history is disabled or the cap is <= 0. */
  def appendHistory(entry: HistoryEntry): Unit =
    val effective = current
    val historyDisabled = effective.disableHistory.getOrElse(false)
    val cap = effective.maxHistorySize.getOrElse(10000)
    if !historyDisabled && cap > 0 then
      val base = localContext
      val nextHistory = (base.history :+ entry).takeRight(cap)
      contextRef.set(base.withHistory(nextHistory))

  /** Render the last `n` LM-call history entries on the active context as a human-readable string, in the spirit
    * of upstream `dspy.inspect_history(n)`. dspy4s has no global per-LM history buffer; history is the per-thread
    * accumulation on [[dspy4s.core.contracts.RuntimeContext.history]] (filled by [[appendHistory]]), so this reads
    * `current.history`. `n <= 0` yields an empty render; `n` larger than the available history is clamped. The
    * payload shape is caller-defined, so [[dspy4s.core.contracts.HistoryRenderer]] renders each field generically.
    */
  def inspectHistory(n: Int = 10): String =
    val entries = if n <= 0 then Vector.empty else current.history.takeRight(n)
    HistoryRenderer.render(entries)

  /** Convenience: [[inspectHistory]] printed to stdout, matching upstream `dspy.inspect_history`'s print-only
    * behavior. Returns the same string it printed so callers can also capture it. */
  def printHistory(n: Int = 10): String =
    val rendered = inspectHistory(n)
    println(rendered)
    rendered

  /** The callback handlers registered on the active context. */
  def activeCallbacks: Vector[CallbackHandler] = current.callbacks

  /** Deliver `event` to every active callback handler, with the active [[RuntimeContext]] in implicit scope. */
  def emit(event: CallbackEvent): Unit =
    val context = current
    given RuntimeContext = context
    activeCallbacks.foreach(_.onEvent(event))

  /** Reset all global and thread-local state to defaults: clears configure ownership, the id counters, the
    * global default context, and this thread's overlay. Test-only. */
  def resetForTests(): Unit =
    globalRef.set(RuntimeContext())
    configureOwnerThreadId.set(-1L)
    configureOwnerAsyncTaskId.set(null)
    asyncTaskCounter.set(0L)
    callbackCallCounter.set(0L)
    contextRef.remove()
