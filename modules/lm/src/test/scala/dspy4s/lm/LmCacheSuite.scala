package dspy4s.lm

import dspy4s.core.contracts.{DynamicValues, ToolCall, :=}
import dspy4s.lm.contracts.{LmMode, LmOutput, LmRequest, LmResponse, LmUsage, TokenCategory}
import dspy4s.lm.runtime.{
  CacheCapacity,
  CompositeLmCache,
  DiskLmCache,
  InMemoryLmCache,
  LmCacheConfig,
  LmCacheRegistry,
  NoopLmCache,
  RequestHash
}
import munit.FunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class LmCacheSuite extends FunSuite:
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
    LmCacheRegistry.resetDefault()

  override def afterEach(context: AfterEach): Unit =
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
    val cache = new InMemoryLmCache(maxEntries = CacheCapacity(8))
    cache.put(baseRequest, baseResponse)

    val cached = cache.get(baseRequest)
    assert(cached.isDefined)
    assertEquals(cached.get.cacheHit, true)
    assertEquals(cached.get.usage, None)
  }

  test("disk cache persists values across cache instances") {
    val tempDir = Files.createTempDirectory("dspy4s-lm-disk-cache")
    try
      val first = new DiskLmCache(tempDir, maxEntries = CacheCapacity(8))
      first.put(baseRequest, baseResponse)
      assertEquals(first.size, 1)

      val second = new DiskLmCache(tempDir, maxEntries = CacheCapacity(8))
      val cached = second.get(baseRequest)
      assert(cached.isDefined)
      assertEquals(cached.get.cacheHit, true)
      assertEquals(cached.get.usage, None)
    finally deleteRecursively(tempDir)
  }

  test("disk cache round-trips tool-call args faithfully (not stringified)") {
    val tempDir = Files.createTempDirectory("dspy4s-lm-disk-cache-tools")
    try
      val toolResponse = LmResponse(outputs =
        Vector(LmOutput(
          text = "",
          toolCalls = Vector(ToolCall(
            name = "search",
            args = DynamicValues.recordFromEntries(Seq("query" := "belgium", "top_k" := 3))
          ))
        ))
      )
      val first = new DiskLmCache(tempDir, maxEntries = CacheCapacity(8))
      first.put(baseRequest, toolResponse)

      val second = new DiskLmCache(tempDir, maxEntries = CacheCapacity(8))
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
      val disk   = new DiskLmCache(tempDir, maxEntries = CacheCapacity(8))
      val memory = new InMemoryLmCache(maxEntries = CacheCapacity(8))
      disk.put(baseRequest, baseResponse)

      val composite = CompositeLmCache(memory = Some(memory), disk = Some(disk))
      val first     = composite.get(baseRequest)
      val second    = composite.get(baseRequest)

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
      LmCacheConfig(enableDiskCache = false, enableMemoryCache = true, memoryMaxEntries = CacheCapacity(4))
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
        try children.iterator().asScala.foreach(child => deleteRecursively(child))
        finally children.close()
      val _ = Files.deleteIfExists(path)
