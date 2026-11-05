package dspy4s.streaming

import dspy4s.adapters.contracts.Adapter
import dspy4s.core.runtime.StreamProgress
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.StreamSink
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.StreamListener
import dspy4s.streaming.contracts.TokenEvent

import java.util.concurrent.ConcurrentHashMap

/** The stream of one `streamify` call, the analog of upstream `settings.send_stream`. `Streamify` puts this session in
  * the context of the program ([[dspy4s.core.contracts.RuntimeContext.streamSink]]). At each model call, [[wrap]] gives
  * a [[StreamingLanguageModelWrapper]] over the model.
  *
  * The wrappers of all the models of the call share this session: the queue, the adapter, the listeners, the indices of
  * the listeners that closed a field, and the flags of [[sawCacheHit]] and [[gaveToken]]. Thus a listener with
  * `allowReuse = false` stops after one field also across two models, and the rule of the final prediction of
  * `Streamify` counts each model.
  *
  * The workers of a parallel program share this session, so its state is safe for concurrent use.
  */
final class StreamSession(
    queue                           : StreamingQueue[StreamEvent],
    private[streaming] val adapter  : Option[Adapter],
    private[streaming] val listeners: Vector[StreamListener]
) extends StreamSink:

  private val firedListeners: java.util.Set[Int] = ConcurrentHashMap.newKeySet[Int]()
  @volatile private var cacheHitSeen             = false
  @volatile private var tokenGiven               = false

  /** A [[StreamingLanguageModelWrapper]] over a [[dspy4s.lm.contracts.StreamingLanguageModel]]. Each other model stays
    * as it is, and it gives no token.
    */
  override def wrap(model: LanguageModel): LanguageModel =
    model match
      case streaming: StreamingLanguageModel => new StreamingLanguageModelWrapper(streaming, this)
      case other                             => other

  /** `true` when a model call of this session got a [[dspy4s.lm.contracts.LmChunk.cached]] chunk. */
  private[streaming] def sawCacheHit: Boolean = cacheHitSeen

  /** Record that a model call of this session got a [[dspy4s.lm.contracts.LmChunk.cached]] chunk. */
  private[streaming] def markCacheHit(): Unit = cacheHitSeen = true

  /** `true` when a wrapper of this session gave a token to the queue. */
  private[streaming] def gaveToken: Boolean = tokenGiven

  /** `true` when no listener narrows the stream, or when a listener matches the predictor `predictName` and one of its
    * `outputFields` (upstream `Predict._should_stream`: each predictor streams when there is no listener).
    */
  private[streaming] def streamsToListener(predictName: String, outputFields: Vector[String]): Boolean =
    listeners.isEmpty || listeners.exists(listener => outputFields.exists(listener.matches(predictName, _)))

  /** Give a token to the listeners, and record the visible output, so the predictor tries no fallback adapter for this
    * call (see [[StreamProgress]]).
    *
    * When the queue refuses the token, the consumer has gone, and this method throws an `InterruptedException`.
    * `NonFatal` rejects this exception, so it passes the catches of the program layers, and the program stops.
    */
  private[streaming] def offerToken(event: TokenEvent): Unit =
    offer(event)
    tokenGiven = true

  /** Give a token of the reasoning of the model to the listeners. It stops the fallback, as each token does. It does
    * not count for [[gaveToken]]: upstream `StreamListener.receive` gives such a chunk before it starts a field, so the
    * stream still has the prediction, which holds the other outputs.
    */
  private[streaming] def offerReasoningToken(event: TokenEvent): Unit = offer(event)

  private def offer(event: TokenEvent): Unit =
    if !queue.offer(event) then throw new InterruptedException("The consumer closed the stream")
    StreamProgress.markEmitted()

  /** `true` when no listener narrows the stream, or when a listener matches the field and can still get a token. A
    * listener with `allowReuse = false` that closed a field cannot.
    */
  private[streaming] def listenerAccepts(predictName: String, fieldName: String): Boolean =
    if listeners.isEmpty then true
    else
      listeners.iterator.zipWithIndex.exists { case (l, i) =>
        l.matches(predictName, fieldName) && (l.allowReuse || !firedListeners.contains(i))
      }

  /** Record that the field closed, for each listener with `allowReuse = false` that matches it. */
  private[streaming] def markListenersFired(predictName: String, fieldName: String): Unit =
    listeners.iterator.zipWithIndex.foreach { case (l, i) =>
      if !l.allowReuse && l.matches(predictName, fieldName) then
        val _ = firedListeners.add(i)
    }
