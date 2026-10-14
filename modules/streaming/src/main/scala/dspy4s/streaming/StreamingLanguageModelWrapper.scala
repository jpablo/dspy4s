package dspy4s.streaming

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FieldChunk
import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.ActivePredictContext
import dspy4s.core.runtime.StreamProgress
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.lm.runtime.ToolCallAssembler
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.StreamListener
import dspy4s.streaming.contracts.TokenEvent
import zio.blocks.schema.DynamicValue

import scala.collection.mutable
import scala.util.control.NonFatal

/** Wraps a [[StreamingLanguageModel]] so the runtime can siphon streamed text into a [[StreamingQueue]] while still
  * returning a fully assembled [[LmResponse]] to non-streaming callers.
  *
  * Per-call signature routing: on each `call()` / `stream()` the wrapper reads the innermost [[ActivePredictContext]]
  * entry. That entry carries the active predictor's name (used as `TokenEvent.predictName`) and signature (used to
  * build a fresh [[AdapterStreamingState]] from the configured adapter). This lets a single Streamify wrap a program
  * that internally invokes multiple prediction leaves with different signatures, whether `Predict`s or
  * `DynamicPredict`s — each LM call routes to per-field chunks under the correct signature.
  *
  * When there is no active predict context (e.g. the LM is called outside any `PredictEngine.execute`, or no adapter is
  * configured), the wrapper falls back to raw-token emission with an empty `fieldName`.
  *
  * Listener filtering: when `listeners` is non-empty, only field chunks whose `(predictName, fieldName)` match at least
  * one listener are emitted.
  *
  * `allowReuse = false` semantics: a non-reuse listener fires for the chunks of a single field cycle (up to and
  * including the chunk that carries `isLastChunk = true`) and then goes silent for the remainder of the `streamify`
  * invocation. The mutable set [[firedListeners]] holds the indices that have closed at least one field cycle.
  */
final class StreamingLanguageModelWrapper private (
    delegate : StreamingLanguageModel,
    queue    : StreamingQueue[StreamEvent],
    adapter  : Option[Adapter],
    listeners: Vector[StreamListener]
) extends StreamingLanguageModel:

  private val firedListeners: mutable.Set[Int] = mutable.Set.empty

  override val id: String   = delegate.id
  override val mode: LmMode = delegate.mode
  // This wrapper is the active LM during `streamify`, so it must not hide what the delegate supports (adapters
  // consult the flags to decide e.g. whether to emit `response_format` or native tools) or its default options.
  override def supportsFunctionCalling: Boolean    = delegate.supportsFunctionCalling
  override def supportsResponseSchema: Boolean     = delegate.supportsResponseSchema
  override def supportsReasoning: Boolean          = delegate.supportsReasoning
  override def defaultOptions: DynamicValue.Record = delegate.defaultOptions

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    val ctx                        = activeContext()
    val text                       = new StringBuilder
    var lastUsage: Option[LmUsage] = None
    val toolDeltas                 = mutable.ArrayBuffer.empty[LmToolCallDelta]
    val chunks                     = delegate.stream(request)
    try
      // Providers reify transport/HTTP failures into a terminal chunk with `finishReason = "error"`, the
      // message under `raw`, and the typed failure in `error` (see OpenAiLanguageModel.stream). Surface that as
      // a Left instead of folding it into a successful empty response the adapter would then misreport as a
      // parse error.
      var streamError: Option[DspyError] = None
      chunks.foreach { chunk =>
        failureOf(chunk) match
          case Some(failure) => streamError = Some(failure)
          case None          =>
            emit(ctx, chunk)
            text.append(chunk.text)
            chunk.usage.foreach(usage => lastUsage = Some(usage))
            if chunk.toolCalls.nonEmpty then toolDeltas ++= chunk.toolCalls
      }
      streamError match
        case Some(failure) => Left(failure)
        case None          =>
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
    finally
      // The delegate's iterator self-closes only on exhaustion; an exception mid-stream (from emit/receive)
      // would otherwise abandon the live HTTP SSE connection until GC finalization.
      chunks match
        case closable: ClosableIterator[?] => closable.close()
        case _                             => ()

  override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
    val ctx = activeContext()
    delegate.stream(request).map { chunk =>
      emit(ctx, chunk)
      chunk
    }

  /** Bundles per-LM-call state. Resolved once at the start of each `call()` / `stream()` so the same predict context
    * governs every chunk in that invocation, even if nested context pushes happen meanwhile.
    */
  private final case class CallContext(
      predictName: String,
      state      : Option[AdapterStreamingState]
  )

  /** The failure that a chunk reports, if any. The typed `error` of the chunk wins, so the caller gets the same
    * `DspyError` that the provider produced (a `ContextWindowExceededError` stays one). A chunk that has only
    * `finishReason = "error"` gives a `RuntimeError("streaming_lm", ...)` with the message from `raw`.
    */
  private def failureOf(chunk: LmChunk): Option[DspyError] =
    chunk.error.orElse(
      Option.when(chunk.finishReason.contains("error"))(RuntimeError("streaming_lm", errorMessage(chunk.raw)))
    )

  /** Extract the provider's error message from a reified error chunk's `raw` payload (`Map("error" -> message)` by
    * convention).
    */
  private def errorMessage(raw: Option[Any]): String =
    raw match
      case Some(m: Map[?, ?]) =>
        m.collectFirst { case (k, v) if String.valueOf(k) == "error" => String.valueOf(v) }.getOrElse(m.toString)
      case Some(other) => other.toString
      case None        => "LM stream terminated with an error"

  private def activeContext(): CallContext =
    ActivePredictContext.current match
      case Some(active) =>
        val state = adapter.flatMap(_.streamingState(active.layout))
        CallContext(active.name, state)
      case None => CallContext(predictName = "", state = None)

  private def emit(ctx: CallContext, chunk: LmChunk): Unit =
    ctx.state match
      case Some(s) => if chunk.text.nonEmpty then
          s.receive(chunk.text).foreach(emitFieldChunk(ctx, _, isFinalChunk = chunk.isFinal))
      case None => if chunk.text.nonEmpty && listenerAccepts(ctx.predictName, "") then
          offerToken(
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
      offerToken(
        TokenEvent(
          predictName = ctx.predictName,
          fieldName = fc.fieldName,
          chunk = fc.text,
          isLastChunk = isLast
        )
      )
      if isLast then markListenersFired(ctx.predictName, fc.fieldName)

  /** Give a token to the listeners, and record the visible output, so the predictor tries no fallback adapter for this
    * call (see [[StreamProgress]]).
    */
  private def offerToken(event: TokenEvent): Unit =
    val _ = queue.offer(event)
    StreamProgress.markEmitted()

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
      delegate : StreamingLanguageModel,
      queue    : StreamingQueue[StreamEvent],
      adapter  : Option[Adapter]        = None,
      listeners: Vector[StreamListener] = Vector.empty
  ): StreamingLanguageModelWrapper =
    new StreamingLanguageModelWrapper(
      delegate = delegate,
      queue = queue,
      adapter = adapter,
      listeners = listeners
    )
