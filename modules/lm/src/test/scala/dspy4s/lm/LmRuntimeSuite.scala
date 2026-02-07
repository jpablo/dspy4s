package dspy4s.lm

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmUsage
import dspy4s.lm.runtime.InMemoryLmCache
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.lm.runtime.RequestHash
import dspy4s.lm.runtime.RetryPolicies
import dspy4s.lm.runtime.UsageTracker
import dspy4s.lm.runtime.UsageTracking
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

class LmRuntimeSuite extends FunSuite:
  private final class StubLanguageModel(initial: Vector[Either[DspyError, LmResponse]]) extends LanguageModel:
    private var scripted = initial
    val calls = ArrayBuffer.empty[LmRequest]

    override val id: String = "stub"
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
    options = Map("temperature" -> 0.7)
  )

  private val baseResponse = LmResponse(
    outputs = Vector(LmOutput(text = "hello")),
    usage = Some(LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5, details = Map("cached_tokens" -> 3L)))
  )

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("request hash is stable for equivalent map orderings") {
    val requestA = baseRequest.copy(
      options = Map("a" -> 1, "nested" -> Map("x" -> 1, "y" -> 2), "items" -> Vector(1, 2, 3))
    )
    val requestB = baseRequest.copy(
      options = Map("items" -> Vector(1, 2, 3), "nested" -> Map("y" -> 2, "x" -> 1), "a" -> 1)
    )

    assertEquals(RequestHash.forRequest(requestA), RequestHash.forRequest(requestB))
  }

  test("in-memory cache returns cache hit response without usage") {
    val cache = new InMemoryLmCache(maxEntries = 8)
    cache.put(baseRequest, baseResponse)

    val cached = cache.get(baseRequest)
    assert(cached.isDefined)
    assertEquals(cached.get.cacheHit, true)
    assertEquals(cached.get.usage, None)
  }

  test("managed language model caches by rollout id and strips rollout before delegate call") {
    val delegate = new StubLanguageModel(Vector(Right(baseResponse), Right(baseResponse)))
    val managed = ManagedLanguageModel(delegate = delegate, cache = Some(new InMemoryLmCache(16)))
    val request = baseRequest.copy(options = baseRequest.options ++ Map("rollout_id" -> 1))

    given RuntimeContext = RuntimeEnvironment.current
    val first = managed.call(request)
    val second = managed.call(request)
    val third = managed.call(request.copy(options = request.options.updated("rollout_id", 2)))

    assert(first.isRight)
    assert(second.isRight)
    assert(third.isRight)
    assertEquals(first.toOption.get.cacheHit, false)
    assertEquals(second.toOption.get.cacheHit, true)
    assertEquals(third.toOption.get.cacheHit, false)
    assertEquals(delegate.calls.size, 2)
    assert(delegate.calls.forall(request => !request.options.contains("rollout_id")))
  }

  test("managed language model retries until policy max retries is reached") {
    val delegate = new StubLanguageModel(
      Vector(
        Left(RuntimeError("lm", "rate-limited")),
        Left(RuntimeError("lm", "rate-limited")),
        Right(baseResponse)
      )
    )
    val managed = ManagedLanguageModel(delegate = delegate, retryPolicy = RetryPolicies.maxRetries(2))

    given RuntimeContext = RuntimeEnvironment.current
    val result = managed.call(baseRequest)

    assert(result.isRight)
    assertEquals(delegate.calls.size, 3)
  }

  test("usage tracking records only non-cached usage entries") {
    val delegate = new StubLanguageModel(Vector(Right(baseResponse)))
    val managed = ManagedLanguageModel(delegate = delegate, cache = Some(new InMemoryLmCache(16)))
    val tracker = new UsageTracker

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
    assertEquals(totals.details("cached_tokens"), 3L)
  }
