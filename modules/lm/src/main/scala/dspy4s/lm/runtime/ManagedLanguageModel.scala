package dspy4s.lm.runtime

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.LmError
import dspy4s.core.contracts.LmErrorDetails
import dspy4s.core.contracts.LmStreamAssemblyError
import dspy4s.core.contracts.LmUnexpectedError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.runtime.UsageScope
import dspy4s.lm.contracts.CacheKey
import dspy4s.lm.contracts.ForwardingLanguageModel
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmCache
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmCost
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmPrice
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.core.contracts.LmUsage
import dspy4s.lm.contracts.RetryPolicy
import dspy4s.lm.contracts.StreamingLanguageModel
import zio.blocks.schema.DynamicValue

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.control.NonFatal
import java.util.concurrent.ThreadLocalRandom

/** The retry policies of [[ManagedLanguageModel]].
  *
  * Two rules apply to each policy that retries:
  *
  *   - The default of the `retryOn` parameter is [[dspy4s.core.contracts.LmError.isRetryable]]: only a transient LM
  *     error (a rate limit, a timeout, a server failure, a transport failure) gets a new attempt. An authentication
  *     failure, a billing failure, a context-window failure, and each error that is not an LM error stop the call
  *     immediately. A caller can pass a different predicate.
  *   - When the error carries the retry hint of the provider (`details.retryAfter`, from the `Retry-After` header), the
  *     delay before the next attempt is that hint, limited to `maxHintMillis` (60 seconds by default). Upstream
  *     `_delay` (`dspy/clients/execution.py`) uses the hint with no limit. The port sets a limit, so that a hint of one
  *     day does not stop the thread for one day.
  */
object RetryPolicies:
  val never: RetryPolicy = new RetryPolicy:
    override def shouldRetry(attempt: Int, error: DspyError): Boolean = false

  /** Retries a failure that `retryOn` accepts, `maxRetries` times at most. The delay is the retry hint of the provider
    * when the error carries one, limited to `maxHintMillis`. Without a hint, the next attempt starts immediately.
    */
  def maxRetries(
      maxRetries   : RetryCount,
      retryOn      : DspyError => Boolean = LmError.isRetryable,
      maxHintMillis: RetryDelayMillis     = DefaultMaxHintMillis
  ): RetryPolicy =
    new RetryPolicy:
      override def shouldRetry(attempt: Int, error: DspyError): Boolean =
        attempt < maxRetries && retryOn(error)

      override def delayBeforeNextAttemptMillis(attempt: Int, error: DspyError): Long =
        retryHintMillis(error, maxHintMillis).getOrElse(0L)

  /** Like [[maxRetries]], and retries only an error whose `code` is in `retryableCodes`. The codes of the LM errors are
    * the upstream codes, for example `rate_limit`, `timeout`, `server`, and `transport`.
    */
  def maxRetriesOnCodes(
      maxRetries    : RetryCount,
      retryableCodes: Set[String],
      maxHintMillis : RetryDelayMillis = DefaultMaxHintMillis
  ): RetryPolicy =
    this.maxRetries(maxRetries, error => retryableCodes.contains(error.code), maxHintMillis)

  /** Retries a failure that `retryOn` accepts, `maxRetries` times at most, with a delay that grows.
    *
    * When the error carries the retry hint of the provider, the delay is the hint, limited to `maxHintMillis`: the hint
    * gets no jitter, and `maxDelayMillis` does not limit it. Without a hint, the delay is
    * `baseDelayMillis * 2^attempt`, limited to `maxDelayMillis`, plus a random part of `jitterFactor` of that delay at
    * most. The jitter does not move the delay above `maxDelayMillis`.
    */
  def exponentialBackoff(
      maxRetries     : RetryCount,
      baseDelayMillis: RetryDelayMillis     = RetryDelayMillis(200L),
      maxDelayMillis : RetryDelayMillis     = RetryDelayMillis(4000L),
      jitterFactor   : JitterFactor         = JitterFactor(0.0),
      retryOn        : DspyError => Boolean = LmError.isRetryable,
      maxHintMillis  : RetryDelayMillis     = DefaultMaxHintMillis
  ): RetryPolicy =
    require(maxDelayMillis >= baseDelayMillis, "maxDelayMillis must be >= baseDelayMillis")

    new RetryPolicy:
      override def shouldRetry(attempt: Int, error: DspyError): Boolean =
        attempt < maxRetries && retryOn(error)

      override def delayBeforeNextAttemptMillis(attempt: Int, error: DspyError): Long =
        retryHintMillis(error, maxHintMillis).getOrElse(computedDelayMillis(attempt))

      private def computedDelayMillis(attempt: Int): Long =
        val exponent = if attempt <= 0 then 0 else attempt
        val factor   = 1L << math.min(exponent, 20)
        val bounded  =
          if baseDelayMillis == 0L then 0L
          else if baseDelayMillis > maxDelayMillis / factor then maxDelayMillis
          else baseDelayMillis * factor
        if jitterFactor == 0.0 then bounded
        else
          val headroom  = maxDelayMillis - bounded
          val maxJitter = math.min(math.max((bounded.toDouble * jitterFactor).toLong, 0L), headroom)
          val jitter    = if maxJitter == 0L then 0L else ThreadLocalRandom.current().nextLong(maxJitter + 1L)
          bounded + jitter

  /** The default upper limit of the retry hint of the provider: 60 seconds. */
  val DefaultMaxHintMillis: RetryDelayMillis = RetryDelayMillis(60000L)

  /** The retry hint of the provider in milliseconds, limited to `maxHintMillis`, if `error` carries one. */
  private def retryHintMillis(error: DspyError, maxHintMillis: RetryDelayMillis): Option[Long] =
    LmError.retryAfter(error).map(hint => math.min(hint.toMillis, maxHintMillis))

/** The usage tracker of the user: the usage of the model calls in a block of code, for each model name. The class is
  * [[dspy4s.core.runtime.UsageTracker]].
  */
type UsageTracker = dspy4s.core.runtime.UsageTracker

/** The usage trackers of the user (upstream `dspy.track_usage`, `dspy/utils/usage_tracker.py`). A tracker is a usage
  * scope ([[dspy4s.core.runtime.UsageScope]]): it gets the usage of each model call in the block, for each model, also
  * on the worker threads that the block starts (`ParallelExecutor`, the futures, the producer of `streamify`).
  *
  * The record point is `LanguageModel.callAndRecordUsage`. `Predict`, `TwoStepAdapter`, and the sub-LM tools of `RLM`
  * use it, so each model call of a program counts, also for a model with no managed wrapper. A direct `call` or
  * `stream` of a model counts in no tracker, also for a [[ManagedLanguageModel]]. A cache hit adds nothing. A failed
  * call adds the usage that its LM error carries, if any. The trackers get nothing when the context of the call has
  * `trackUsage = Some(false)`.
  *
  * The LM usage of a program result comes from the same records: it is the total of the usage scope of the program
  * call. Thus a tracker around one program call has the total of the result, when `trackUsage` is not `false`.
  */
object UsageTracking:

  def withTracker[A](tracker: UsageTracker)(thunk: => A): A =
    UsageScope.track(tracker)(thunk)

  def withNewTracker[A](thunk: UsageTracker => A): A =
    val tracker = new UsageTracker
    withTracker(tracker)(thunk(tracker))

/** A model with a cache, retries, and the history over `delegate`. The capability flags, the default options, the cache
  * identity, and `close` come from the delegate ([[ForwardingLanguageModel]]). A close closes the delegate, and the
  * cache stays.
  *
  * The model records no usage. The one record point of the usage is
  * [[dspy4s.lm.contracts.LanguageModel.callAndRecordUsage]], which the framework uses for each model call of a program.
  * Thus a direct `call` or `stream` of this model counts in no sink: not in a user tracker ([[UsageTracking]]), and not
  * in the usage of a program result. The response and the chunks still carry the usage of the delegate.
  *
  * The usage of a call is the total of each attempt that reported a usage (upstream `combine(results)` and
  * `_account_failed_call(results)` in `dspy/clients/execution.py`): the usage of each failed attempt
  * ([[dspy4s.core.contracts.LmError.usage]] of its error) plus the usage of the last attempt. A success gets the total
  * as the usage of its response, so the history cost, the cache store, and the record point see the total. A failure
  * gets the total on its error ([[dspy4s.core.contracts.LmErrorDetails.usage]]) when the total is not empty and the
  * error is an LM error. An error that is not an LM error stays as it is. A cache hit has no usage.
  *
  * A delegate that throws a non-fatal exception gives an [[dspy4s.core.contracts.LmUnexpectedError]] with the message
  * of the exception, on the call path and on the stream path (upstream `error_boundary(..., unexpected=True)` in
  * `dspy/clients/errors.py`). The retry policy decides on that error as on each other error, and the call gets the
  * failure entry. A fatal error, for example an `InterruptedException`, goes up to the caller.
  *
  * The delegate can be a second managed model, the inner model. One call of this model then gives one history entry:
  * the inner model writes the history entry of each call that it gets, and this model writes no history entry for these
  * calls. The entry has the cost estimate at the price of the inner model. This model still stores a response of the
  * inner model that is not a cache hit. A cache hit of the inner model is a hit of this model too, and this model does
  * not store it. A cache hit of this model does not call the inner model, so this model writes the history entry of
  * that hit. When this model retries a failure, the inner model writes a failure entry for each attempt.
  *
  * The model computes the cache key of a call one time, with `cacheKey`. A request with no cache key, for example one
  * with a value nested too deep for the stack, is a miss, and the cache stores nothing for it. The delegate thus gets
  * each call of that request (upstream `Cache.get` and `Cache.put` go on with no cache, `dspy/clients/cache.py`).
  */
final case class ManagedLanguageModel(
    delegate   : LanguageModel,
    cache      : Option[LmCache] = None,
    retryPolicy: RetryPolicy     = RetryPolicies.never,
    sleep      : Long => Unit    = ManagedLanguageModel.defaultSleep,
    /** The price of the tokens of the delegate. The history entry of each call then has the cost estimate
      * ([[dspy4s.lm.contracts.LmCost]]). With `None`, the cost is unknown (decision L10).
      */
    price: Option[LmPrice] = None,
    /** The cache key of a request. The model gives it the request with the merged options, the mode of the delegate,
      * and the cache identity of the delegate. A `Left` gives no key. A different function changes the data that the
      * cache keys on (upstream: a `Cache` subclass that overrides `cache_key`).
      */
    cacheKey: (LmRequest, LmMode, DynamicValue.Record) => Either[DspyError, CacheKey] = RequestHash.forRequest
) extends StreamingLanguageModel
    with ForwardingLanguageModel:
  override val id: String   = delegate.id
  override val mode: LmMode = delegate.mode

  /** The component of the history entries of this model. */
  private val historyComponent: String = s"lm:$id"

  /** `false` when the delegate is a managed model: that model writes the history entry of each call that it gets, so
    * this model does not do it again.
    */
  private val recordsDelegateCalls: Boolean = delegate match
    case _: ManagedLanguageModel => false
    case _                       => true

  /** A streamed call with the rules of [[call]] (upstream `execute`: one call path for streamed calls).
    *
    *   - Cache: a hit gives one [[LmChunk.cached]] chunk, and the delegate gets no request. The cache stores the
    *     response of a complete stream only: not a failure, and not a stream that is not complete (see [[LmChunk]]).
    *     The key is the key of [[call]].
    *   - Stream guard: a stream of the delegate that ends with no chunk that has a finish reason gets a last
    *     [[LmChunk.failure]] chunk with an [[dspy4s.core.contracts.LmStreamAssemblyError]].
    *   - Retry: when the stream fails before its first chunk with text, reasoning, a citation, or a tool call, the
    *     retry policy decides on a new attempt, as for [[call]]. The caller gets no chunk of an attempt that failed.
    *     After the first such chunk, a failure ends the stream, with no retry (upstream `_retryable`: no replay after
    *     visible output; a thinking delta and a citation delta are visible output too).
    *   - History: one entry for each streamed call, when the stream ends: the entry of [[call]] for a success or a
    *     cache hit, and the failure entry of [[call]] for a failure.
    *   - Usage: this model records no usage. The chunks of the delegate carry the usage to the caller. The usage of a
    *     failed attempt is the usage of its error, else the usage of its chunks. A usage chunk of the last attempt
    *     carries the usage of the call, the total of the rules of [[call]], and so does the stored response. When the
    *     last attempt has no usage chunk, one more chunk with the usage of the failed attempts comes at the end of a
    *     successful stream. The error of the failure chunk has the usage of the call. A cache hit has no usage.
    *   - Output: the stored response has the output of [[StreamAccumulator]] in the mode of the delegate.
    *
    * A delegate that cannot stream gives one terminal chunk assembled from [[call]], or a reified
    * `finishReason = "error"` chunk on failure. The chunk carries the text, the reasoning, the citations, the tool
    * calls, and the answers of the first output. Each tool call is one complete delta, so `ToolCallAssembler.assemble`
    * gives the call back with its id.
    */
  override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
    delegate match
      case streaming: StreamingLanguageModel =>
        val key = keyOf(request)
        cacheHit(request, key) match
          case Some(cached) => Iterator.single(LmChunk.cached(cached))
          case None         => ManagedStream(streaming, request, key)
      case _ => call(request) match
          case Right(response) if response.cacheHit => Iterator.single(LmChunk.cached(response))
          case Right(response)                      => Iterator.single(LmChunk.fromResponse(response).copy(usage = response.usage))
          case Left(error)                          => Iterator.single(LmChunk.failure(error))

  /** The cache key of `request`, or `None` when the model has no cache or the request has no key. The key request has
    * the merged options: the provider merges its default options into each request, so two models with one id and
    * different defaults get different entries. The key also has the mode of the delegate and the cache identity of the
    * delegate ([[dspy4s.lm.contracts.LanguageModel.cacheIdentity]]), for example the endpoint of a TypeSafe model.
    * `rolloutId` and all other fields stay as they are. The delegate still receives the original request.
    */
  private def keyOf(request: LmRequest): Option[CacheKey] =
    if cache.isEmpty then None
    else cacheKey(request.copy(options = request.effectiveOptions(defaultOptions)), mode, cacheIdentity).toOption

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    val key = keyOf(request)
    cacheHit(request, key) match
      case Some(cached) => Right(cached)
      case None         =>
        // rolloutId is a field, not an option. A provider sends only the options, so the request goes as it is.
        val result = executeWithRetry(request)
        result match
          case Left(error) =>
            appendFailureHistory(request, error)
            Left(error)
          case Right(response) if response.cacheHit => Right(acceptDelegateHit(request, response))
          case Right(response)                      => Right(acceptResponse(request, key, response))

  /** The cached response of `request` (see [[acceptHit]]). */
  private def cacheHit(request: LmRequest, key: Option[CacheKey])(using RuntimeContext): Option[LmResponse] =
    for
      store  <- cache
      found  <- key
      stored <- store.get(found)
    yield acceptHit(request, stored)

  /** The response of a cache hit, `stored`, with `cacheHit = true` and no usage: a hit bills no tokens, as upstream. A
    * hit appends the history entry of a cache hit, and is not stored again. The cost of that entry comes from the usage
    * of `stored` (upstream keeps the cost of the cached result), at the current [[price]].
    */
  private def acceptHit(request: LmRequest, stored: LmResponse)(using RuntimeContext): LmResponse =
    val hit = stored.copy(cacheHit = true, usage = None)
    appendHistory(request, hit, cacheHit = true, stored.usage)
    hit

  /** A cache hit of a delegate that is a managed model too. That model wrote the history entry of the hit, so this
    * model writes none (see [[recordsDelegateCalls]]).
    */
  private def acceptDelegateHit(request: LmRequest, response: LmResponse)(using RuntimeContext): LmResponse =
    if recordsDelegateCalls then acceptHit(request, response) else response.copy(cacheHit = true, usage = None)

  /** Store the response that the delegate gave, and append its history entry. */
  private def acceptResponse(request: LmRequest, key: Option[CacheKey], response: LmResponse)(using
      RuntimeContext
  ): LmResponse =
    val uncached = response.copy(cacheHit = false)
    for store <- cache; found <- key do store.put(found, uncached)
    if recordsDelegateCalls then appendHistory(request, uncached, cacheHit = false, uncached.usage)
    uncached

  /** One call of the delegate. A non-fatal exception gives an [[dspy4s.core.contracts.LmUnexpectedError]]. */
  private def callDelegate(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    try delegate.call(request)
    catch case NonFatal(error) => Left(ManagedLanguageModel.unexpected(error, request))

  /** The attempts of a call, with the retry policy. `spent` is the usage of the failed attempts. The response of a
    * success and the error of a failure get the usage of the call (see [[ManagedLanguageModel]]).
    */
  private def executeWithRetry(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    @tailrec def loop(attempt: Int, spent: Option[LmUsage]): Either[DspyError, LmResponse] =
      callDelegate(request) match
        case Left(error) =>
          val used = total(spent, LmError.usage(error))
          if retryAfter(attempt, error) then loop(attempt + 1, used) else Left(LmError.withUsage(error, used))
        case Right(response) => Right(response.copy(usage = total(spent, response.usage)))
    loop(0, None)

  /** The total of two usages. `None` is no usage, so the total of two `None` values is `None`. */
  private def total(
      first : Option[LmUsage],
      second: Option[LmUsage]
  ): Option[LmUsage] = (first ++ second).reduceOption(_.combine(_))

  /** `true` when the retry policy gives a new attempt after the failure `error` of the attempt `attempt`. The function
    * then waits for the delay of the policy before it gives `true`. The call path and the stream path use it.
    */
  private def retryAfter(attempt: Int, error: DspyError): Boolean =
    val retry = retryPolicy.shouldRetry(attempt, error)
    if retry then
      val delay = retryPolicy.delayBeforeNextAttemptMillis(attempt, error)
      if delay > 0L then sleep(delay)
    retry

  /** Append the history entry of a success or a cache hit. The cost estimate of the entry comes from `usage`, at the
    * current [[price]]. [[LmHistoryRecord.success]] gives the payload. The function builds the payload only when
    * [[dspy4s.core.runtime.RuntimeEnvironment.historyEnabled]] is `true`.
    */
  private def appendHistory(request: LmRequest, response: LmResponse, cacheHit: Boolean, usage: Option[LmUsage])(using
      RuntimeContext
  ): Unit =
    if RuntimeEnvironment.historyEnabled then
      val cost = LmCost.of(request.model, price, usage)
      RuntimeEnvironment.appendHistory(
        HistoryEntry(historyComponent, LmHistoryRecord.success(request, mode, response, cacheHit, cost))
      )

  /** Append the history entry of a failure of the delegate. [[LmHistoryRecord.failure]] gives the payload. A delegate
    * that is a managed model wrote the entry, so this model writes none (see [[recordsDelegateCalls]]).
    */
  private def appendFailureHistory(request: LmRequest, error: DspyError)(using RuntimeContext): Unit =
    if recordsDelegateCalls && RuntimeEnvironment.historyEnabled then
      RuntimeEnvironment.appendHistory(HistoryEntry(historyComponent, LmHistoryRecord.failure(request, mode, error)))

  /** One streamed call to a streaming delegate, with the retry, the stream guard, the history, and the cache store of
    * [[stream]].
    *
    * An attempt holds back its chunks until its first chunk with text, reasoning, a citation, or a tool call, or until
    * its chunk with a finish reason. Then the attempt is visible: the held chunks and all later chunks go to the
    * caller. A failure of an attempt that is not visible drops its chunks, and the retry policy decides on a new
    * attempt. A failure of a visible attempt ends the stream.
    *
    * The history entry and the cache store occur at the end of the stream. A caller that closes the stream before its
    * end gets neither of them. A stream with a [[LmChunk.cached]] chunk is a cache hit of the delegate: it gets the
    * history entry of a hit, and no store. A delegate that is a managed model writes the history entry itself, so this
    * stream writes none (see [[recordsDelegateCalls]]).
    *
    * A non-fatal exception of the delegate, when it opens the stream or during a read, is a failure of the attempt with
    * an [[dspy4s.core.contracts.LmUnexpectedError]].
    */
  private final class ManagedStream(streaming: StreamingLanguageModel, request: LmRequest, key: Option[CacheKey])(using
      RuntimeContext
  ) extends ClosableIterator[LmChunk]:
    private val ready                      = mutable.Queue.empty[LmChunk]
    private val held                       = mutable.ArrayBuffer.empty[LmChunk]
    private val parts                      = StreamAccumulator(streaming.mode)
    private var usage: Option[LmUsage]     = None
    private var spent: Option[LmUsage]     = None
    private var cached: Option[LmResponse] = None
    private var attempt                    = 0
    private var visible                    = false
    private var complete                   = false
    private var finished                   = false
    private var source: Iterator[LmChunk]  = open()

    override def hasNext: Boolean =
      while ready.isEmpty && !finished do step()
      ready.nonEmpty

    override def next(): LmChunk =
      if !hasNext then throw new NoSuchElementException("The managed LM stream has no more chunks")
      ready.dequeue()

    override def close(): Unit =
      finished = true
      ready.clear()
      held.clear()
      release()

    /** Open a stream of the delegate. A non-fatal exception gives a stream of one failure chunk. */
    private def open(): Iterator[LmChunk] =
      try streaming.stream(request)
      catch case NonFatal(error) => Iterator.single(LmChunk.failure(ManagedLanguageModel.unexpected(error, request)))

    /** The next chunk of the delegate, `None` at the end, or the error of a non-fatal exception of the read. */
    private def read(): Either[DspyError, Option[LmChunk]] =
      try Right(Option.when(source.hasNext)(source.next()))
      catch case NonFatal(error) => Left(ManagedLanguageModel.unexpected(error, request))

    /** Read one chunk of the delegate, or end the attempt when the delegate has no more chunks. */
    private def step(): Unit =
      read() match
        case Left(error)        => fail(error, LmChunk.failure(error))
        case Right(Some(chunk)) => chunk.reportedFailure match
            case Some(error) => fail(error, chunk)
            case None        => accept(chunk)
        case Right(None) if complete => succeed()
        case Right(None)             =>
          // The stream guard (upstream `checked_stream`): a stream with no completion chunk is not complete.
          val error = LmStreamAssemblyError(
            "The LM stream ended with no chunk that has a finish reason",
            LmErrorDetails(model = Some(request.model))
          )
          fail(error, LmChunk.failure(error))

    private def accept(chunk: LmChunk): Unit =
      parts.push(chunk)
      chunk.usage.foreach(value => usage = Some(value))
      chunk.cachedResponse.foreach(response => cached = Some(response))
      if chunk.isFinal then complete = true
      // The caller gets the usage of the call: the usage of the failed attempts plus the usage of this attempt.
      val shown = if chunk.usage.isEmpty then chunk else chunk.copy(usage = total(spent, chunk.usage))
      if visible then ready += shown
      else
        held += shown
        // Upstream `_retryable`: no retry after visible output, and no retry after the end of the stream. Upstream
        // `ListenerBridge.chunk` gives a chunk for a thinking delta and a citation delta too.
        val output = chunk.text.nonEmpty || chunk.reasoning.nonEmpty || chunk.citations.nonEmpty ||
          chunk.toolCalls.nonEmpty
        if output || complete then show()

    private def show(): Unit =
      visible = true
      ready ++= held
      held.clear()

    /** End the attempt with `error`. The usage of the attempt is the usage of `error`, else the usage of its chunks,
      * and it adds to `spent`. The caller gets `chunk`, the failure chunk, when no retry occurs. The error of that
      * chunk then has the usage of the call.
      */
    private def fail(error: DspyError, chunk: LmChunk): Unit =
      release()
      spent = total(spent, LmError.usage(error).orElse(usage))
      if !visible && retryAfter(attempt, error) then
        attempt += 1
        held.clear()
        parts.clear()
        usage = None
        cached = None
        complete = false
        source = open()
      else
        // The caller gets no held chunk of the attempt. It gets only the failure chunk, with the usage of the call.
        held.clear()
        ready += chunk.copy(error = chunk.error.map(LmError.withUsage(_, spent)))
        finished = true
        appendFailureHistory(request, error)

    private def succeed(): Unit =
      release()
      finished = true
      cached match
        case Some(response) => val _ = acceptDelegateHit(request, response)
        case None           =>
          // The last attempt has no usage chunk: one more chunk gives the usage of the failed attempts to the caller.
          if usage.isEmpty then spent.foreach(value => ready += LmChunk(usage = Some(value)))
          val response = LmResponse(outputs = Vector(parts.output), usage = total(spent, usage))
          val _        = acceptResponse(request, key, response)

    /** Close the stream of the delegate, so that the connection does not stay open. A non-fatal exception of the close
      * does not change the result: the attempt ends in each case.
      */
    private def release(): Unit =
      source match
        case closable: ClosableIterator[?] =>
          try closable.close()
          catch case NonFatal(_) => ()
        case _ => ()
      source = Iterator.empty

object ManagedLanguageModel:
  private def defaultSleep(millis: Long): Unit =
    Thread.sleep(millis)

  /** The error of a non-fatal exception of the delegate (upstream `wrap_error`: `LMUnexpectedError(str(exc))` with the
    * model). The message is the text of [[dspy4s.core.contracts.DspyError.messageOf]]. The stream wrapper of
    * `streamify` gives the same error for an exception of its model.
    */
  def unexpected(error: Throwable, request: LmRequest): LmUnexpectedError =
    LmUnexpectedError(DspyError.messageOf(error), LmErrorDetails(model = Some(request.model)))
