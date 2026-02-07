package dspy4s.core.contracts

import java.time.Instant
import java.util.UUID

sealed trait CallbackEvent extends Product with Serializable:
  def timestamp: Instant
  def callId: String
  def parentCallId: Option[String]

final case class ModuleStartEvent(
    moduleName: String,
    inputs: Map[String, Any],
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
)
    extends CallbackEvent

final case class ModuleEndEvent(
    moduleName: String,
    output: Either[DspyError, Any],
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
) extends CallbackEvent

final case class LmStartEvent(
    modelId: String,
    request: Map[String, Any],
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
)
    extends CallbackEvent

final case class LmEndEvent(
    modelId: String,
    response: Either[DspyError, Any],
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
) extends CallbackEvent

final case class AdapterStartEvent(
    adapterName: String,
    inputs: Map[String, Any],
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
)
    extends CallbackEvent

final case class AdapterEndEvent(
    adapterName: String,
    output: Either[DspyError, Any],
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
) extends CallbackEvent

final case class ToolStartEvent(
    toolName: String,
    args: Map[String, Any],
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
) extends CallbackEvent

final case class ToolEndEvent(
    toolName: String,
    output: Either[DspyError, Any],
    callId: String = UUID.randomUUID().toString,
    parentCallId: Option[String] = None,
    timestamp: Instant = Instant.now()
) extends CallbackEvent

trait CallbackHandler:
  def onEvent(event: CallbackEvent)(using RuntimeContext): Unit

object CallbackHandler:
  val noop: CallbackHandler = new CallbackHandler:
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = ()
