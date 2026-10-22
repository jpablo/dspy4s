package dspy4s.lm.runtime

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.TokenCategory
import dspy4s.core.contracts.ToolCall
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.providers.DynamicJson
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean

/** A class that records each time that Java serialization reads it. A legacy disk entry must not read it. */
final class UnexpectedPayload(val note: String) extends Serializable:
  private def readObject(in: ObjectInputStream): Unit =
    UnexpectedPayload.read.set(true)
    in.defaultReadObject()

object UnexpectedPayload:
  val read: AtomicBoolean = AtomicBoolean(false)

/** The disk record of [[DiskLmCache]]: a versioned JSON record in place of Java serialization (upstream
  * `CallResult.dump` and `CACHE_FORMAT` in `dspy/clients/call_result.py`; PORT_GAPS G-24, upgrade item L9).
  */
class DiskCacheRecordSuite extends FunSuite:

  private val request = LmRequest(model = "gpt-test", mode = LmMode.Chat, options = DynamicValues.record("n" := 1))

  private val response = LmResponse(
    outputs = Vector(LmOutput(
      text = "Paris",
      toolCalls = Vector(
        ToolCall(name = "search", args = DynamicValues.record("query" := "x", "top_k" := 3), id = Some("call_1")),
        ToolCall(name = "lookup", args = DynamicValue.Record.empty)
      ),
      metadata = DynamicValues.record("finish" := "stop")
    )),
    usage = Some(LmUsage(
      totalTokens = 9,
      promptTokens = 4,
      completionTokens = 5,
      extras = Map(TokenCategory.Cached -> 3L)
    )),
    modelName = Some("gpt-test-2026")
  )

  private def withDirectory[A](body: Path => A): A =
    val directory = Files.createTempDirectory("dspy4s-disk-record")
    try body(directory)
    finally
      val paths = Files.walk(directory)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path): Unit)
      finally paths.close()

  private def key: String = RequestHash.forRequest(request)

  private def jsonFile(directory  : Path): Path = directory.resolve(s"$key.json")
  private def legacyFile(directory: Path): Path = directory.resolve(s"$key.bin")

  /** A JSON value as plain Scala data: an object is an unordered map, and a number is a `BigDecimal`. */
  private def plain(value: DynamicValue): Any =
    value match
      case record: DynamicValue.Record =>
        DynamicValues.recordEntries(record).map((name, item) => name -> plain(item)).toMap
      case sequence: DynamicValue.Sequence                       => sequence.elements.toVector.map(plain)
      case DynamicValue.Primitive(PrimitiveValue.String(text))   => text
      case DynamicValue.Primitive(PrimitiveValue.Boolean(flag))  => flag
      case DynamicValue.Primitive(PrimitiveValue.Int(number))    => BigDecimal(number)
      case DynamicValue.Primitive(PrimitiveValue.Long(number))   => BigDecimal(number)
      case DynamicValue.Primitive(PrimitiveValue.Double(number)) => BigDecimal(number)
      case _: DynamicValue.Null.type                             => None
      case other                                                 => other.toString

  private def readJson(path: Path): Any =
    DynamicJson.decode(Files.readString(path)).fold(error => fail(s"not JSON: $error"), plain)

  /** The keys and values of a JSON object that [[plain]] gave. */
  private def objectOf(value: Any): Map[String, Any] =
    value match
      case map: Map[?, ?] => map.collect { case (name: String, item) => name -> item }
      case other          => fail(s"not an object: $other")

  private def writeLegacy(path: Path, value: AnyRef): Unit =
    val bytes = ByteArrayOutputStream()
    val out   = ObjectOutputStream(bytes)
    try out.writeObject(value)
    finally out.close()
    val _ = Files.write(path, bytes.toByteArray)

  private def legacyResponse: DiskCacheModel.PersistedResponse =
    val details = java.util.HashMap[String, java.lang.Long]()
    val _       = details.put("cached_tokens", java.lang.Long.valueOf(3L))
    DiskCacheModel.PersistedResponse(
      outputs = Array(DiskCacheModel.PersistedOutput(
        text = "Paris",
        toolCalls = Array(DiskCacheModel.PersistedToolCall(
          "search",
          DiskCacheModel.encodeRecord(DynamicValues.record("query" := "x")),
          "call_1"
        )),
        metadataJson = DiskCacheModel.encodeRecord(DynamicValue.Record.empty)
      )),
      usage = DiskCacheModel.PersistedUsage(9L, 4L, 5L, details),
      modelName = "gpt-test-2026"
    )

  // ── The JSON record ─────────────────────────────────────────────────────────────────────────────────────────

  test("put writes one JSON record with the format key and each field of the response") {
    withDirectory { directory =>
      DiskLmCache(directory).put(request, response)
      assert(Files.exists(jsonFile(directory)))
      assert(!Files.exists(legacyFile(directory)))
      val expected = Map(
        "_dspy4s_format" -> "dspy4s-lm-result-v1",
        "outputs"        -> Vector(Map(
          "text"       -> "Paris",
          "tool_calls" -> Vector(
            Map(
              "name" -> "search",
              "args" -> Map("query" -> "x", "top_k" -> BigDecimal(3)),
              "id"   -> "call_1"
            ),
            Map("name" -> "lookup", "args" -> Map.empty[String, Any])
          ),
          "metadata" -> Map("finish" -> "stop")
        )),
        "usage" -> Map(
          "total_tokens"      -> BigDecimal(9),
          "prompt_tokens"     -> BigDecimal(4),
          "completion_tokens" -> BigDecimal(5),
          "extras"            -> Map("cached_tokens" -> BigDecimal(3))
        ),
        "model_name" -> "gpt-test-2026"
      )
      assertEquals(readJson(jsonFile(directory)), expected)
      assertEquals(DiskLmCache.RecordFormat, "dspy4s-lm-result-v1")
    }
  }

  test("a response with no usage and no model name writes null for them") {
    withDirectory { directory =>
      DiskLmCache(directory).put(request, LmResponse(outputs = Vector(LmOutput(text = "x"))))
      val record = objectOf(readJson(jsonFile(directory)))
      assertEquals(record.get("usage"), Some(None))
      assertEquals(record.get("model_name"), Some(None))
    }
  }

  test("a JSON record round trips across cache instances") {
    withDirectory { directory =>
      DiskLmCache(directory).put(request, response)
      assertEquals(DiskLmCache(directory).get(request), Some(response.copy(cacheHit = true)))
    }
  }

  test("a file that is not JSON is a miss, and get removes it") {
    withDirectory { directory =>
      val _ = Files.writeString(jsonFile(directory), "not json")
      assertEquals(DiskLmCache(directory).get(request), None)
      assert(!Files.exists(jsonFile(directory)))
    }
  }

  test("a record with another format is a miss, and get removes it") {
    withDirectory { directory =>
      val _ = Files.writeString(jsonFile(directory), """{"_dspy4s_format": "other-v9", "outputs": []}""")
      assertEquals(DiskLmCache(directory).get(request), None)
      assert(!Files.exists(jsonFile(directory)))
    }
  }

  // ── Legacy entries ──────────────────────────────────────────────────────────────────────────────────────────

  test("a legacy entry is a hit, and get moves it to the JSON record") {
    withDirectory { directory =>
      writeLegacy(legacyFile(directory), legacyResponse)
      val cached = DiskLmCache(directory).get(request)
      assertEquals(cached.map(_.outputs.map(_.text)), Some(Vector("Paris")))
      assertEquals(cached.flatMap(_.outputs.head.toolCalls.headOption).flatMap(_.id), Some("call_1"))
      assertEquals(cached.map(_.cacheHit), Some(true))
      assert(Files.exists(jsonFile(directory)))
      assert(!Files.exists(legacyFile(directory)))
      // The moved record keeps the usage of the legacy entry.
      assertEquals(
        objectOf(readJson(jsonFile(directory))).get("usage"),
        Some(Map(
          "total_tokens"      -> BigDecimal(9),
          "prompt_tokens"     -> BigDecimal(4),
          "completion_tokens" -> BigDecimal(5),
          "extras"            -> Map("cached_tokens" -> BigDecimal(3))
        ))
      )
    }
  }

  test("a legacy entry with a class outside the record is a miss, and Java serialization does not read that class") {
    withDirectory { directory =>
      UnexpectedPayload.read.set(false)
      writeLegacy(legacyFile(directory), UnexpectedPayload("x"))
      assertEquals(DiskLmCache(directory).get(request), None)
      assert(!UnexpectedPayload.read.get(), "the filter must reject the class before Java serialization reads it")
      assert(!Files.exists(legacyFile(directory)))
    }
  }

  test("size and clear count the JSON entries and the legacy entries") {
    withDirectory { directory =>
      val cache = DiskLmCache(directory)
      cache.put(request.copy(model = "other"), response)
      writeLegacy(legacyFile(directory), legacyResponse)
      assertEquals(cache.size, 2)
      cache.clear()
      assertEquals(cache.size, 0)
    }
  }
