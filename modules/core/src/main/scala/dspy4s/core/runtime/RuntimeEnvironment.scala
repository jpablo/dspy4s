package dspy4s.core.runtime

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object RuntimeEnvironment:
  private val globalRef = new AtomicReference[RuntimeContext](RuntimeContext())
  private val configureOwnerThreadId = new AtomicLong(-1L)
  private val configureOwnerAsyncTaskId = new AtomicReference[String | Null](null)
  private val asyncTaskCounter = new AtomicLong(0L)
  private val callbackCallCounter = new AtomicLong(0L)

  private val contextRef = new ThreadLocal[RuntimeContext]:
    override def initialValue(): RuntimeContext = RuntimeContext()

  private def localContext: RuntimeContext = contextRef.get()

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

  def withSetting[A](update: RuntimeContext => RuntimeContext)(thunk: => A): A =
    withContext(update(current))(thunk)

  def withAsyncTask[A](taskId: String)(thunk: => A): A =
    withSetting(_.copy(asyncTaskId = Some(taskId)))(thunk)

  def withGeneratedAsyncTask[A](prefix: String = "async-task")(thunk: => A): A =
    val taskId = s"$prefix-${asyncTaskCounter.incrementAndGet()}"
    withAsyncTask(taskId)(thunk)

  def nextCallId(prefix: String = "call"): String =
    s"$prefix-${callbackCallCounter.incrementAndGet()}"

  def activeCallStack: Vector[String] = current.callStack

  def activeCallDepth: Int = activeCallStack.size

  def activeCallId: Option[String] = current.callStack.lastOption.orElse(current.activeCallId)

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

  def withContext[A](context: RuntimeContext)(thunk: => A): A =
    val previous = contextRef.get()
    contextRef.set(context)
    try thunk
    finally contextRef.set(previous)

  def withSettings[A](settings: RuntimeContext)(thunk: => A): A =
    withContext(settings.fillFrom(current))(thunk)

  def withCallbacks[A](callbacks: Vector[CallbackHandler])(thunk: => A): A =
    withContext(current.copy(callbacks = callbacks))(thunk)

  def appendTrace(entry: TraceEntry): Unit =
    contextRef.set(localContext.appendTrace(entry))

  def appendHistory(entry: HistoryEntry): Unit =
    val effective = current
    val historyDisabled = effective.disableHistory.getOrElse(false)
    val cap = effective.maxHistorySize.getOrElse(10000)
    if !historyDisabled && cap > 0 then
      val base = localContext
      val nextHistory = (base.history :+ entry).takeRight(cap)
      contextRef.set(base.withHistory(nextHistory))

  def activeCallbacks: Vector[CallbackHandler] = current.callbacks

  def emit(event: CallbackEvent): Unit =
    val context = current
    given RuntimeContext = context
    activeCallbacks.foreach(_.onEvent(event))

  def resetForTests(): Unit =
    globalRef.set(RuntimeContext())
    configureOwnerThreadId.set(-1L)
    configureOwnerAsyncTaskId.set(null)
    asyncTaskCounter.set(0L)
    callbackCallCounter.set(0L)
    contextRef.remove()
