package dspy4s.lm.runtime

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.ValidationError
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.providers.DynamicJson
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** The work of a `put` of [[DiskLmCache]]: the count of the entries, the eviction of the oldest entries, and a response
  * that has no JSON text. Upstream `diskcache.FanoutCache` keeps the count in its index, and `Cache.put` logs a value
  * that it cannot write and continues (`dspy/clients/cache.py`).
  */
class DiskLmCachePutSuite extends FunSuite:

  /** The operations of the file system, with a count of each directory listing and of each time read. */
  private final class CountingFiles extends DiskLmCache.EntryFiles:
    val listings: AtomicInteger  = AtomicInteger(0)
    val timeReads: AtomicInteger = AtomicInteger(0)

    override def list(directory: Path): Vector[Path] =
      val _ = listings.incrementAndGet()
      DiskLmCache.EntryFiles.system.list(directory)

    override def modifiedMillis(path: Path): Long =
      val _ = timeReads.incrementAndGet()
      DiskLmCache.EntryFiles.system.modifiedMillis(path)

    def reset(): Unit =
      listings.set(0)
      timeReads.set(0)

  private val response = LmResponse(outputs = Vector(LmOutput(text = "Paris")), modelName = Some("gpt-test"))

  private def requestOf(index: Int): LmRequest =
    LmRequest(model = "gpt-test", mode = LmMode.Chat, options = DynamicValues.record("n" := index))

  private def withDirectory[A](body: Path => A): A =
    val directory = Files.createTempDirectory("dspy4s-disk-put")
    try body(directory)
    finally
      val paths = Files.walk(directory)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path): Unit)
      finally paths.close()

  /** The names of all files in `directory`. */
  private def fileNames(directory: Path): Vector[String] =
    val stream = Files.list(directory)
    try stream.iterator().asScala.map(_.getFileName.toString).toVector
    finally stream.close()

  /** Set the time of the last change of the record of `requestOf(index)` to `index` seconds after the epoch. */
  private def age(directory: Path, index: Int): Unit =
    val path = directory.resolve(s"${RequestHash.forRequest(requestOf(index))}.json")
    val _    = Files.setLastModifiedTime(path, FileTime.fromMillis(index * 1000L))

  /** A record nested `depth` levels deep. The function builds it with a loop, so it does not use the stack. */
  private def nested(depth: Int): DynamicValue.Record = (1 until depth).foldLeft(DynamicValue.Record(Chunk("leaf" ->
    DynamicValue.Primitive(PrimitiveValue.Int(1))))) {
    (inner, _) => DynamicValue.Record(Chunk("a" -> inner))
  }

  // ── The count and the eviction (B14.1) ─────────────────────────────────────────────────────────────────────

  test("a put below the cap lists the directory one time at most, and reads no time") {
    withDirectory { directory =>
      val files = CountingFiles()
      val cache = new DiskLmCache(directory, CacheCapacity(100), files)
      (1 to 20).foreach(index => cache.put(requestOf(index), response))
      assert(files.listings.get() <= 1, s"20 puts listed the directory ${files.listings.get()} times")
      assertEquals(files.timeReads.get(), 0, "a put with no overflow must not read the time of an entry")
      assertEquals(cache.size, 20)
    }
  }

  test("an eviction reads the time of each entry one time, and removes the oldest entries down to 90% of the cap") {
    withDirectory { directory =>
      val files = CountingFiles()
      val cache = new DiskLmCache(directory, CacheCapacity(10), files)
      (1 to 10).foreach { index =>
        cache.put(requestOf(index), response)
        age(directory, index)
      }
      files.reset()
      cache.put(requestOf(11), response)
      assertEquals(files.listings.get(), 1)
      assertEquals(files.timeReads.get(), 11, "the eviction must read the time of each of the 11 entries one time")
      assertEquals(cache.size, 9)
      (1 to 2).foreach(index => assert(cache.get(requestOf(index)).isEmpty, s"the old entry $index must go"))
      (3 to 11).foreach(index => assert(cache.get(requestOf(index)).isDefined, s"entry $index must stay"))
    }
  }

  // Upstream `diskcache` culls through its SQLite index: each `set` at the size limit removes up to `cull_limit`
  // entries. The port removes 10% of the cap at one time, so the puts after an eviction do not list the directory.
  test("after an eviction, the puts do not list the directory until the count passes the cap again") {
    withDirectory { directory =>
      val files = CountingFiles()
      val cache = new DiskLmCache(directory, CacheCapacity(10), files)
      (1 to 11).foreach { index =>
        cache.put(requestOf(index), response)
        age(directory, index)
      }
      files.reset()
      cache.put(requestOf(12), response)
      assertEquals(files.listings.get(), 0, "the cache has 10 entries after the put, so it must not list")
      assertEquals(files.timeReads.get(), 0)
      age(directory, 12)
      cache.put(requestOf(13), response)
      assertEquals(files.listings.get(), 1, "the cache has 11 entries after the put, so it must evict")
      assertEquals(cache.size, 9)
      assertEquals((1 to 13).filter(index => cache.get(requestOf(index)).isDefined).toVector, (5 to 13).toVector)
    }
  }

  test("the eviction of a cap of 1 keeps the entry of the put") {
    withDirectory { directory =>
      val cache = new DiskLmCache(directory, CacheCapacity(1), CountingFiles())
      cache.put(requestOf(1), response)
      age(directory, 1)
      cache.put(requestOf(2), response)
      assertEquals(cache.size, 1)
      assert(cache.get(requestOf(2)).isDefined, "the entry of the put must stay")
    }
  }

  test(
    "the first put counts the entries that the directory has, and the eviction counts the entries of a second cache"
  ) {
    withDirectory { directory =>
      val other = DiskLmCache(directory)
      (1 to 3).foreach { index =>
        other.put(requestOf(index), response)
        age(directory, index)
      }
      // The cap is 3, so an eviction keeps 2 entries (90% of the cap, rounded down).
      val cache = new DiskLmCache(directory, CacheCapacity(3), CountingFiles())
      cache.put(requestOf(4), response)
      age(directory, 4)
      assertEquals(cache.size, 2)
      assertEquals((1 to 4).filter(index => cache.get(requestOf(index)).isDefined).toVector, Vector(3, 4))

      // The second cache writes two entries. The count of the first cache does not include them, so the directory can
      // hold more entries than the cap, until a put of the first cache passes the cap. That put counts the directory
      // again and removes the oldest entries.
      (5 to 6).foreach { index =>
        other.put(requestOf(index), response)
        age(directory, index)
      }
      cache.put(requestOf(7), response)
      age(directory, 7)
      assertEquals(cache.size, 5)
      cache.put(requestOf(8), response)
      assertEquals(cache.size, 2)
      assertEquals((3 to 8).filter(index => cache.get(requestOf(index)).isDefined).toVector, Vector(7, 8))
    }
  }

  test("a get that removes a file that is not a record lowers the count") {
    withDirectory { directory =>
      val files = CountingFiles()
      val cache = new DiskLmCache(directory, CacheCapacity(2), files)
      cache.put(requestOf(1), response)
      age(directory, 1)
      cache.put(requestOf(2), response)
      age(directory, 2)
      val _ = Files.writeString(directory.resolve(s"${RequestHash.forRequest(requestOf(2))}.json"), "not json")
      assert(cache.get(requestOf(2)).isEmpty)
      files.reset()
      cache.put(requestOf(3), response)
      assertEquals(files.timeReads.get(), 0, "the cache has 2 entries after the put, so it must not evict")
      assertEquals(cache.size, 2)
    }
  }

  // ── A response with no JSON text (B14.2) ───────────────────────────────────────────────────────────────────

  test("a put of tool-call args nested 100,000 levels deep does not throw and stores nothing, and the next put works") {
    withDirectory { directory =>
      val cache = DiskLmCache(directory)
      val deep  =
        LmResponse(outputs = Vector(LmOutput(text = "x", toolCalls = Vector(ToolCall("deep", nested(100000))))))
      // `intercept` catches only non-fatal errors, so the test catches the StackOverflowError itself.
      val thrown =
        try
          cache.put(requestOf(1), deep)
          None
        catch case error: StackOverflowError => Some(error.getClass.getSimpleName)
      assertEquals(thrown, None)
      assert(cache.get(requestOf(1)).isEmpty, "the cache must not store a response that it cannot encode")
      assertEquals(fileNames(directory), Vector.empty, "the failed put must leave no file")

      cache.put(requestOf(2), response)
      assertEquals(cache.get(requestOf(2)), Some(response.copy(cacheHit = true)))
    }
  }

  test(
    "the checked encoder gives a ValidationError for a value nested 100,000 levels deep, and the next encode works"
  ) {
    DynamicJson.encodeExactChecked(nested(100000)) match
      case Left(error: ValidationError) => assert(error.message.contains("nested too deep"), error.message)
      case Left(other)                  => fail(s"expected a ValidationError, got $other")
      case Right(json)                  => fail(s"expected a ValidationError, got ${json.length} characters")
    assertEquals(DynamicJson.encodeExactChecked(nested(3)), Right("""{"a":{"a":{"leaf":1}}}"""))
  }
