package dspy4s.lm

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmUsage
import dspy4s.lm.contracts.TokenCategory
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import dspy4s.lm.runtime.CompositeLmCache
import dspy4s.lm.runtime.DiskLmCache
import dspy4s.lm.runtime.InMemoryLmCache
import dspy4s.lm.runtime.LmCacheConfig
import dspy4s.lm.runtime.LmCacheRegistry
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.lm.runtime.NoopLmCache
import dspy4s.lm.runtime.RequestHash
import dspy4s.lm.runtime.RetryPolicies
import dspy4s.lm.runtime.UsageTracker
import dspy4s.lm.runtime.UsageTracking
import munit.FunSuite

import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

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
    options = DynamicValues.record("temperature" := 0.7)
  )

  private val baseResponse = LmResponse(
    outputs = Vector(LmOutput(text = "hello")),
    usage = Some(LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5, extras = Map(TokenCategory.Cached -> 3L)))
  )

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()
    LmCacheRegistry.resetDefault()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()
    LmCacheRegistry.resetDefault()

  test("request hash is stable for equivalent map orderings") {
    val requestA = baseRequest.copy(
      options = DynamicValues.record("a" := 1, "nested" := Map("x" -> 1, "y" -> 2), "items" := Vector(1, 2, 3))
    )
    val requestB = baseRequest.copy(
      options = DynamicValues.record("items" := Vector(1, 2, 3), "nested" := Map("y" -> 2, "x" -> 1), "a" := 1)
    )

    assertEquals(RequestHash.forRequest(requestA), RequestHash.forRequest(requestB))
  }

  test("request hash distinguishes primitive types with equal text") {
    val asInt    = baseRequest.copy(options = DynamicValues.record("v" := 1))
    val asString = baseRequest.copy(options = DynamicValues.record("v" := "1"))

    assertNotEquals(RequestHash.forRequest(asInt), RequestHash.forRequest(asString))
  }

  test("in-memory cache returns cache hit response without usage") {
    val cache = new InMemoryLmCache(maxEntries = 8)
    cache.put(baseRequest, baseResponse)

    val cached = cache.get(baseRequest)
    assert(cached.isDefined)
    assertEquals(cached.get.cacheHit, true)
    assertEquals(cached.get.usage, None)
  }

  test("managed language model caches by typed rolloutId and keeps it out of provider options") {
    val delegate = new StubLanguageModel(Vector(Right(baseResponse), Right(baseResponse)))
    val managed = ManagedLanguageModel(delegate = delegate, cache = Some(new InMemoryLmCache(16)))
    val request = baseRequest.copy(rolloutId = Some(1))

    given RuntimeContext = RuntimeEnvironment.current
    val first = managed.call(request)
    val second = managed.call(request)
    val third = managed.call(request.copy(rolloutId = Some(2)))

    assert(first.isRight)
    assert(second.isRight)
    assert(third.isRight)
    // Distinct rolloutId -> distinct cache key -> cache miss; same rolloutId -> hit.
    assertEquals(first.toOption.get.cacheHit, false)
    assertEquals(second.toOption.get.cacheHit, true)
    assertEquals(third.toOption.get.cacheHit, false)
    assertEquals(delegate.calls.size, 2)
    // rolloutId rides as a typed field to the delegate (no strip) and never leaks into the provider option bag.
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
    val managed = ManagedLanguageModel(delegate = delegate, retryPolicy = RetryPolicies.maxRetries(2))

    given RuntimeContext = RuntimeEnvironment.current
    val result = managed.call(baseRequest)

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
    val delays = ArrayBuffer.empty[Long]
    val retryPolicy = RetryPolicies.exponentialBackoff(
      maxRetries = 2,
      baseDelayMillis = 5L,
      maxDelayMillis = 20L,
      jitterFactor = 0.0
    )
    val managed = ManagedLanguageModel(
      delegate = delegate,
      retryPolicy = retryPolicy,
      sleep = millis => delays += millis
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = managed.call(baseRequest)

    assert(result.isRight)
    assertEquals(delegate.calls.size, 3)
    assertEquals(delays.toVector, Vector(5L, 10L))
  }

  test("retry code filtering prevents retries for non-matching errors") {
    val delegate = new StubLanguageModel(
      Vector(
        Left(ConfigurationError("bad setup")),
        Right(baseResponse)
      )
    )
    val retryPolicy = RetryPolicies.maxRetriesOnCodes(
      maxAttempts = 3,
      retryableCodes = Set("runtime_error")
    )
    val managed = ManagedLanguageModel(delegate = delegate, retryPolicy = retryPolicy)

    given RuntimeContext = RuntimeEnvironment.current
    val result = managed.call(baseRequest)

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[ConfigurationError])
    assertEquals(delegate.calls.size, 1)
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
    assertEquals(totals.extras(TokenCategory.Cached), 3L)
  }

  test("usage tracking respects track_usage setting") {
    val delegate = new StubLanguageModel(Vector(Right(baseResponse)))
    val managed = ManagedLanguageModel(delegate = delegate)
    val tracker = new UsageTracker

    given RuntimeContext = RuntimeEnvironment.current
    UsageTracking.withTracker(tracker) {
      RuntimeEnvironment.withSettings(RuntimeContext(trackUsage = Some(false))) {
        assert(managed.call(baseRequest).isRight)
      }
    }

    assertEquals(tracker.usageData, Map.empty)
  }

  test("disk cache persists values across cache instances") {
    val tempDir = Files.createTempDirectory("dspy4s-lm-disk-cache")
    try
      val first = new DiskLmCache(tempDir, maxEntries = 8)
      first.put(baseRequest, baseResponse)
      assertEquals(first.size, 1)

      val second = new DiskLmCache(tempDir, maxEntries = 8)
      val cached = second.get(baseRequest)
      assert(cached.isDefined)
      assertEquals(cached.get.cacheHit, true)
      assertEquals(cached.get.usage, None)
    finally deleteRecursively(tempDir)
  }

  test("disk cache round-trips typed tool-call args faithfully (not stringified)") {
    val tempDir = Files.createTempDirectory("dspy4s-lm-disk-cache-tools")
    try
      val toolResponse = LmResponse(outputs = Vector(LmOutput(
        text = "",
        toolCalls = Vector(ToolCall(
          name = "search",
          args = DynamicValues.recordFromEntries(Seq("query" := "belgium", "top_k" := 3))
        ))
      )))
      val first = new DiskLmCache(tempDir, maxEntries = 8)
      first.put(baseRequest, toolResponse)

      val second = new DiskLmCache(tempDir, maxEntries = 8)
      val args   = DynamicValues.recordToMap(second.get(baseRequest).get.outputs.head.toolCalls.head.args)

      assertEquals(args("query"), "belgium": Any)
      // Before the DynamicValue migration the disk cache flattened args via String.valueOf,
      // so top_k=3 round-tripped to the String "3". It must now stay numeric.
      args("top_k") match
        case n: Int  => assertEquals(n, 3)
        case n: Long => assertEquals(n, 3L)
        case other   => fail(s"top_k must round-trip as a number, not ${other.getClass.getSimpleName}: $other")
    finally deleteRecursively(tempDir)
  }

  test("composite cache warms memory cache on disk hit") {
    val tempDir = Files.createTempDirectory("dspy4s-lm-composite-cache")
    try
      val disk = new DiskLmCache(tempDir, maxEntries = 8)
      val memory = new InMemoryLmCache(maxEntries = 8)
      disk.put(baseRequest, baseResponse)

      val composite = CompositeLmCache(memory = Some(memory), disk = Some(disk))
      val first = composite.get(baseRequest)
      val second = composite.get(baseRequest)

      assert(first.isDefined)
      assert(second.isDefined)
      assertEquals(memory.size, 1)
      assertEquals(second.get.cacheHit, true)
    finally deleteRecursively(tempDir)
  }

  test("cache registry configure supports disabled and memory-only modes") {
    val disabled = LmCacheRegistry.configure(
      LmCacheConfig(enableDiskCache = false, enableMemoryCache = false)
    )
    assert(disabled eq NoopLmCache)
    assertEquals(disabled.get(baseRequest), None)

    val memoryOnly = LmCacheRegistry.configure(
      LmCacheConfig(enableDiskCache = false, enableMemoryCache = true, memoryMaxEntries = 4)
    )
    memoryOnly.put(baseRequest, baseResponse)
    val cached = memoryOnly.get(baseRequest)
    assert(cached.isDefined)
    assertEquals(cached.get.cacheHit, true)
  }

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val children = Files.list(path)
        try
          children.iterator().asScala.foreach(child => deleteRecursively(child))
        finally children.close()
      val _ = Files.deleteIfExists(path)
