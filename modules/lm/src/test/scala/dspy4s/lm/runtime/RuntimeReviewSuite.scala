package dspy4s.lm.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.LmErrorDetails
import dspy4s.core.contracts.LmRateLimitError
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.CacheKey
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import munit.FunSuite

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import scala.concurrent.duration.DurationInt

/** The bugs of the code review of the lm runtime and contracts: each test failed before its fix. */
class RuntimeReviewSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val usage: LmUsage = LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5)

  private val request: LmRequest = LmRequest(model = "test-model")

  /** A model that answers each call with `output` and the usage, and counts the calls. */
  private final class Answering(output: LmOutput = LmOutput(text = "Hello")) extends LanguageModel:
    var calls                 = 0
    override val id: String   = "stub"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      calls += 1
      Right(LmResponse(outputs = Vector(output), usage = Some(usage)))

  private def lmHistory: Vector[HistoryEntry] =
    RuntimeEnvironment.current.history.filter(_.component == "lm:stub")

  private def memory = Some(new InMemoryLmCache(CacheCapacity(16)))

  private def tempDirectory(): Path = Files.createTempDirectory("dspy4s-runtime-review")

  private def response(text: String): LmResponse = LmResponse(outputs = Vector(LmOutput(text = text)))

  test("the retry hint of the provider has an upper limit") {
    val error  = LmRateLimitError("slow down", LmErrorDetails(retryAfter = Some(1.day)))
    val delays = Vector(
      RetryPolicies.maxRetries(RetryCount(2)).delayBeforeNextAttemptMillis(0, error),
      RetryPolicies.exponentialBackoff(RetryCount(2)).delayBeforeNextAttemptMillis(0, error)
    )
    delays.foreach(delay => assert(delay <= 60000L, s"a one-day hint gave a delay of $delay ms"))
  }

  test("a managed model over a managed model writes one history entry and one usage record for a miss") {
    given RuntimeContext = RuntimeEnvironment.current
    val delegate         = Answering()
    val outer            = ManagedLanguageModel(ManagedLanguageModel(delegate, cache = memory), cache = memory)
    val (result, total)  = UsageTracking.withNewTracker(tracker => (outer.call(request), tracker.totalUsage))
    assert(result.isRight, result.toString)
    assertEquals(lmHistory.size, 1, lmHistory.toString)
    assertEquals(total, Map("test-model" -> usage))
    assertEquals(delegate.calls, 1)
  }

  test("a managed model over a managed model writes one history entry for a hit of the inner model") {
    given RuntimeContext = RuntimeEnvironment.current
    val inner            = ManagedLanguageModel(Answering(), cache = memory)
    val outer            = ManagedLanguageModel(inner, cache = memory)
    assert(inner.call(request).isRight)
    val before = lmHistory.size
    assert(outer.call(request).exists(_.cacheHit))
    assertEquals(lmHistory.size - before, 1, lmHistory.toString)
  }

  test("a disk read error that is not a bad record keeps the file") {
    val directory = tempDirectory()
    val cache     = new DiskLmCache(directory)
    val key       = CacheKey.of("a key")
    // A directory at the path of the record: the read fails with an IOException, and the file is not a bad record.
    val path = Files.createDirectory(directory.resolve(s"${key.hex}.json"))
    assertEquals(cache.get(key), None)
    assert(Files.exists(path), "the get removed a path that it could not read")
  }

  test("the eviction keeps a record that a put rewrote after the listing") {
    val directory = tempDirectory()
    var rewrite   = Option.empty[Path]
    val files     = new DiskLmCache.EntryFiles:
      override def list(dir: Path): Vector[DiskLmCache.Entry] =
        val entries = DiskLmCache.EntryFiles.system.list(dir)
        // A put of another thread moves a new record into place after the listing.
        rewrite.foreach { path =>
          val _ = Files.write(path, Files.readAllBytes(path))
          val _ = Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 60000L))
        }
        entries
    val cache  = new DiskLmCache(directory, CacheCapacity(2), files)
    val first  = CacheKey.of("first")
    val second = CacheKey.of("second")
    cache.put(first, response("one"))
    cache.put(second, response("two"))
    val firstPath = directory.resolve(s"${first.hex}.json")
    val _         = Files.setLastModifiedTime(firstPath, FileTime.fromMillis(1000L))
    val _         = Files.setLastModifiedTime(directory.resolve(s"${second.hex}.json"), FileTime.fromMillis(2000L))
    rewrite = Some(firstPath)
    cache.put(CacheKey.of("third"), response("three"))
    assert(Files.exists(firstPath), "the eviction removed a record that a put wrote after the listing")
  }

  test("clear removes the temporary files that a stopped write left") {
    val directory = tempDirectory()
    val cache     = new DiskLmCache(directory)
    val temp      = Files.createFile(directory.resolve("0123abcd5550001.tmp"))
    cache.clear()
    assert(!Files.exists(temp), "clear left a temporary file")
  }

  test("a delegate that cannot stream gives the finish reason of its reply") {
    given RuntimeContext = RuntimeEnvironment.current
    val output           = LmOutput(text = "Hel", metadata = DynamicValues.record("finish_reason" := "length"))
    val chunks           = ManagedLanguageModel(Answering(output)).stream(request).toVector
    assertEquals(chunks.map(_.finishReason), Vector(Some("length")))
  }
