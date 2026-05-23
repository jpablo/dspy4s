package dspy4s.streaming

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.lm.contracts.LmUsage
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.lm.runtime.ToolCallAssembler
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.TokenEvent

import scala.collection.mutable
import scala.util.control.NonFatal

final class StreamingLanguageModelWrapper private (
    delegate: StreamingLanguageModel,
    sink: LmChunk => Unit
) extends StreamingLanguageModel:

  override val id: String = delegate.id
  override val mode: LmMode = delegate.mode

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    val chunks = delegate.stream(request)
    val text = new StringBuilder
    var lastUsage: Option[LmUsage] = None
    val toolDeltas = mutable.ArrayBuffer.empty[LmToolCallDelta]
    try
      chunks.foreach { chunk =>
        sink(chunk)
        text.append(chunk.text)
        chunk.usage.foreach(usage => lastUsage = Some(usage))
        if chunk.toolCalls.nonEmpty then toolDeltas ++= chunk.toolCalls
      }
      val toolCalls = ToolCallAssembler.assemble(toolDeltas.toVector)
      Right(
        LmResponse(
          outputs = Vector(LmOutput(text = text.toString, toolCalls = toolCalls)),
          usage = lastUsage
        )
      )
    catch
      case NonFatal(error) =>
        Left(RuntimeError("streaming_lm", Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))

  override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
    val chunks = delegate.stream(request)
    chunks.map { chunk =>
      sink(chunk)
      chunk
    }

object StreamingLanguageModelWrapper:
  def apply(
      delegate: StreamingLanguageModel,
      queue: StreamingQueue[StreamEvent],
      predictName: String = "",
      fieldName: String = ""
  ): StreamingLanguageModelWrapper =
    val sink: LmChunk => Unit = chunk =>
      if chunk.text.nonEmpty then
        queue.offer(
          TokenEvent(
            predictName = predictName,
            fieldName = fieldName,
            chunk = chunk.text,
            isLastChunk = chunk.isFinal
          )
        )
    new StreamingLanguageModelWrapper(delegate, sink)
