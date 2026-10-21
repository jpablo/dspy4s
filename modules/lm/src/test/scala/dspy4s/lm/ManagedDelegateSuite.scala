package dspy4s.lm

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.LmUnexpectedError
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmCache
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.lm.runtime.CacheCapacity
import dspy4s.lm.runtime.InMemoryLmCache
import dspy4s.lm.runtime.LmCacheRegistry
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.lm.runtime.RetryCount
import dspy4s.lm.runtime.RetryPolicies
import dspy4s.lm.runtime.UsageTracking
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

/** A managed model over a delegate that is not a plain provider: a second managed model, whose cache hit is a hit of
  * the outer model too, and a delegate that throws. Upstream changes the exception of an engine into an
  * `LMUnexpectedError` (`error_boundary(..., unexpected=True)` in `dspy/clients/errors.py`).
  */
class ManagedDelegateSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()
    LmCacheRegistry.resetDefault()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()
    LmCacheRegistry.resetDefault()

  private val request: LmRequest = LmRequest(model = "test-model")

  private val usage: LmUsage = LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5)

  private val response: LmResponse = LmResponse(outputs = Vector(LmOutput(text = "Hello")), usage = Some(usage))

  private val complete: Vector[LmChunk] = Vector(
    LmChunk(text = "Hel"),
    LmChunk(text = "lo", finishReason = Some("stop")),
    LmChunk(usage = Some(usage))
  )

  /** A model that answers each call with `response` and each stream with `complete`, and counts the requests. */
  private final class Answering extends StreamingLanguageModel:
    var calls                 = 0
    var streams               = 0
    override val id: String   = "stub"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      calls += 1
      Right(response)

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      streams += 1
      complete.iterator

  /** A model whose calls give the next outcome of `outcomes`: a thrown exception or a response. */
  private final class Throwing(outcomes: Vector[() => LmResponse]) extends LanguageModel:
    private var remaining     = outcomes
    var calls                 = 0
    override val id: String   = "stub"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      calls += 1
      val next = remaining.head
      remaining = remaining.tail
      Right(next())

  /** A stream model whose `stream` gives the result of `open`. */
  private final class Opening(open: () => Iterator[LmChunk]) extends StreamingLanguageModel:
    override val id: String   = "stub"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(response)

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] = open()

  /** A stream that gives one chunk with text, then throws on the next read. It records its close. */
  private final class Broken extends ClosableIterator[LmChunk]:
    var closed                    = false
    private var reads             = 0
    override def hasNext: Boolean = true
    override def next(): LmChunk  =
      reads += 1
      if reads == 1 then LmChunk(text = "Hi") else throw IllegalStateException("the stream broke")
    override def close(): Unit = closed = true

  /** A cache that counts its stores. */
  private final class CountingCache extends LmCache:
    private val store                                                = new InMemoryLmCache(CacheCapacity(16))
    var puts                                                         = 0
    override def get(request: LmRequest): Option[LmResponse]         = store.get(request)
    override def put(request: LmRequest, response: LmResponse): Unit =
      puts += 1
      store.put(request, response)

  private def lmHistory: Vector[HistoryEntry] =
    RuntimeEnvironment.current.history.filter(_.component == "lm:stub")

  private def field(entry: HistoryEntry, name: String): Option[DynamicValue] =
    DynamicValues.recordGet(entry.payload, name)

  private def bool(value: Boolean): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Boolean(value))

  private def text(value: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(value))

  private def lastEntry: HistoryEntry = lmHistory.lastOption.getOrElse(fail("no history entry"))

  private def memory: Option[LmCache] = Some(new InMemoryLmCache(CacheCapacity(16)))

  // ── A nested managed model ──────────────────────────────────────────────────────────────────────────────────

  test("a cache hit of an inner managed model is a hit of the outer model on the call path") {
    given RuntimeContext = RuntimeEnvironment.current
    val delegate         = Answering()
    val inner            = ManagedLanguageModel(delegate, cache = memory)
    val outerCache       = CountingCache()
    val outer            = ManagedLanguageModel(inner, cache = Some(outerCache))
    assert(inner.call(request).isRight)

    val (result, recorded) = UsageTracking.withNewTracker(tracker => (outer.call(request), tracker.totalUsage))
    assertEquals(result.map(_.cacheHit), Right(true))
    assertEquals(result.map(_.outputs.map(_.text)), Right(Vector("Hello")))
    assertEquals(outerCache.puts, 0, "the outer model must not store a hit of the inner model")
    assertEquals(field(lastEntry, "cache_hit"), Some(bool(true)))
    assertEquals(recorded, Map.empty, "a hit records no usage")
    assertEquals(delegate.calls, 1)
  }

  test("a cache hit of an inner managed model is a hit of the outer model on the stream path") {
    given RuntimeContext = RuntimeEnvironment.current
    val delegate         = Answering()
    val inner            = ManagedLanguageModel(delegate, cache = memory)
    val outerCache       = CountingCache()
    val outer            = ManagedLanguageModel(inner, cache = Some(outerCache))
    assertEquals(inner.stream(request).map(_.text).mkString, "Hello")

    val chunks = outer.stream(request).toVector
    assertEquals(chunks.size, 1, chunks.toString)
    assert(chunks.head.cachedResponse.exists(_.cacheHit), chunks.toString)
    assertEquals(outerCache.puts, 0, "the outer model must not store a hit of the inner model")
    assertEquals(field(lastEntry, "cache_hit"), Some(bool(true)))
    assertEquals(delegate.streams, 1)
  }

  // ── A delegate that throws ──────────────────────────────────────────────────────────────────────────────────

  test("a delegate call that throws gives an LmUnexpectedError and the failure entry") {
    given RuntimeContext = RuntimeEnvironment.current
    val delegate         = Throwing(Vector(() => throw IllegalStateException("boom")))
    ManagedLanguageModel(delegate).call(request) match
      case Left(error: LmUnexpectedError) =>
        assertEquals(error.message, "boom")
        assertEquals(error.details.model, Some("test-model"))
      case other => fail(s"expected an LmUnexpectedError, got $other")
    assertEquals(field(lastEntry, "error_code"), Some(text("unexpected")))
    assertEquals(field(lastEntry, "error_message"), Some(text("boom")))
  }

  test("the retry policy decides on the error of a delegate that throws") {
    given RuntimeContext = RuntimeEnvironment.current
    // The default rule retries only a transient error, and an unexpected error is not transient.
    val once = Throwing(Vector(() => throw IllegalStateException("boom"), () => response))
    assert(ManagedLanguageModel(once, retryPolicy = RetryPolicies.maxRetries(RetryCount(2))).call(request).isLeft)
    assertEquals(once.calls, 1)

    val retried = Throwing(Vector(() => throw IllegalStateException("boom"), () => response))
    val policy  = RetryPolicies.maxRetries(RetryCount(1), retryOn = _ => true)
    assertEquals(
      ManagedLanguageModel(retried, retryPolicy = policy).call(request).map(_.outputs.map(_.text)),
      Right(Vector("Hello"))
    )
    assertEquals(retried.calls, 2)
  }

  test("a fatal error of the delegate goes up to the caller") {
    given RuntimeContext = RuntimeEnvironment.current
    val delegate         = Throwing(Vector(() => throw InterruptedException("stop")))
    // `intercept` catches only non-fatal errors, so the test catches the fatal error itself.
    val thrown =
      try
        val _ = ManagedLanguageModel(delegate).call(request)
        None
      catch case error: InterruptedException => Some(error.getMessage)
    assertEquals(thrown, Some("stop"))
  }

  test("a stream delegate that throws when it opens the stream gives a failure chunk and the failure entry") {
    given RuntimeContext = RuntimeEnvironment.current
    val delegate         = Opening(() => throw IllegalStateException("no stream"))
    val chunks           = ManagedLanguageModel(delegate).stream(request).toVector
    chunks.lastOption.flatMap(_.error) match
      case Some(error: LmUnexpectedError) => assertEquals(error.message, "no stream")
      case other                          => fail(s"expected an LmUnexpectedError, got $other in $chunks")
    assertEquals(field(lastEntry, "error_code"), Some(text("unexpected")))
  }

  test("a stream that throws during a read gives a failure chunk and the failure entry, and the stream closes") {
    given RuntimeContext = RuntimeEnvironment.current
    val source           = Broken()
    val chunks           = ManagedLanguageModel(Opening(() => source)).stream(request).toVector
    assertEquals(chunks.headOption.map(_.text), Some("Hi"))
    chunks.lastOption.flatMap(_.error) match
      case Some(error: LmUnexpectedError) => assertEquals(error.message, "the stream broke")
      case other                          => fail(s"expected an LmUnexpectedError, got $other in $chunks")
    assertEquals(field(lastEntry, "error_code"), Some(text("unexpected")))
    assert(source.closed, "the managed model must close the stream of the delegate")
  }
