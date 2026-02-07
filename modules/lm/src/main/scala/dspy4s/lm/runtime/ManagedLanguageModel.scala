package dspy4s.lm.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmCache
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmUsage
import dspy4s.lm.contracts.RetryPolicy

import scala.collection.mutable
import java.util.concurrent.ThreadLocalRandom

object RetryPolicies:
  val never: RetryPolicy = new RetryPolicy:
    override def shouldRetry(attempt: Int, error: DspyError): Boolean = false

  def maxRetries(maxRetries: Int, retryOn: DspyError => Boolean = _ => true): RetryPolicy =
    require(maxRetries >= 0, "maxRetries must be non-negative")
    new RetryPolicy:
      override def shouldRetry(attempt: Int, error: DspyError): Boolean =
        attempt < maxRetries && retryOn(error)

  def maxRetriesOnCodes(maxAttempts: Int, retryableCodes: Set[String]): RetryPolicy =
    maxRetries(maxAttempts, error => retryableCodes.contains(error.code))

  def exponentialBackoff(
      maxRetries: Int,
      baseDelayMillis: Long = 200L,
      maxDelayMillis: Long = 4000L,
      jitterFactor: Double = 0.0,
      retryOn: DspyError => Boolean = _ => true
  ): RetryPolicy =
    require(maxRetries >= 0, "maxRetries must be non-negative")
    require(baseDelayMillis >= 0L, "baseDelayMillis must be non-negative")
    require(maxDelayMillis >= baseDelayMillis, "maxDelayMillis must be >= baseDelayMillis")
    require(jitterFactor >= 0.0 && jitterFactor <= 1.0, "jitterFactor must be in [0.0, 1.0]")

    new RetryPolicy:
      override def shouldRetry(attempt: Int, error: DspyError): Boolean =
        attempt < maxRetries && retryOn(error)

      override def delayBeforeNextAttemptMillis(attempt: Int, error: DspyError): Long =
        val exponent = if attempt <= 0 then 0 else attempt
        val scaled = baseDelayMillis * (1L << math.min(exponent, 20))
        val bounded = math.min(scaled, maxDelayMillis)
        if jitterFactor == 0.0 then bounded
        else
          val maxJitter = math.max((bounded * jitterFactor).toLong, 0L)
          val jitter = if maxJitter == 0L then 0L else ThreadLocalRandom.current().nextLong(maxJitter + 1L)
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
      data.view.mapValues(aggregate).toMap
    }

  private def aggregate(entries: Vector[LmUsage]): LmUsage =
    entries.foldLeft(LmUsage()) { (acc, usage) =>
      val mergedDetails = usage.details.foldLeft(acc.details) { case (map, (key, value)) =>
        map.updated(key, map.getOrElse(key, 0L) + value)
      }
      acc.copy(
        totalTokens = acc.totalTokens + usage.totalTokens,
        promptTokens = acc.promptTokens + usage.promptTokens,
        completionTokens = acc.completionTokens + usage.completionTokens,
        details = mergedDetails
      )
    }

object UsageTracking:
  private val activeTrackers = new ThreadLocal[Vector[UsageTracker]]:
    override def initialValue(): Vector[UsageTracker] = Vector.empty

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
    delegate: LanguageModel,
    cache: Option[LmCache] = None,
    retryPolicy: RetryPolicy = RetryPolicies.never,
    sleep: Long => Unit = ManagedLanguageModel.defaultSleep
) extends LanguageModel:
  override val id: String = delegate.id
  override val mode: LmMode = delegate.mode

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    cache.flatMap(_.get(request)) match
      case Some(cached) =>
        appendHistory(request, cached, cacheHit = true)
        Right(cached.copy(cacheHit = true))
      case None =>
        val providerRequest = request.copy(options = request.options.removed("rollout_id"))
        val result = executeWithRetry(providerRequest)
        result match
          case Left(error) =>
            appendFailureHistory(providerRequest, error)
            Left(error)
          case Right(response) =>
            val uncached = response.copy(cacheHit = false)
            cache.foreach(_.put(request, uncached))
            appendHistory(providerRequest, uncached, cacheHit = false)
            trackUsage(providerRequest, uncached)
            Right(uncached)

  private def executeWithRetry(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    var attempt = 0
    var continue = true
    var result: Either[DspyError, LmResponse] = Left(
      dspy4s.core.contracts.RuntimeError("lm", "retry loop did not execute")
    )

    while continue do
      delegate.call(request) match
        case ok @ Right(_) =>
          result = ok
          continue = false
        case Left(error) if retryPolicy.shouldRetry(attempt, error) =>
          val delay = retryPolicy.delayBeforeNextAttemptMillis(attempt, error)
          if delay > 0L then sleep(delay)
          attempt += 1
        case failed @ Left(_) =>
          result = failed
          continue = false

    result

  private def appendHistory(request: LmRequest, response: LmResponse, cacheHit: Boolean)(using RuntimeContext): Unit =
    if historyEnabled then
      RuntimeEnvironment.appendHistory(
        HistoryEntry(
          component = s"lm:$id",
          payload = Map(
            "model" -> request.model,
            "cache_hit" -> cacheHit,
            "outputs" -> response.outputs.size,
            "mode" -> request.mode.toString
          )
        )
      )

  private def appendFailureHistory(request: LmRequest, error: DspyError)(using RuntimeContext): Unit =
    if historyEnabled then
      RuntimeEnvironment.appendHistory(
        HistoryEntry(
          component = s"lm:$id",
          payload = Map(
            "model" -> request.model,
            "cache_hit" -> false,
            "error_code" -> error.code,
            "error_message" -> error.message
          )
        )
      )

  private def historyEnabled(using RuntimeContext): Boolean =
    !summon[RuntimeContext].settings.get(SettingKeys.disableHistory).getOrElse(false)

  private def trackUsage(request: LmRequest, response: LmResponse)(using RuntimeContext): Unit =
    val usageEnabled = summon[RuntimeContext].settings.get(SettingKeys.trackUsage).getOrElse(true)
    if usageEnabled && !response.cacheHit then
      response.usage.foreach { usage =>
        UsageTracking.record(request.model, usage)
      }

object ManagedLanguageModel:
  private def defaultSleep(millis: Long): Unit =
    Thread.sleep(millis)
