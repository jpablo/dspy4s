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

  private val contextRef = new ThreadLocal[RuntimeContext]:
    override def initialValue(): RuntimeContext = RuntimeContextData()

  private def localContext: RuntimeContext = contextRef.get()

  private def mergedSettings(local: Settings): Settings =
    SettingsData(globalSettingsRef.get().entries ++ local.entries)

  private def ensureConfigureAllowed(): Either[DspyError, Unit] =
    val caller = Thread.currentThread().threadId()
    val owner = configureOwnerThreadId.get()
    if owner == -1L then
      if configureOwnerThreadId.compareAndSet(-1L, caller) then Right(())
      else ensureConfigureAllowed()
    else if owner == caller then Right(())
    else Left(ConfigurationError("Cannot call RuntimeEnvironment.configure from a non-owner thread"))

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
    contextRef.set(localContext.appendHistory(entry))

  def activeCallbacks: Vector[CallbackHandler] =
    current.settings.get(SettingKeys.callbacks).getOrElse(current.callbacks)

  def emit(event: CallbackEvent): Unit =
    val context = current
    given RuntimeContext = context
    activeCallbacks.foreach(_.onEvent(event))

  def resetForTests(): Unit =
    globalSettingsRef.set(SettingsData())
    configureOwnerThreadId.set(-1L)
    contextRef.remove()
