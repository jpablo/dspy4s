package dspy4s.core.contracts

import java.time.Instant

trait LanguageModelRef
trait AdapterRef

final case class SettingKey[A](name: String)

final case class Settings(entries: Map[String, Any] = Map.empty):
  def get[A](key: SettingKey[A]): Option[A] = entries.get(key.name).map(_.asInstanceOf[A])

  def updated[A](key: SettingKey[A], value: A): Settings =
    copy(entries = entries.updated(key.name, value))

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
