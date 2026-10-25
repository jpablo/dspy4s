package dspy4s.lm

import dspy4s.core.contracts.{DynamicValues, LmUsage, TokenCategory, ToolCall, :=}
import dspy4s.lm.contracts.{CacheKey, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.lm.runtime.{CacheCapacity, CompositeLmCache, DiskLmCache, InMemoryLmCache, RequestHash}
import munit.FunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class LmCacheSuite extends FunSuite:
  private val baseRequest = LmRequest(
    model = "test-model",
    mode = LmMode.Chat,
    options = DynamicValues.record("temperature" := 0.7)
  )

  private val key = CacheKey.of("test-model")

  private val baseResponse = LmResponse(
    outputs = Vector(LmOutput(text = "hello")),
    usage =
      Some(LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5, extras = Map(TokenCategory.Cached -> 3L)))
  )

  test("request hash is stable for equivalent map orderings") {
    val requestA = baseRequest.copy(
      options = DynamicValues.record("a" := 1, "nested" := Map("x" -> 1, "y" -> 2), "items" := Vector(1, 2, 3))
    )
    val requestB = baseRequest.copy(
      options = DynamicValues.record("items" := Vector(1, 2, 3), "nested" := Map("y" -> 2, "x" -> 1), "a" := 1)
    )

    val keyA = RequestHash.forRequest(requestA)
    assert(keyA.isRight, keyA)
    assertEquals(keyA, RequestHash.forRequest(requestB))
  }

  test("request hash distinguishes primitive types with equal text") {
    val asInt    = baseRequest.copy(options = DynamicValues.record("v" := 1))
    val asString = baseRequest.copy(options = DynamicValues.record("v" := "1"))

    assertNotEquals(RequestHash.forRequest(asInt), RequestHash.forRequest(asString))
  }

  test("request hash gives no key for a value with no JSON text") {
    val notFinite = baseRequest.copy(options = DynamicValues.record("temperature" := Double.NaN))
    val surrogate = baseRequest.copy(options = DynamicValues.record("stop" := "\uD83D"))
    assert(RequestHash.forRequest(notFinite).isLeft)
    assert(RequestHash.forRequest(surrogate).isLeft)
    assert(RequestHash.forRequest(baseRequest).isRight)
  }

  test("in-memory cache returns the stored response with its usage") {
    val cache = new InMemoryLmCache(maxEntries = CacheCapacity(8))
    cache.put(key, baseResponse)

    assertEquals(cache.get(key), Some(baseResponse))
  }

  test("disk cache persists values across cache instances") {
    val tempDir = Files.createTempDirectory("dspy4s-lm-disk-cache")
    try
      val first = new DiskLmCache(tempDir, maxEntries = CacheCapacity(8))
      first.put(key, baseResponse)
      assertEquals(first.size, 1)

      val second = new DiskLmCache(tempDir, maxEntries = CacheCapacity(8))
      assertEquals(second.get(key), Some(baseResponse))
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
      first.put(key, toolResponse)

      val second = new DiskLmCache(tempDir, maxEntries = CacheCapacity(8))
      val args   = DynamicValues.recordToMap(second.get(key).get.outputs.head.toolCalls.head.args)

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
      disk.put(key, baseResponse)

      val composite = CompositeLmCache(memory = memory, disk = disk)
      val first     = composite.get(key)
      val second    = composite.get(key)

      assertEquals(first, Some(baseResponse))
      assertEquals(second, Some(baseResponse))
      assertEquals(memory.size, 1)
    finally deleteRecursively(tempDir)
  }

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val children = Files.list(path)
        try children.iterator().asScala.foreach(child => deleteRecursively(child))
        finally children.close()
      val _ = Files.deleteIfExists(path)
