package dspy4s.streaming

import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FieldChunk
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
import dspy4s.streaming.contracts.StreamListener
import dspy4s.streaming.contracts.TokenEvent

import scala.collection.mutable
import scala.util.control.NonFatal

/** Wraps a [[StreamingLanguageModel]] so the runtime can siphon streamed text
  * into a [[StreamingQueue]] while still returning a fully assembled
  * [[LmResponse]] to non-streaming callers.
  *
  * Two emission modes:
  *
  *   - **Raw mode** (no adapter state supplied): every non-empty text chunk
  *     becomes a [[TokenEvent]] with `fieldName = defaultFieldName`. Used when
  *     listeners are not configured.
  *   - **Field-aware mode** (state factory supplied): each `call()` builds a
  *     fresh [[AdapterStreamingState]] that parses the streamed text into
  *     per-field chunks. Emissions are filtered by `listeners` — when
  *     listeners is empty, all field chunks are emitted; otherwise only those
  *     matching a listener's `(predictName, fieldName)` are emitted.
  */
final class StreamingLanguageModelWrapper private (
    delegate: StreamingLanguageModel,
    queue: StreamingQueue[StreamEvent],
    predictName: String,
    defaultFieldName: String,
    stateFactory: () => Option[AdapterStreamingState],
    listeners: Vector[StreamListener]
) extends StreamingLanguageModel:

  override val id: String = delegate.id
  override val mode: LmMode = delegate.mode

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    val chunks = delegate.stream(request)
    val text = new StringBuilder
    var lastUsage: Option[LmUsage] = None
    val toolDeltas = mutable.ArrayBuffer.empty[LmToolCallDelta]
    val state = stateFactory()
    try
      chunks.foreach { chunk =>
        emit(state, chunk)
        text.append(chunk.text)
        chunk.usage.foreach(usage => lastUsage = Some(usage))
        if chunk.toolCalls.nonEmpty then toolDeltas ++= chunk.toolCalls
      }
      flush(state)
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
    val state = stateFactory()
    delegate.stream(request).map { chunk =>
      emit(state, chunk)
      chunk
    }

  private def emit(state: Option[AdapterStreamingState], chunk: LmChunk): Unit =
    state match
      case Some(s) =>
        if chunk.text.nonEmpty then
          s.receive(chunk.text).foreach(emitFieldChunk(_, isFinalChunk = chunk.isFinal))
      case None =>
        if chunk.text.nonEmpty && listenerAccepts(defaultFieldName) then
          queue.offer(
            TokenEvent(
              predictName = predictName,
              fieldName = defaultFieldName,
              chunk = chunk.text,
              isLastChunk = chunk.isFinal
            )
          )

  private def flush(state: Option[AdapterStreamingState]): Unit =
    state.foreach { s =>
      s.finish().foreach(emitFieldChunk(_, isFinalChunk = true))
    }

  private def emitFieldChunk(fc: FieldChunk, isFinalChunk: Boolean): Unit =
    if listenerAccepts(fc.fieldName) then
      queue.offer(
        TokenEvent(
          predictName = predictName,
          fieldName = fc.fieldName,
          chunk = fc.text,
          isLastChunk = fc.isLast || isFinalChunk
        )
      )

  private def listenerAccepts(fieldName: String): Boolean =
    listeners.isEmpty || listeners.exists(_.matches(predictName, fieldName))

object StreamingLanguageModelWrapper:
  def apply(
      delegate: StreamingLanguageModel,
      queue: StreamingQueue[StreamEvent],
      predictName: String = "",
      fieldName: String = "",
      stateFactory: () => Option[AdapterStreamingState] = () => None,
      listeners: Vector[StreamListener] = Vector.empty
  ): StreamingLanguageModelWrapper =
    new StreamingLanguageModelWrapper(
      delegate = delegate,
      queue = queue,
      predictName = predictName,
      defaultFieldName = fieldName,
      stateFactory = stateFactory,
      listeners = listeners
    )
