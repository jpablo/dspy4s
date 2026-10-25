package dspy4s.core.runtime

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Executed
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.HistoryLimit
import dspy4s.core.contracts.HistoryRenderer
import dspy4s.core.contracts.PredictorKey
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeDelta
import dspy4s.core.contracts.RuntimeScope
import dspy4s.core.contracts.TraceEntry

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** The process's single source of truth for the active [[RuntimeContext]]: the configured LM / adapter / callbacks plus
  * the per-call accumulated trace, history, and call stack. State lives in two tiers that [[current]] merges on every
  * read:
  *
  *   - a process-wide global default (set via [[configure]]), shared across all threads;
  *   - a per-thread overlay installed by the scoped `with*` helpers, which owns the mutable accumulated state (trace /
  *     history / call stack) and shadows the global for the duration of a thunk.
  *
  * `current` projects the thread-local overlay onto the global, so a thread sees its own scoped overrides on top of the
  * shared configuration. Every `with*` helper restores the prior overlay when its thunk returns, so overrides never
  * leak past their dynamic scope.
  *
  * The given context wins (UPGRADE-3.2.1-to-3.4.0, decision D8; PORT_GAPS G-50). A caller can give a `using
  * RuntimeContext` that is not the installed context. The callbacks ([[emit]]), the parallel workers
  * ([[ContextPropagation.captureAll]]), and the other parts that read [[current]] would then see other settings than
  * the program. Thus each public entry point (a program call, `compile`, `evaluate`, a parallel run, a stream, a tool
  * call) installs the context that it gets with [[withInstalled]] for the duration of the call. The given context wins
  * for each field that it sets, and each field that it does not set comes from the installed context.
  *
  * This object also mints the monotonic ids that correlate observability events (`callId`, async-task id) and is the
  * fan-out point for callback delivery ([[emit]]).
  *
  * Thread-safety: the global default and the id counters are atomics; the overlay is a `ThreadLocal`, so concurrent
  * threads never share mutable context. Cross-thread / cross-async-task reconfiguration is guarded (see [[configure]]).
  */
object RuntimeEnvironment:
  // Process-wide default context, shared across threads and merged beneath every thread's overlay by `current`.
  private val globalRef = new AtomicReference[RuntimeContext](RuntimeContext())
  // configure() ownership latches: the first caller's thread id (and async-task id, if it runs under one).
  // Only that owner may reconfigure; -1L / null mean "unclaimed".
  private val configureOwnerThreadId    = new AtomicLong(-1L)
  private val configureOwnerAsyncTaskId = new AtomicReference[String | Null](null)
  // Monotonic sources for generated async-task ids and callback callIds.
  private val asyncTaskCounter    = new AtomicLong(0L)
  private val callbackCallCounter = new AtomicLong(0L)

  // Per-thread overlay: the live context holding scoped overrides plus accumulated trace / history / call stack.
  private val contextRef = new ThreadLocal[RuntimeContext]:
    override def initialValue(): RuntimeContext = RuntimeContext()

  private def localContext: RuntimeContext = contextRef.get()

  /** Enforce the single-owner rule for [[configure]]: the first thread to call it claims ownership, and only that
    * thread (and, when it runs under an async task, only that same task) may call `configure` again. Returns
    * `Left(ConfigurationError)` for any other caller.
    */
  @tailrec
  private def ensureConfigureAllowed(): Either[DspyError, Unit] =
    val caller = Thread.currentThread().threadId()
    val owner  = configureOwnerThreadId.get()
    if owner == -1L then
      val _ = configureOwnerThreadId.compareAndSet(-1L, caller)
      ensureConfigureAllowed()
    else if owner != caller then
      Left(ConfigurationError("Cannot call RuntimeEnvironment.configure from a non-owner thread"))
    else
      current.asyncTaskId match
        case None         => Right(())
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

  /** The currently-active [[RuntimeContext]] for this thread. Starts from the live thread-local context (which owns
    * trace / history / accumulated state) and fills in any unset configured field from the global.
    */
  def current: RuntimeContext = localContext.fillFrom(globalRef.get())

  /** Set the process-wide default context. Only the first thread to call `configure` may call it again; cross-task
    * mutation must use `withSettings` instead.
    */
  def configure(context: RuntimeContext): Either[DspyError, Unit] =
    ensureConfigureAllowed().map { _ =>
      globalRef.set(context)
      ()
    }

  /** Run `thunk` with `current` transformed by `update`: a functional, thunk-scoped override of one or more context
    * fields.
    */
  def withSetting[A](update: RuntimeContext => RuntimeContext)(thunk: => A): A =
    withContext(update(current))(thunk)

  /** Run `thunk` tagged with the given async-task id, used to scope [[configure]] ownership and correlate work that
    * fans out across async tasks.
    */
  def withAsyncTask[A](taskId: String)(thunk: => A): A =
    withSetting(ctx => ctx.withScope(ctx.scope.copy(asyncTaskId = Some(taskId))))(thunk)

  /** Like [[withAsyncTask]], but mints a fresh `"$prefix-N"` id from a monotonic counter. */
  def withGeneratedAsyncTask[A](prefix: String = "async-task")(thunk: => A): A =
    val taskId = s"$prefix-${asyncTaskCounter.incrementAndGet()}"
    withAsyncTask(taskId)(thunk)

  /** Mint a fresh monotonic `"$prefix-N"` id. [[dspy4s.core.runtime.CallbackDispatcher]] uses this to tag each callback
    * scope's `callId`.
    */
  def nextCallId(prefix: String = "call"): String =
    s"$prefix-${callbackCallCounter.incrementAndGet()}"

  /** The `callId`s of the scopes currently open on this thread, outermost first. */
  def activeCallStack: Vector[String] = current.callStack

  /** How many `with*`-call scopes enclose the current point. */
  def activeCallDepth: Int = activeCallStack.size

  /** The innermost open scope's `callId`, if any: the `parentCallId` a newly-opened scope should inherit. */
  def activeCallId: Option[String] = current.activeCallId

  /** Push `callId` onto the call stack (making it the active call) for the duration of `thunk`, then unwind just the
    * call-tracking fields. Unlike [[withContext]], trace / history accumulated inside `thunk` are preserved on exit,
    * since those bubble up to the enclosing scope.
    */
  def withActiveCall[A](callId: String)(thunk: => A): A =
    withScopeChange(scope => scope.copy(callStack = scope.callStack :+ callId))((after, before) =>
      after.copy(callStack = before.callStack)
    )(thunk)

  /** Run `thunk` with `enter` applied to the scope of this thread's overlay. On exit, `exit` gives the scope from the
    * scope that `thunk` left and the scope before the call. Unlike [[withContext]], the trace and the history that
    * `thunk` accumulates stay, also when `thunk` throws.
    */
  // `inline`, so the two functions of each call site allocate no closure on this hot path.
  private inline def withScopeChange[A](inline enter: RuntimeScope => RuntimeScope)(
      inline exit: (RuntimeScope, RuntimeScope) => RuntimeScope
  )(thunk: => A): A =
    val previous = localContext
    contextRef.set(previous.withScope(enter(previous.scope)))
    try thunk
    finally
      val after = localContext
      contextRef.set(after.withScope(exit(after.scope, previous.scope)))

  /** `true` while a predictor that owns a [[dspy4s.core.contracts.PredictorKey]] runs on this thread. */
  def withinPredictor: Boolean = current.scope.withinPredictor

  /** The key of the predictor that runs on this thread, or `None` outside a predictor. */
  def activePredictor: Option[PredictorKey] = current.scope.predictor

  /** Mark the extent of `thunk` as the body of the predictor with the key `key`, then restore the previous mark. Like
    * [[withActiveCall]], this keeps the trace and the history that `thunk` accumulates.
    */
  def withinPredictor[A](key: PredictorKey)(thunk: => A): A =
    withScopeChange(_.copy(predictor = Some(key)))((after, before) => after.copy(predictor = before.predictor))(thunk)

  /** Replace this thread's overlay context wholesale for the duration of `thunk`, restoring the previous overlay on
    * exit. The low-level primitive the other `with*` helpers build on. Because the restore is wholesale, any state
    * accumulated on the overlay inside the thunk (trace / history) does not outlive the scope.
    */
  def withContext[A](context: RuntimeContext)(thunk: => A): A =
    val previous = contextRef.get()
    contextRef.set(context)
    try thunk
    finally contextRef.set(previous)

  /** Run `thunk` with `context` as the installed context (decision D8: the given context wins). Each public entry point
    * that takes a `using RuntimeContext` calls this helper and runs its work with the context that `thunk` gets.
    *
    * The given context wins for each service and each configuration field that it sets. Each field that it does not set
    * comes from the installed context, as [[withSettings]] fills a context (upstream `dspy.context(**kwargs)` overrides
    * only the given settings and keeps the others). Thus a context that a caller captured before a [[withCallbacks]]
    * block keeps the callbacks of the block, and its own language model wins over the installed one.
    *
    * `thunk` gets the effective context: the context that [[current]] gives in the call. The program, the callbacks,
    * and the workers thus see one context. For example, a given context with only a language model gets the adapter of
    * the installed context, also for the parts that read the `using` value.
    *
    * The helper keeps the dynamic scope of this thread (the open call scopes, the async task, and the predictor mark)
    * and the trace and the history of this thread. On exit, it restores the services and the configuration of the
    * previous context, but the trace and the history that `thunk` adds stay, also when `thunk` throws. Thus, unlike
    * [[withContext]], the restore is not wholesale, and the output of the call reaches the caller as with no helper.
    * The usage trackers and the evidence log have their own thread-local state, so the helper does not change them.
    *
    * The helper only runs `thunk` with the current context, with no install, in two cases: the services and the
    * configuration of `context` are the current ones (a reference check), or each field that `context` sets already has
    * its value in the current context (`givesInstalledSettings`). A nested call that gets the effective context of its
    * caller thus costs no more than before.
    */
  def withInstalled[A](context: RuntimeContext)(thunk: RuntimeContext ?=> A): A =
    val previous  = localContext
    val effective = previous.fillFrom(globalRef.get())
    if givesInstalledSettings(context, effective) then thunk(using effective)
    else
      // The fill reads the overlay of this thread, not `current`, so the global default stays a default.
      val services = context.services.fillFrom(previous.services)
      val config   = context.config.fillFrom(previous.config)
      contextRef.set(RuntimeContext.fromParts(services, config, previous.scope, previous.delta))
      try thunk(using current)
      finally contextRef.set(previous.withDelta(localContext.delta))

  /** `true` when `context` filled from `installed` gives the services and the configuration of `installed`. The check
    * is cheap: it compares the parts by reference first, and then each field that `context` sets. A service, the
    * callback list, and the callback metadata must be the same object; a number or a flag must be equal. The check does
    * not read the trace or the history.
    */
  private def givesInstalledSettings(context: RuntimeContext, installed: RuntimeContext): Boolean =
    ((context.services eq installed.services) || context.services.keptBy(installed.services)) &&
      ((context.config eq installed.config) || context.config.keptBy(installed.config))

  /** Run `thunk` with `settings` overlaid on `current`: `settings` wins for every field it sets and `current` supplies
    * the rest (`RuntimeContext.fillFrom`). The cross-task-safe way to install an LM / adapter / callbacks for a scope,
    * in contrast to the process-wide [[configure]].
    */
  def withSettings[A](settings: RuntimeContext)(thunk: => A): A =
    withContext(settings.fillFrom(current))(thunk)

  /** Run `thunk` with the callback handler list replaced by `callbacks`, restoring it afterward. */
  def withCallbacks[A](callbacks: Vector[CallbackHandler])(thunk: => A): A =
    withContext(current.withCallbacks(callbacks))(thunk)

  /** Append a trace entry to this thread's overlay; visible to [[current]] for the rest of the enclosing scope. */
  def appendTrace(entry: TraceEntry): Unit =
    contextRef.set(localContext.appendTrace(entry))

  /** Append a history entry, honoring the context's `disableHistory` flag and `maxHistorySize` cap (default 10000;
    * oldest entries drop once the cap is exceeded). No-op when history is disabled or the cap is <= 0.
    */
  def appendHistory(entry: HistoryEntry): Unit = appendHistoryEntries(Vector(entry))

  /** [[appendHistory]] for each of `entries`, in order, with one write of the overlay. */
  private def appendHistoryEntries(entries: Vector[HistoryEntry]): Unit =
    val base            = localContext
    val effective       = base.fillFrom(globalRef.get())
    val historyDisabled = effective.disableHistory.getOrElse(false)
    val cap             = effective.maxHistorySize.getOrElse(HistoryLimit(10000))
    if entries.nonEmpty && !historyDisabled && cap > 0 then
      contextRef.set(base.withHistory((base.history ++ entries).takeRight(cap)))

  /** Run `body` under a fresh [[RuntimeDelta]] derived from `base`, returning the body's result and accumulated output
    * as an [[Executed]]. Used by the BestOfN / Refine attempt loops, which isolate each attempt (applying any
    * per-attempt service overrides to `base` first) then propagate only the winner's observability via [[propagate]].
    */
  def isolatedAttempt[A](base: RuntimeContext)(
      body: RuntimeContext ?=> A
  ): Executed[A] =
    val isolated = base.withDelta(RuntimeDelta.empty)
    withContext(isolated) {
      given RuntimeContext = current
      val result           = body
      Executed(result, current.delta)
    }

  /** Replay an explicitly captured delta into the current overlay, in order. This is the caller-controlled join
    * operation for isolated attempts and [[ContextPropagation.futureExecuted]] workers.
    */
  def propagate(delta: RuntimeDelta): Unit =
    if delta.trace.nonEmpty then
      val base = localContext
      contextRef.set(base.withDelta(base.delta.copy(trace = base.trace ++ delta.trace)))
    appendHistoryEntries(delta.history)

  /** Render the last `n` LM-call history entries on the active context as a human-readable string, as upstream
    * `dspy.inspect_history(n)`. dspy4s has no global per-LM history buffer; history is the per-thread accumulation on
    * [[dspy4s.core.contracts.RuntimeContext.history]] (filled by [[appendHistory]]), so this reads `current.history`.
    * Only the LM entries count: the entries whose component starts with `lm:`. The other entries of the history are not
    * shown. `n <= 0` yields an empty render; `n` larger than the available history is clamped.
    * [[dspy4s.core.contracts.HistoryRenderer]] gives the layout.
    */
  def inspectHistory(n: Int = 10): String =
    val entries = if n <= 0 then Vector.empty else current.history.filter(_.component.startsWith("lm:")).takeRight(n)
    HistoryRenderer.render(entries)

  /** Convenience: [[inspectHistory]] printed to stdout, matching upstream `dspy.inspect_history`'s print-only behavior.
    * Returns the same string it printed so callers can also capture it.
    */
  def printHistory(n: Int = 10): String =
    val rendered = inspectHistory(n)
    println(rendered)
    rendered

  /** Emit a one-off framework diagnostic to stderr under the shared `WARN [dspy4s]` convention. Diagnostics are not
    * callback events (those are strictly Start/End scope pairs; see [[dspy4s.core.contracts.CallbackEvent]]). All the
    * warnings of the framework go through this method, so the convention and any future redirection live in one place.
    */
  def warn(component: String, message: String): Unit =
    Console.err.println(s"WARN [dspy4s] $component: $message")

  /** Deliver `event` to every active callback handler, with the active [[RuntimeContext]] in implicit scope. Handlers
    * are observational: a non-fatal failure is reported and delivery continues, so an observer cannot replace the
    * result of the work it watches or starve later observers.
    */
  def emit(event: CallbackEvent): Unit =
    val context = current
    if context.callbacks.nonEmpty then
      given RuntimeContext = context
      context.callbacks.foreach { callback =>
        try callback.onEvent(event)
        catch
          case NonFatal(error) =>
            val detail = Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
            warn("callback", s"${callback.getClass.getName} failed for ${event.getClass.getSimpleName}: $detail")
      }

  /** Reset all global and thread-local state to defaults: clears configure ownership, the id counters, the global
    * default context, and this thread's overlay. Test-only.
    */
  def resetForTests(): Unit =
    globalRef.set(RuntimeContext())
    configureOwnerThreadId.set(-1L)
    configureOwnerAsyncTaskId.set(null)
    asyncTaskCounter.set(0L)
    callbackCallCounter.set(0L)
    contextRef.remove()
