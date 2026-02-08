package dspy4s.core.runtime

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeContextData
import dspy4s.core.contracts.SettingKey
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.Settings
import dspy4s.core.contracts.SettingsData
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.HistoryEntry

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object RuntimeEnvironment:
  private val globalSettingsRef = new AtomicReference[Settings](SettingsData())
  private val configureOwnerThreadId = new AtomicLong(-1L)
  private val configureOwnerAsyncTaskId = new AtomicReference[String | Null](null)
  private val asyncTaskCounter = new AtomicLong(0L)
  private val callbackCallCounter = new AtomicLong(0L)

  private val contextRef = new ThreadLocal[RuntimeContext]:
    override def initialValue(): RuntimeContext = RuntimeContextData()

  private def localContext: RuntimeContext = contextRef.get()

  private def mergedSettings(local: Settings): Settings =
    SettingsData(globalSettingsRef.get().entries ++ local.entries)

  private def ensureConfigureAllowed(): Either[DspyError, Unit] =
    val caller = Thread.currentThread().threadId()
    val owner = configureOwnerThreadId.get()
    if owner == -1L then
      if configureOwnerThreadId.compareAndSet(-1L, caller) then ensureConfigureAllowed()
      else ensureConfigureAllowed()
    else if owner != caller then Left(ConfigurationError("Cannot call RuntimeEnvironment.configure from a non-owner thread"))
    else
      current.settings.get(SettingKeys.asyncTaskId) match
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

  def current: RuntimeContext =
    val context = localContext
    context.withSettings(mergedSettings(context.settings))

  def currentSettings: Settings = current.settings

  def configure(settings: Settings): Either[DspyError, Unit] =
    ensureConfigureAllowed().map { _ =>
      globalSettingsRef.set(settings)
      ()
    }

  def configureEntries(entries: Map[String, Any]): Either[DspyError, Unit] =
    configure(SettingsData(entries))

  def configure(updates: (SettingKey[?], Any)*): Either[DspyError, Unit] =
    val merged = updates.foldLeft(globalSettingsRef.get().entries) { (acc, entry) =>
      val (key, value) = entry
      acc.updated(key.name, value)
    }
    configure(SettingsData(merged))

  def withSetting[A, B](key: SettingKey[A], value: A)(thunk: => B): B =
    withSettings(SettingsData(Map(key.name -> value)))(thunk)

  def withAsyncTask[A](taskId: String)(thunk: => A): A =
    withSetting(SettingKeys.asyncTaskId, taskId)(thunk)

  def withGeneratedAsyncTask[A](prefix: String = "async-task")(thunk: => A): A =
    val taskId = s"$prefix-${asyncTaskCounter.incrementAndGet()}"
    withAsyncTask(taskId)(thunk)

  def nextCallId(prefix: String = "call"): String =
    s"$prefix-${callbackCallCounter.incrementAndGet()}"

  def activeCallStack: Vector[String] =
    current.settings.get(SettingKeys.callStack).getOrElse(Vector.empty)

  def activeCallDepth: Int =
    activeCallStack.size

  def activeCallId: Option[String] =
    activeCallStack.lastOption.orElse(current.settings.get(SettingKeys.activeCallId))

  def withActiveCall[A](callId: String)(thunk: => A): A =
    val previous = localContext
    val previousSettings = previous.settings
    val previousCallStack = previousSettings.get(SettingKeys.callStack).getOrElse(Vector.empty)
    val updatedCallStack = previousCallStack :+ callId
    val updatedSettings = SettingsData(
      previousSettings.entries
        .updated(SettingKeys.activeCallId.name, callId)
        .updated(SettingKeys.callStack.name, updatedCallStack)
    )
    contextRef.set(previous.withSettings(updatedSettings))
    try thunk
    finally
      val after = localContext
      contextRef.set(after.withSettings(previousSettings))

  def withContext[A](context: RuntimeContext)(thunk: => A): A =
    val previous = contextRef.get()
    contextRef.set(context)
    try thunk
    finally contextRef.set(previous)

  def scoped[A](update: RuntimeContext => RuntimeContext)(thunk: => A): A =
    withContext(update(current))(thunk)

  def withSettings[A](settings: Settings)(thunk: => A): A =
    scoped { context =>
      val merged = SettingsData(context.settings.entries ++ settings.entries)
      context.withSettings(merged)
    }(thunk)

  def withCallbacks[A](callbacks: Vector[CallbackHandler])(thunk: => A): A =
    scoped { context =>
      val settings = SettingsData(context.settings.entries.updated(SettingKeys.callbacks.name, callbacks))
      context.withSettings(settings).withCallbacks(callbacks)
    }(thunk)

  def appendTrace(entry: TraceEntry): Unit =
    contextRef.set(localContext.appendTrace(entry))

  def appendHistory(entry: HistoryEntry): Unit =
    val effectiveSettings = current.settings
    val historyDisabled = effectiveSettings.get(SettingKeys.disableHistory).getOrElse(false)
    val maxHistorySize = effectiveSettings.get(SettingKeys.maxHistorySize).getOrElse(10000)
    if !historyDisabled && maxHistorySize > 0 then
      val base = localContext
      val nextHistory = (base.history :+ entry).takeRight(maxHistorySize)
      contextRef.set(base.withHistory(nextHistory))

  def activeCallbacks: Vector[CallbackHandler] =
    current.settings.get(SettingKeys.callbacks).getOrElse(current.callbacks)

  def emit(event: CallbackEvent): Unit =
    val context = current
    given RuntimeContext = context
    activeCallbacks.foreach(_.onEvent(event))

  def resetForTests(): Unit =
    globalSettingsRef.set(SettingsData())
    configureOwnerThreadId.set(-1L)
    configureOwnerAsyncTaskId.set(null)
    asyncTaskCounter.set(0L)
    callbackCallCounter.set(0L)
    contextRef.remove()
