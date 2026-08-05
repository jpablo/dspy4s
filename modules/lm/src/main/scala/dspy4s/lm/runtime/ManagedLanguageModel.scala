package dspy4s.lm.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmCache
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmUsage
import dspy4s.lm.contracts.RetryPolicy
import dspy4s.lm.contracts.StreamingLanguageModel

import scala.annotation.tailrec
import scala.collection.mutable
import java.util.concurrent.ThreadLocalRandom

object RetryPolicies:
  val never: RetryPolicy = new RetryPolicy:
    override def shouldRetry(attempt: Int, error: DspyError): Boolean = false

  def maxRetries(maxRetries: RetryCount, retryOn: DspyError => Boolean = _ => true): RetryPolicy =
    new RetryPolicy:
      override def shouldRetry(attempt: Int, error: DspyError): Boolean =
        attempt < maxRetries && retryOn(error)

  def maxRetriesOnCodes(maxRetries: RetryCount, retryableCodes: Set[String]): RetryPolicy =
    this.maxRetries(maxRetries, error => retryableCodes.contains(error.code))

  def exponentialBackoff(
      maxRetries     : RetryCount,
      baseDelayMillis: RetryDelayMillis     = RetryDelayMillis(200L),
      maxDelayMillis : RetryDelayMillis     = RetryDelayMillis(4000L),
      jitterFactor   : JitterFactor         = JitterFactor(0.0),
      retryOn        : DspyError => Boolean = _ => true
  ): RetryPolicy =
    require(maxDelayMillis >= baseDelayMillis, "maxDelayMillis must be >= baseDelayMillis")

    new RetryPolicy:
      override def shouldRetry(attempt: Int, error: DspyError): Boolean =
        attempt < maxRetries && retryOn(error)

      override def delayBeforeNextAttemptMillis(attempt: Int, error: DspyError): Long =
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

final class UsageTracker:
  private val data = mutable.Map.empty[String, Vector[LmUsage]]

  def addUsage(model: String, usage: LmUsage): Unit =
    this.synchronized {
      val entries = data.getOrElse(model, Vector.empty)
      data.update(model, entries :+ usage)
    }

  def usageData: Map[String, Vector[LmUsage]] =
    this.synchronized {
      data.toMap
    }

  def totalUsage: Map[String, LmUsage] =
    this.synchronized {
      data.view.mapValues(_.foldLeft(LmUsage.empty)(_.combine(_))).toMap
    }

object UsageTracking:
  private val activeTrackers = new ThreadLocal[Vector[UsageTracker]]:
    override def initialValue(): Vector[UsageTracker] = Vector.empty

  // The tracker stack must travel with the RuntimeContext to worker threads (ParallelExecutor, futures,
  // the streamify producer) — otherwise usage recorded there is silently dropped. Registered once at object
  // init; installing a tracker touches this object, so registration always precedes any capture that matters.
  ContextPropagation.registerCarrier(new ContextPropagation.Carrier:
    override def capture(): ContextPropagation.Snapshot =
      val captured = activeTrackers.get()
      new ContextPropagation.Snapshot:
        override def restore[A](thunk: => A): A =
          val previous = activeTrackers.get()
          activeTrackers.set(captured)
          try thunk
          finally activeTrackers.set(previous))

  def withTracker[A](tracker: UsageTracker)(thunk: => A): A =
    val previous = activeTrackers.get()
    activeTrackers.set(previous :+ tracker)
    try thunk
    finally activeTrackers.set(previous)

  def withNewTracker[A](thunk: UsageTracker => A): A =
    val tracker = new UsageTracker
    withTracker(tracker)(thunk(tracker))

  def record(model: String, usage: LmUsage): Unit =
    activeTrackers.get().foreach(_.addUsage(model, usage))

final case class ManagedLanguageModel(
    delegate   : LanguageModel,
    cache      : Option[LmCache] = None,
    retryPolicy: RetryPolicy     = RetryPolicies.never,
    sleep      : Long => Unit    = ManagedLanguageModel.defaultSleep
) extends StreamingLanguageModel:
  override val id: String   = delegate.id
  override val mode: LmMode = delegate.mode
  // Capability flags pass through — wrapping must not hide what the delegate supports (adapters consult
  // these to decide e.g. whether to emit `response_format` or native tools).
  override def supportsFunctionCalling: Boolean = delegate.supportsFunctionCalling
  override def supportsResponseSchema: Boolean  = delegate.supportsResponseSchema
  override def supportsReasoning: Boolean       = delegate.supportsReasoning

  /** Streaming passthrough, so wrapping a streaming provider in ManagedLanguageModel does not silently disable token
    * streaming (streamify only wraps `StreamingLanguageModel`s).
    *
    *   - Streaming delegate: tokens come straight from the delegate. The cache and retry policy do NOT apply to
    *     streamed calls (same as calling the provider's `stream` directly).
    *   - Non-streaming delegate: falls back to one terminal chunk assembled from the managed [[call]] (which keeps
    *     cache / retries / history / usage), or a reified `finishReason = "error"` chunk on failure — the same error
    *     convention providers use.
    */
  override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
    delegate match
      case streaming: StreamingLanguageModel => streaming.stream(request)
      case _                                 => call(request) match
          case Right(response) => Iterator.single(LmChunk(
              text = response.outputs.headOption.map(_.text).getOrElse(""),
              finishReason = Some("stop"),
              usage = response.usage
            ))
          case Left(error) =>
            Iterator.single(LmChunk(finishReason = Some("error"), raw = Some(Map("error" -> error.message))))

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    cache.flatMap(_.get(request)) match
      case Some(cached) =>
        appendHistory(request, cached, cacheHit = true)
        Right(cached.copy(cacheHit = true))
      case None =>
        // rolloutId is a typed field (not in `options`) and `normalize` only spreads `options` to the wire,
        // so there is nothing to strip before the provider call -- it cannot reach the request body.
        val result = executeWithRetry(request)
        result match
          case Left(error) =>
            appendFailureHistory(request, error)
            Left(error)
          case Right(response) =>
            val uncached = response.copy(cacheHit = false)
            cache.foreach(_.put(request, uncached))
            appendHistory(request, uncached, cacheHit = false)
            trackUsage(request, uncached)
            Right(uncached)

  private def executeWithRetry(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    @tailrec def loop(attempt: Int): Either[DspyError, LmResponse] =
      delegate.call(request) match
        case ok @ Right(_)                                          => ok
        case Left(error) if retryPolicy.shouldRetry(attempt, error) =>
          val delay = retryPolicy.delayBeforeNextAttemptMillis(attempt, error)
          if delay > 0L then sleep(delay)
          loop(attempt + 1)
        case failed @ Left(_) => failed
    loop(0)

  private def appendHistory(request: LmRequest, response: LmResponse, cacheHit: Boolean)(using RuntimeContext): Unit =
    if historyEnabled then
      RuntimeEnvironment.appendHistory(
        HistoryEntry(
          component = s"lm:$id",
          payload = DynamicValues.record(
            "model"     := request.model,
            "cache_hit" := cacheHit,
            "outputs"   := response.outputs.size,
            "mode"      := request.mode.toString
          )
        )
      )

  private def appendFailureHistory(request: LmRequest, error: DspyError)(using RuntimeContext): Unit =
    if historyEnabled then
      RuntimeEnvironment.appendHistory(
        HistoryEntry(
          component = s"lm:$id",
          payload = DynamicValues.record(
            "model"         := request.model,
            "cache_hit"     := false,
            "error_code"    := error.code,
            "error_message" := error.message
          )
        )
      )

  private def historyEnabled(using RuntimeContext): Boolean =
    !summon[RuntimeContext].disableHistory.getOrElse(false)

  private def trackUsage(request: LmRequest, response: LmResponse)(using RuntimeContext): Unit =
    val usageEnabled = summon[RuntimeContext].trackUsage.getOrElse(true)
    if usageEnabled && !response.cacheHit then
      response.usage.foreach { usage =>
        UsageTracking.record(request.model, usage)
      }

object ManagedLanguageModel:
  private def defaultSleep(millis: Long): Unit =
    Thread.sleep(millis)
