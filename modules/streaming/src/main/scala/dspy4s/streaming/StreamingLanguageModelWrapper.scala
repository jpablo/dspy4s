package dspy4s.streaming

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FieldChunk
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.ActivePredictContext
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
  * Per-call signature routing: on each `call()` / `stream()` the wrapper reads
  * the innermost [[ActivePredictContext]] entry. That entry carries the active
  * predictor's name (used as `TokenEvent.predictName`) and signature (used to
  * build a fresh [[AdapterStreamingState]] from the configured adapter). This
  * lets a single Streamify wrap a program that internally invokes multiple
  * `DynamicPredict`s with different signatures — each LM call routes to per-field
  * chunks under the correct signature.
  *
  * When there is no active predict context (e.g. the LM is called outside any
  * `DynamicPredict.execute`, or no adapter is configured), the wrapper falls back to
  * raw-token emission with an empty `fieldName`.
  *
  * Listener filtering: when `listeners` is non-empty, only field chunks whose
  * `(predictName, fieldName)` match at least one listener are emitted.
  *
  * `allowReuse = false` semantics: a non-reuse listener fires for the chunks
  * of a single field cycle (up to and including the chunk that carries
  * `isLastChunk = true`) and then goes silent for the remainder of the
  * `streamify` invocation. The mutable set [[firedListeners]] holds the
  * indices that have closed at least one field cycle.
  */
final class StreamingLanguageModelWrapper private (
    delegate: StreamingLanguageModel,
    queue: StreamingQueue[StreamEvent],
    adapter: Option[Adapter],
    listeners: Vector[StreamListener]
) extends StreamingLanguageModel:

  private val firedListeners: mutable.Set[Int] = mutable.Set.empty

  override val id: String = delegate.id
  override val mode: LmMode = delegate.mode

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    val ctx = activeContext()
    val text = new StringBuilder
    var lastUsage: Option[LmUsage] = None
    val toolDeltas = mutable.ArrayBuffer.empty[LmToolCallDelta]
    val chunks = delegate.stream(request)
    try
      chunks.foreach { chunk =>
        emit(ctx, chunk)
        text.append(chunk.text)
        chunk.usage.foreach(usage => lastUsage = Some(usage))
        if chunk.toolCalls.nonEmpty then toolDeltas ++= chunk.toolCalls
      }
      flush(ctx)
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
    val ctx = activeContext()
    delegate.stream(request).map { chunk =>
      emit(ctx, chunk)
      chunk
    }

  /** Bundles per-LM-call state. Resolved once at the start of each `call()`
    * / `stream()` so the same predict context governs every chunk in that
    * invocation, even if nested context pushes happen meanwhile. */
  private final case class CallContext(
      predictName: String,
      state: Option[AdapterStreamingState]
  )

  private def activeContext(): CallContext =
    ActivePredictContext.current match
      case Some(active) =>
        val state = adapter.flatMap(_.streamingState(active.signature))
        CallContext(active.name, state)
      case None =>
        CallContext(predictName = "", state = None)

  private def emit(ctx: CallContext, chunk: LmChunk): Unit =
    ctx.state match
      case Some(s) =>
        if chunk.text.nonEmpty then
          s.receive(chunk.text).foreach(emitFieldChunk(ctx, _, isFinalChunk = chunk.isFinal))
      case None =>
        if chunk.text.nonEmpty && listenerAccepts(ctx.predictName, "") then
          queue.offer(
            TokenEvent(
              predictName = ctx.predictName,
              fieldName = "",
              chunk = chunk.text,
              isLastChunk = chunk.isFinal
            )
          )

  private def flush(ctx: CallContext): Unit =
    ctx.state.foreach { s =>
      s.finish().foreach(emitFieldChunk(ctx, _, isFinalChunk = true))
    }

  private def emitFieldChunk(ctx: CallContext, fc: FieldChunk, isFinalChunk: Boolean): Unit =
    val isLast = fc.isLast || isFinalChunk
    if listenerAccepts(ctx.predictName, fc.fieldName) then
      queue.offer(
        TokenEvent(
          predictName = ctx.predictName,
          fieldName = fc.fieldName,
          chunk = fc.text,
          isLastChunk = isLast
        )
      )
      if isLast then markListenersFired(ctx.predictName, fc.fieldName)

  private def listenerAccepts(predictName: String, fieldName: String): Boolean =
    if listeners.isEmpty then true
    else
      listeners.iterator.zipWithIndex.exists { case (l, i) =>
        l.matches(predictName, fieldName) && (l.allowReuse || !firedListeners.contains(i))
      }

  private def markListenersFired(predictName: String, fieldName: String): Unit =
    listeners.iterator.zipWithIndex.foreach { case (l, i) =>
      if !l.allowReuse && l.matches(predictName, fieldName) then firedListeners += i
    }

object StreamingLanguageModelWrapper:
  def apply(
      delegate: StreamingLanguageModel,
      queue: StreamingQueue[StreamEvent],
      adapter: Option[Adapter] = None,
      listeners: Vector[StreamListener] = Vector.empty
  ): StreamingLanguageModelWrapper =
    new StreamingLanguageModelWrapper(
      delegate = delegate,
      queue = queue,
      adapter = adapter,
      listeners = listeners
    )
