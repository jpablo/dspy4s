package dspy4s.streaming

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterStreamingState
import dspy4s.adapters.contracts.FieldChunk
import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.ActivePredictContext
import dspy4s.core.runtime.StreamProgress
import dspy4s.lm.contracts.ForwardingLanguageModel
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.lm.runtime.StreamAccumulator
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.StreamListener
import dspy4s.streaming.contracts.TokenEvent

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
    val delegate: StreamingLanguageModel,
    queue       : StreamingQueue[StreamEvent],
    adapter     : Option[Adapter],
    listeners   : Vector[StreamListener]
) extends StreamingLanguageModel
    with ForwardingLanguageModel:

  private val firedListeners: mutable.Set[Int] = mutable.Set.empty

  override val id: String   = delegate.id
  override val mode: LmMode = delegate.mode
  // This wrapper is the active LM during `streamify`, so it must not hide what the delegate supports (adapters
  // consult the flags to decide e.g. whether to emit `response_format` or native tools, and `Predict` reads
  // `supportsDecisionRequests` to choose a decision request), its default options, or its cache identity.
  // ForwardingLanguageModel forwards them.

  /** Does nothing. The delegate is the model of the configuration, and this wrapper does not own it: the wrapper is the
    * active model for one `streamify` call only. Thus the wrapper does not close the delegate.
    */
  override def close(): Unit = ()

  /** `true` when no listener narrows the stream, or when a listener matches the predictor `predictName` and one of its
    * `outputFields` (upstream `Predict._should_stream`: each predictor streams when there is no listener).
    */
  override def streamsToListener(predictName: String, outputFields: Vector[String]): Boolean =
    listeners.isEmpty || listeners.exists(listener => outputFields.exists(listener.matches(predictName, _)))

  /** Stream the call, give the tokens to the listeners, and assemble the response. A [[LmChunk.cached]] chunk gives its
    * response as is, and no token goes to a listener (upstream: a cache hit sends no stream chunk). The output of the
    * response is the output of a [[dspy4s.lm.runtime.StreamAccumulator]] in the mode of the delegate, as in
    * [[ManagedLanguageModel]].
    *
    * A non-fatal exception, for example of the delegate when it opens or reads the stream, gives an
    * [[dspy4s.core.contracts.LmUnexpectedError]], as in [[ManagedLanguageModel]] (upstream
    * `error_boundary(..., unexpected=True)` in `dspy/clients/errors.py`). A fatal error goes up to the caller.
    */
  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    val ctx                        = activeContext()
    val parts                      = StreamAccumulator(delegate.mode)
    var lastUsage: Option[LmUsage] = None
    var chunks: Iterator[LmChunk]  = Iterator.empty
    try
      chunks = delegate.stream(request)
      // Providers reify transport/HTTP failures into a terminal chunk with `finishReason = "error"`, the
      // message under `raw`, and the typed failure in `error` (see OpenAiLanguageModel.stream). Surface that as
      // a Left instead of folding it into a successful empty response the adapter would then misreport as a
      // parse error.
      var streamError: Option[DspyError] = None
      // The chunk of a cache hit carries the whole response. It is the only chunk, and it gives no token.
      var cached: Option[LmResponse] = None
      chunks.foreach { chunk =>
        chunk.cachedResponse match
          case Some(response) => cached = Some(response)
          case None           => chunk.reportedFailure match
              case Some(failure) => streamError = Some(failure)
              case None          =>
                emit(ctx, chunk)
                parts.push(chunk)
                chunk.usage.foreach(usage => lastUsage = Some(usage))
      }
      (cached, streamError) match
        case (Some(response), _) => Right(response)
        case (_, Some(failure))  => Left(failure)
        case (None, None)        =>
          flush(ctx)
          Right(LmResponse(outputs = Vector(parts.output), usage = lastUsage))
    catch
      case NonFatal(error) => Left(ManagedLanguageModel.unexpected(error, request))
    finally
      // The delegate's iterator self-closes only on exhaustion; an exception mid-stream (from emit/receive)
      // would otherwise abandon the live HTTP SSE connection until GC finalization. A non-fatal exception of the
      // close does not change the result, as in ManagedLanguageModel.
      chunks match
        case closable: ClosableIterator[?] =>
          try closable.close()
          catch case NonFatal(_) => ()
        case _ => ()

  /** The chunks of the delegate. Each chunk goes to the listeners, except a [[LmChunk.cached]] chunk and a failure
    * chunk, as in [[call]]. At the end of the stream, the wrapper flushes the stream state of the adapter, as [[call]]
    * does: the listeners get the held end of a field and the value of the single-output fallback (upstream
    * `StreamListener.flush`). A stream with a cached chunk or a failure chunk gets no flush, as in [[call]].
    *
    * The flush occurs at the `hasNext` that gives `false`, or in the `next` that takes the last chunk. While a flush is
    * pending, `next` asks the delegate if it has one more chunk before `next` gives its chunk, so a caller that uses
    * only `next` also gets the flush. The listeners get the tokens of a chunk before that check. The caller gets the
    * chunk when the delegate has the next chunk or ends. With no stream state, no flush is pending, and `next` does no
    * check.
    *
    * A non-fatal exception of the delegate, when it opens the stream or during a read, gives a failure chunk with an
    * [[dspy4s.core.contracts.LmUnexpectedError]], the last chunk of the stream, as [[call]] gives a `Left` (upstream
    * `error_boundary(..., unexpected=True)`). The wrapper then closes the stream of the delegate. A fatal error goes up
    * to the caller, and the stream of the delegate stays open until the caller closes the result.
    *
    * A close of the result closes the stream of the delegate, so a caller that stops early releases the connection. A
    * close before the end of the stream gives no flush. A non-fatal exception of the close of the delegate does not go
    * to the caller, as in [[ManagedLanguageModel]]. After a close, the result has no more chunks.
    */
  override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
    val ctx    = activeContext()
    val opened =
      try delegate.stream(request)
      catch case NonFatal(error) => Iterator.single(LmChunk.failure(ManagedLanguageModel.unexpected(error, request)))
    new ClosableIterator[LmChunk]:
      private var chunks: Iterator[LmChunk] = opened
      private var flushPending              = ctx.state.isDefined

      override def hasNext: Boolean =
        val more = guarded(chunks.hasNext) { failure =>
          chunks = Iterator.single(failure)
          true
        }
        if !more && flushPending then
          flushPending = false
          flush(ctx)
        more

      override def next(): LmChunk =
        if !hasNext then throw new NoSuchElementException("The stream of the wrapper has no more chunks")
        val chunk = guarded(chunks.next())(identity)
        if chunk.cachedResponse.isDefined || chunk.reportedFailure.isDefined then flushPending = false
        else
          emit(ctx, chunk)
          // The check of the end of the stream: at the end, `hasNext` flushes the stream state.
          if flushPending then
            val _ = hasNext
        chunk

      override def close(): Unit =
        flushPending = false
        release()

      /** The value of `read`, a read of the delegate. A non-fatal exception closes the stream of the delegate, and
        * gives `recover` of the failure chunk with an [[dspy4s.core.contracts.LmUnexpectedError]].
        */
      private def guarded[A](read: => A)(recover: LmChunk => A): A =
        try read
        catch
          case NonFatal(error) =>
            release()
            recover(LmChunk.failure(ManagedLanguageModel.unexpected(error, request)))

      /** Close the stream of the delegate. A non-fatal exception of the close does not go to the caller. After this
        * close, the delegate gives no more chunks.
        */
      private def release(): Unit =
        chunks match
          case closable: ClosableIterator[?] =>
            try closable.close()
            catch case NonFatal(_) => ()
          case _ => ()
        chunks = Iterator.empty

  /** Bundles per-LM-call state. Resolved once at the start of each `call()` / `stream()` so the same predict context
    * governs every chunk in that invocation, even if nested context pushes happen meanwhile.
    */
  private final case class CallContext(
      predictName: String,
      state      : Option[AdapterStreamingState]
  )

  private def activeContext()(using RuntimeContext): CallContext =
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
