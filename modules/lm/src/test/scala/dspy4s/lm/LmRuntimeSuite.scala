package dspy4s.lm

import dspy4s.core.contracts.{
  ConfigurationError,
  DspyError,
  DynamicValues,
  LmUsage,
  RuntimeContext,
  RuntimeError,
  TokenCategory,
  :=
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.lm.runtime.{
  CacheCapacity,
  InMemoryLmCache,
  JitterFactor,
  LmCacheRegistry,
  ManagedLanguageModel,
  RetryCount,
  RetryDelayMillis,
  RetryPolicies,
  UsageTracker,
  UsageTracking
}
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class LmRuntimeSuite extends FunSuite:
  private final class StubLanguageModel(initial: Vector[Either[DspyError, LmResponse]]) extends LanguageModel:
    private var scripted = initial
    val calls            = ArrayBuffer.empty[LmRequest]

    override val id: String   = "stub"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      calls += request
      if scripted.isEmpty then Left(RuntimeError("stub_lm", "No scripted response available"))
      else
        val head = scripted.head
        scripted = scripted.tail
        head

  private val baseRequest = LmRequest(
    model = "test-model",
    mode = LmMode.Chat,
    options = DynamicValues.record("temperature" := 0.7)
  )

  private val baseResponse = LmResponse(
    outputs = Vector(LmOutput(text = "hello")),
    usage =
      Some(LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5, extras = Map(TokenCategory.Cached -> 3L)))
  )

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()
    LmCacheRegistry.resetDefault()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()
    LmCacheRegistry.resetDefault()

  test("LanguageModel.acall suspends the call on the supplied execution context") {
    val pending            = ArrayBuffer.empty[Runnable]
    given ExecutionContext =
      new ExecutionContext:
        override def execute(runnable   : Runnable): Unit  = pending += runnable
        override def reportFailure(cause: Throwable): Unit = throw cause
    given RuntimeContext = RuntimeEnvironment.current

    val lm     = new StubLanguageModel(Vector(Right(baseResponse)))
    val future = lm.acall(baseRequest)

    assertEquals(lm.calls.toVector, Vector.empty)
    assertEquals(pending.size, 1)

    pending.remove(0).run()

    assertEquals(Await.result(future, 1.second), Right(baseResponse))
    assertEquals(lm.calls.toVector, Vector(baseRequest))
  }

  test("managed language model caches by rolloutId and keeps it out of provider options") {
    val delegate = new StubLanguageModel(Vector(Right(baseResponse), Right(baseResponse)))
    val managed  = ManagedLanguageModel(delegate = delegate, cache = Some(new InMemoryLmCache(CacheCapacity(16))))
    val request  = baseRequest.copy(rolloutId = Some(1))

    given RuntimeContext = RuntimeEnvironment.current
    val first            = managed.call(request)
    val second           = managed.call(request)
    val third            = managed.call(request.copy(rolloutId = Some(2)))

    assert(first.isRight)
    assert(second.isRight)
    assert(third.isRight)
    // Distinct rolloutId -> distinct cache key -> cache miss; same rolloutId -> hit.
    assertEquals(first.toOption.get.cacheHit, false)
    assertEquals(second.toOption.get.cacheHit, true)
    assertEquals(third.toOption.get.cacheHit, false)
    assertEquals(delegate.calls.size, 2)
    // rolloutId rides as a field to the delegate (no strip) and never leaks into the provider option bag.
    assertEquals(delegate.calls.map(_.rolloutId).toVector, Vector[Option[Int]](Some(1), Some(2)))
    assert(delegate.calls.forall(c => DynamicValues.recordGet(c.options, "rollout_id").isEmpty))
  }

  test("managed language model retries until policy max retries is reached") {
    val delegate = new StubLanguageModel(
      Vector(
        Left(RuntimeError("lm", "rate-limited")),
        Left(RuntimeError("lm", "rate-limited")),
        Right(baseResponse)
      )
    )
    val managed = ManagedLanguageModel(delegate = delegate, retryPolicy = RetryPolicies.maxRetries(RetryCount(2)))

    given RuntimeContext = RuntimeEnvironment.current
    val result           = managed.call(baseRequest)

    assert(result.isRight)
    assertEquals(delegate.calls.size, 3)
  }

  test("exponential backoff policy emits deterministic delays when jitter is disabled") {
    val delegate = new StubLanguageModel(
      Vector(
        Left(RuntimeError("lm", "temporary-1")),
        Left(RuntimeError("lm", "temporary-2")),
        Right(baseResponse)
      )
    )
    val delays      = ArrayBuffer.empty[Long]
    val retryPolicy = RetryPolicies.exponentialBackoff(
      maxRetries = RetryCount(2),
      baseDelayMillis = RetryDelayMillis(5L),
      maxDelayMillis = RetryDelayMillis(20L),
      jitterFactor = JitterFactor(0.0)
    )
    val managed = ManagedLanguageModel(
      delegate = delegate,
      retryPolicy = retryPolicy,
      sleep = millis => delays += millis
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result           = managed.call(baseRequest)

    assert(result.isRight)
    assertEquals(delegate.calls.size, 3)
    assertEquals(delays.toVector, Vector(5L, 10L))
  }

  test("exponential backoff saturates instead of overflowing large delays") {
    val base   = Long.MaxValue / 2L + 1L
    val policy = RetryPolicies.exponentialBackoff(
      maxRetries = RetryCount(30),
      baseDelayMillis = RetryDelayMillis.applyUnsafe(base),
      maxDelayMillis = RetryDelayMillis.applyUnsafe(Long.MaxValue),
      jitterFactor = JitterFactor(0.0)
    )
    val error = RuntimeError("lm", "temporary")

    assertEquals(policy.delayBeforeNextAttemptMillis(0, error), base)
    assertEquals(policy.delayBeforeNextAttemptMillis(1, error), Long.MaxValue)
    assertEquals(policy.delayBeforeNextAttemptMillis(20, error), Long.MaxValue)
  }

  test("retry code filtering prevents retries for non-matching errors") {
    val delegate = new StubLanguageModel(
      Vector(
        Left(ConfigurationError("bad setup")),
        Right(baseResponse)
      )
    )
    val retryPolicy = RetryPolicies.maxRetriesOnCodes(
      maxRetries = RetryCount(3),
      retryableCodes = Set("runtime_error")
    )
    val managed = ManagedLanguageModel(delegate = delegate, retryPolicy = retryPolicy)

    given RuntimeContext = RuntimeEnvironment.current
    val result           = managed.call(baseRequest)

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[ConfigurationError])
    assertEquals(delegate.calls.size, 1)
  }

  test("usage tracking records only non-cached usage entries") {
    val delegate = new StubLanguageModel(Vector(Right(baseResponse)))
    val managed  = ManagedLanguageModel(delegate = delegate, cache = Some(new InMemoryLmCache(CacheCapacity(16))))
    val tracker  = new UsageTracker

    given RuntimeContext = RuntimeEnvironment.current
    UsageTracking.withTracker(tracker) {
      assert(managed.call(baseRequest).isRight)
      assert(managed.call(baseRequest).isRight)
    }

    val usageData = tracker.usageData
    assertEquals(usageData.keySet, Set("test-model"))
    assertEquals(usageData("test-model").size, 1)
    val totals = tracker.totalUsage("test-model")
    assertEquals(totals.totalTokens, 9L)
    assertEquals(totals.promptTokens, 4L)
    assertEquals(totals.completionTokens, 5L)
    assertEquals(totals.extras(TokenCategory.Cached), 3L)
  }

  test("usage tracking respects track_usage setting") {
    val delegate = new StubLanguageModel(Vector(Right(baseResponse)))
    val managed  = ManagedLanguageModel(delegate = delegate)
    val tracker  = new UsageTracker

    given RuntimeContext = RuntimeEnvironment.current
    UsageTracking.withTracker(tracker) {
      RuntimeEnvironment.withSettings(RuntimeContext(trackUsage = Some(false))) {
        assert(managed.call(baseRequest).isRight)
      }
    }

    assertEquals(tracker.usageData, Map.empty)
  }
