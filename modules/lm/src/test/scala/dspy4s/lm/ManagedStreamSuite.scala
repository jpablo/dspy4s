package dspy4s.lm

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.LmAuthError
import dspy4s.core.contracts.LmError
import dspy4s.core.contracts.LmErrorDetails
import dspy4s.core.contracts.LmServerError
import dspy4s.core.contracts.LmStreamAssemblyError
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.lm.contracts.RetryPolicy
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.lm.runtime.CacheCapacity
import dspy4s.lm.runtime.InMemoryLmCache
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.lm.runtime.RetryCount
import dspy4s.lm.runtime.RetryPolicies
import dspy4s.lm.runtime.UsageTracker
import dspy4s.lm.runtime.UsageTracking
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt

/** A streamed call through [[ManagedLanguageModel]] gets the rules of a call (upstream `dspy/clients/execution.py`
  * `execute` and `_attempt`, and `dspy/clients/engines/stream_guard.py`).
  */
class ManagedStreamSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A stream model that gives the chunks of the next attempt for each stream request. */
  private final class ScriptedStreams(attempts: Vector[Vector[LmChunk]]) extends StreamingLanguageModel:
    private var remaining = attempts
    var streams           = 0

    override val id: String   = "stub"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "the non-stream path is not used"))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      streams += 1
      remaining.headOption match
        case Some(chunks) =>
          remaining = remaining.tail
          chunks.iterator
        case None => Iterator.single(LmChunk.failure(RuntimeError("test", "no more scripted streams")))

  private val request: LmRequest = LmRequest(model = "test-model")

  private val usage: LmUsage = LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5)

  private val complete: Vector[LmChunk] = Vector(
    LmChunk(text = "Hel"),
    LmChunk(text = "lo", finishReason = Some("stop")),
    LmChunk(usage = Some(usage))
  )

  private val twoRetries: RetryPolicy = RetryPolicies.maxRetries(RetryCount(2))

  private def managed(
      delegate: ScriptedStreams,
      cached  : Boolean           = false,
      retry   : RetryPolicy       = RetryPolicies.never,
      sleeps  : ArrayBuffer[Long] = ArrayBuffer.empty
  ): ManagedLanguageModel =
    ManagedLanguageModel(
      delegate = delegate,
      cache = Option.when(cached)(new InMemoryLmCache(CacheCapacity(16))),
      retryPolicy = retry,
      sleep = millis => sleeps += millis
    )

  private def streamAll(model: ManagedLanguageModel)(using RuntimeContext): Vector[LmChunk] =
    model.stream(request).toVector

  private def lastError(chunks: Vector[LmChunk]): DspyError =
    chunks.lastOption.flatMap(_.error).getOrElse(fail(s"the stream has no error: $chunks"))

  private def lmHistory: Vector[HistoryEntry] =
    RuntimeEnvironment.current.history.filter(_.component == "lm:stub")

  private def field(entry: HistoryEntry, name: String): Option[DynamicValue] =
    DynamicValues.recordGet(entry.payload, name)

  private def bool(value: Boolean): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Boolean(value))

  // ── The stream assembly error ───────────────────────────────────────────────────────────────────────────────

  test("a stream assembly error has the upstream code and is not retryable") {
    val error = LmStreamAssemblyError("the stream ended early")
    assertEquals(error.code, "stream_assembly")
    assert(!LmError.isRetryable(error))
  }

  // ── Cache ───────────────────────────────────────────────────────────────────────────────────────────────────

  test("a cache hit gives one cached chunk, and the delegate gets no second request") {
    val delegate         = ScriptedStreams(Vector(complete))
    val model            = managed(delegate, cached = true)
    given RuntimeContext = RuntimeEnvironment.current

    val first = streamAll(model)
    assertEquals(first.map(_.text).mkString, "Hello")

    val second = streamAll(model)
    assertEquals(second.size, 1, second.toString)
    val hit = second.head
    assertEquals(hit.text, "Hello")
    assertEquals(hit.finishReason, Some("stop"))
    assertEquals(hit.usage, None)
    hit.cachedResponse match
      case Some(response) =>
        assert(response.cacheHit)
        assertEquals(response.outputs.map(_.text), Vector("Hello"))
      case None => fail(s"the chunk of a cache hit has no response: $hit")
    assertEquals(delegate.streams, 1)
  }

  test("the cache does not store a failed stream") {
    val failed           = Vector(LmChunk(text = "Hi"), LmChunk.failure(LmServerError("busy")))
    val delegate         = ScriptedStreams(Vector(failed, complete))
    val model            = managed(delegate, cached = true)
    given RuntimeContext = RuntimeEnvironment.current

    assert(lastError(streamAll(model)).isInstanceOf[LmServerError])
    val second = streamAll(model)
    assertEquals(second.map(_.text).mkString, "Hello")
    assert(second.forall(_.cachedResponse.isEmpty), second.toString)
    assertEquals(delegate.streams, 2)
  }

  // ── Stream guard ────────────────────────────────────────────────────────────────────────────────────────────

  test("a stream with no finish reason ends with a stream assembly error, and the cache does not store it") {
    val delegate         = ScriptedStreams(Vector(Vector(LmChunk(text = "Hi")), complete))
    val model            = managed(delegate, cached = true)
    given RuntimeContext = RuntimeEnvironment.current

    val first = streamAll(model)
    assertEquals(first.map(_.text).mkString, "Hi")
    assert(lastError(first).isInstanceOf[LmStreamAssemblyError], first.toString)
    assertEquals(streamAll(model).map(_.text).mkString, "Hello")
    assertEquals(delegate.streams, 2)
  }

  test("an empty stream gets a stream assembly error and no retry, because the error is not transient") {
    val delegate         = ScriptedStreams(Vector(Vector.empty, complete))
    val model            = managed(delegate, retry = twoRetries)
    given RuntimeContext = RuntimeEnvironment.current

    assert(lastError(streamAll(model)).isInstanceOf[LmStreamAssemblyError])
    assertEquals(delegate.streams, 1)
  }

  // ── Retry ───────────────────────────────────────────────────────────────────────────────────────────────────

  test("a failure before the first chunk with text gets a retry, and the caller gets no chunk of that attempt") {
    val busy             = LmServerError("busy", LmErrorDetails(retryAfter = Some(5.millis)))
    val delegate         = ScriptedStreams(Vector(Vector(LmChunk(text = ""), LmChunk.failure(busy)), complete))
    val sleeps           = ArrayBuffer.empty[Long]
    val model            = managed(delegate, retry = twoRetries, sleeps = sleeps)
    given RuntimeContext = RuntimeEnvironment.current

    assertEquals(streamAll(model), complete)
    assertEquals(delegate.streams, 2)
    assertEquals(sleeps.toVector, Vector(5L))
  }

  test("a failure after the first chunk with text ends the stream with no retry") {
    val failed           = Vector(LmChunk(text = "Hi"), LmChunk.failure(LmServerError("busy")))
    val delegate         = ScriptedStreams(Vector(failed, complete))
    val model            = managed(delegate, retry = twoRetries)
    given RuntimeContext = RuntimeEnvironment.current

    val chunks = streamAll(model)
    assertEquals(chunks.map(_.text).mkString, "Hi")
    assert(lastError(chunks).isInstanceOf[LmServerError])
    assertEquals(delegate.streams, 1)
  }

  test("a failure after the first chunk with a tool call ends the stream with no retry") {
    val call             = LmToolCallDelta(index = 0, id = Some("c1"), name = Some("search"))
    val failed           = Vector(LmChunk(toolCalls = Vector(call)), LmChunk.failure(LmServerError("busy")))
    val delegate         = ScriptedStreams(Vector(failed, complete))
    val model            = managed(delegate, retry = twoRetries)
    given RuntimeContext = RuntimeEnvironment.current

    assert(lastError(streamAll(model)).isInstanceOf[LmServerError])
    assertEquals(delegate.streams, 1)
  }

  test("an error that is not transient gets no retry") {
    val delegate         = ScriptedStreams(Vector(Vector(LmChunk.failure(LmAuthError("bad key"))), complete))
    val model            = managed(delegate, retry = twoRetries)
    given RuntimeContext = RuntimeEnvironment.current

    assert(lastError(streamAll(model)).isInstanceOf[LmAuthError])
    assertEquals(delegate.streams, 1)
  }

  // ── History ─────────────────────────────────────────────────────────────────────────────────────────────────

  test("each streamed call appends one history entry, also with a retry and for a cache hit") {
    val busy             = Vector(LmChunk.failure(LmServerError("busy")))
    val delegate         = ScriptedStreams(Vector(busy, complete))
    val model            = managed(delegate, cached = true, retry = twoRetries)
    given RuntimeContext = RuntimeEnvironment.current

    val _ = streamAll(model)
    assertEquals(lmHistory.size, 1)
    assertEquals(field(lmHistory.head, "cache_hit"), Some(bool(false)))

    val _ = streamAll(model)
    assertEquals(lmHistory.size, 2)
    assertEquals(field(lmHistory(1), "cache_hit"), Some(bool(true)))
  }

  test("a failed streamed call appends the failure entry") {
    val delegate         = ScriptedStreams(Vector(Vector(LmChunk.failure(LmAuthError("bad key")))))
    val model            = managed(delegate)
    given RuntimeContext = RuntimeEnvironment.current

    val _ = streamAll(model)
    assertEquals(lmHistory.size, 1)
    assertEquals(field(lmHistory.head, "error_code"), Some(DynamicValues.fromAny("auth")))
  }

  // ── Usage ───────────────────────────────────────────────────────────────────────────────────────────────────

  test("the usage of a streamed call is recorded, and a cache hit records no usage") {
    val delegate         = ScriptedStreams(Vector(complete))
    val model            = managed(delegate, cached = true)
    val tracker          = new UsageTracker
    given RuntimeContext = RuntimeEnvironment.current

    UsageTracking.withTracker(tracker) {
      val _ = streamAll(model)
      val _ = streamAll(model)
    }
    assertEquals(tracker.usageData.get("test-model").map(_.size), Some(1))
    assertEquals(tracker.totalUsage("test-model").totalTokens, 9L)
  }

  test("the usage of a chunk before a failure is recorded") {
    val failed           = Vector(LmChunk(text = "Hi"), LmChunk(usage = Some(usage)), LmChunk.failure(LmServerError("x")))
    val delegate         = ScriptedStreams(Vector(failed))
    val model            = managed(delegate)
    val tracker          = new UsageTracker
    given RuntimeContext = RuntimeEnvironment.current

    UsageTracking.withTracker(tracker) {
      val _ = streamAll(model)
    }
    assertEquals(tracker.totalUsage.get("test-model").map(_.totalTokens), Some(9L))
  }
