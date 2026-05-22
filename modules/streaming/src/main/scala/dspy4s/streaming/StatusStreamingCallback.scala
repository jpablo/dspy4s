package dspy4s.streaming

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.LmEndEvent
import dspy4s.core.contracts.LmStartEvent
import dspy4s.core.contracts.ModuleEndEvent
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ToolEndEvent
import dspy4s.core.contracts.ToolStartEvent
import dspy4s.streaming.contracts.StatusEvent
import dspy4s.streaming.contracts.StreamEvent

final class StatusStreamingCallback(
    val provider: StatusMessageProvider,
    queue: StreamingQueue[StreamEvent]
) extends CallbackHandler:

  override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
    val message: Option[String] = event match
      case e: ModuleStartEvent => provider.moduleStart(e.moduleName, e.inputs)
      case e: ModuleEndEvent   => provider.moduleEnd(e.moduleName, e.output.fold(identity, identity))
      case e: LmStartEvent     => provider.lmStart(e.modelId, e.request)
      case e: LmEndEvent       => provider.lmEnd(e.modelId, e.response.fold(identity, identity))
      case e: ToolStartEvent if e.toolName != "finish" =>
        provider.toolStart(e.toolName, e.args)
      case e: ToolEndEvent if e.output != Right("Completed.") =>
        provider.toolEnd(e.toolName, e.output.fold(identity, identity))
      case _ => None

    message.foreach(message => queue.offer(StatusEvent(message = message)))
