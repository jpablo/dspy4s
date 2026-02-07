package dspy4s.core.contracts

import java.time.Instant

sealed trait CallbackEvent extends Product with Serializable:
  def timestamp: Instant

final case class ModuleStartEvent(moduleName: String, inputs: Map[String, Any], timestamp: Instant = Instant.now())
    extends CallbackEvent

final case class ModuleEndEvent(
    moduleName: String,
    output: Either[DspyError, Any],
    timestamp: Instant = Instant.now()
) extends CallbackEvent

final case class LmStartEvent(modelId: String, request: Map[String, Any], timestamp: Instant = Instant.now())
    extends CallbackEvent

final case class LmEndEvent(
    modelId: String,
    response: Either[DspyError, Any],
    timestamp: Instant = Instant.now()
) extends CallbackEvent

final case class AdapterStartEvent(adapterName: String, inputs: Map[String, Any], timestamp: Instant = Instant.now())
    extends CallbackEvent

final case class AdapterEndEvent(
    adapterName: String,
    output: Either[DspyError, Any],
    timestamp: Instant = Instant.now()
) extends CallbackEvent

trait CallbackHandler:
  def onEvent(event: CallbackEvent)(using RuntimeContext): Unit

object CallbackHandler:
  val noop: CallbackHandler = new CallbackHandler:
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = ()
