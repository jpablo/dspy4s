package dspy4s.core.contracts

import java.time.Instant

trait LanguageModelRef
trait AdapterRef

final case class SettingKey[A](name: String)

trait Settings:
  def entries: Map[String, Any]
  def get[A](key: SettingKey[A]): Option[A]
  def updated[A](key: SettingKey[A], value: A): Settings
  def remove(key: SettingKey[?]): Settings

final case class SettingsData(entries: Map[String, Any] = Map.empty) extends Settings:
  override def get[A](key: SettingKey[A]): Option[A] = entries.get(key.name).map(_.asInstanceOf[A])

  override def updated[A](key: SettingKey[A], value: A): Settings =
    copy(entries = entries.updated(key.name, value))

  override def remove(key: SettingKey[?]): Settings = copy(entries = entries.removed(key.name))

object SettingKeys:
  val languageModel: SettingKey[LanguageModelRef] = SettingKey("lm")
  val adapter: SettingKey[AdapterRef] = SettingKey("adapter")
  val callbacks: SettingKey[Vector[CallbackHandler]] = SettingKey("callbacks")
  val asyncTaskId: SettingKey[String] = SettingKey("async_task_id")
  val activeCallId: SettingKey[String] = SettingKey("active_call_id")
  val callStack: SettingKey[Vector[String]] = SettingKey("call_stack")
  val numThreads: SettingKey[Int] = SettingKey("num_threads")
  val maxErrors: SettingKey[Int] = SettingKey("max_errors")
  val asyncMaxWorkers: SettingKey[Int] = SettingKey("async_max_workers")
  val disableHistory: SettingKey[Boolean] = SettingKey("disable_history")
  val trackUsage: SettingKey[Boolean] = SettingKey("track_usage")

final case class TraceEntry(
    component: String,
    inputs: Map[String, Any],
    outputs: Map[String, Any],
    timestamp: Instant = Instant.now()
)

final case class HistoryEntry(component: String, payload: Map[String, Any], timestamp: Instant = Instant.now())

trait RuntimeContext:
  def settings: Settings
  def trace: Vector[TraceEntry]
  def history: Vector[HistoryEntry]
  def callbacks: Vector[CallbackHandler]

  def withSettings(updated: Settings): RuntimeContext
  def withCallbacks(updated: Vector[CallbackHandler]): RuntimeContext
  def appendTrace(entry: TraceEntry): RuntimeContext
  def appendHistory(entry: HistoryEntry): RuntimeContext

final case class RuntimeContextData(
    settings: Settings = SettingsData(),
    trace: Vector[TraceEntry] = Vector.empty,
    history: Vector[HistoryEntry] = Vector.empty,
    callbacks: Vector[CallbackHandler] = Vector.empty
) extends RuntimeContext:
  override def withSettings(updated: Settings): RuntimeContext = copy(settings = updated)

  override def withCallbacks(updated: Vector[CallbackHandler]): RuntimeContext = copy(callbacks = updated)

  override def appendTrace(entry: TraceEntry): RuntimeContext = copy(trace = trace :+ entry)

  override def appendHistory(entry: HistoryEntry): RuntimeContext = copy(history = history :+ entry)
