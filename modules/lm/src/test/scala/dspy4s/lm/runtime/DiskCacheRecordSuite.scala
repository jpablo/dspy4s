package dspy4s.lm.runtime

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.TokenCategory
import dspy4s.core.contracts.ToolCall
import dspy4s.lm.contracts.CacheKey
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.providers.DynamicJson
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/** The disk record of [[DiskLmCache]]: a versioned JSON record in place of Java serialization (upstream
  * `CallResult.dump` and `CACHE_FORMAT` in `dspy/clients/call_result.py`; PORT_GAPS G-24, upgrade item L9).
  */
class DiskCacheRecordSuite extends FunSuite:

  private val key = CacheKey.of("gpt-test")

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

  private def jsonFile(directory: Path): Path = directory.resolve(s"${key.hex}.json")

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

  // ── The JSON record ─────────────────────────────────────────────────────────────────────────────────────────

  test("put writes one JSON record with the format key and each field of the response") {
    withDirectory { directory =>
      DiskLmCache(directory).put(key, response)
      assert(Files.exists(jsonFile(directory)))
      val expected = Map(
        "_dspy4s_format" -> "dspy4s-lm-result-v2",
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
      assertEquals(DiskLmCache.RecordFormat, "dspy4s-lm-result-v2")
    }
  }

  test("a response with no usage, no model name, and no tool call has no key for them") {
    withDirectory { directory =>
      DiskLmCache(directory).put(key, LmResponse(outputs = Vector(LmOutput(text = "x"))))
      val record = objectOf(readJson(jsonFile(directory)))
      assertEquals(record.keySet, Set("_dspy4s_format", "outputs"))
      assertEquals(record.get("outputs"), Some(Vector(Map("text" -> "x", "metadata" -> Map.empty[String, Any]))))
    }
  }

  test("the reasoning and the citations round trip, and absent keys read as no reasoning and no citation") {
    val citation = DynamicValues.record("type" := "citation", "url" := "https://x.test", "title" := "X")
    val reasoned =
      LmResponse(outputs = Vector(LmOutput(text = "x", reasoning = "Think.", citations = Vector(citation))))
    withDirectory { directory =>
      DiskLmCache(directory).put(key, reasoned)
      val outputs = objectOf(readJson(jsonFile(directory))).get("outputs")
      assertEquals(
        outputs,
        Some(Vector(Map(
          "text"      -> "x",
          "reasoning" -> "Think.",
          "citations" -> Vector(Map("type" -> "citation", "url" -> "https://x.test", "title" -> "X")),
          "metadata"  -> Map.empty[String, Any]
        )))
      )
      assertEquals(DiskLmCache(directory).get(key), Some(reasoned))
    }
    withDirectory { directory =>
      val text = """{"_dspy4s_format": "dspy4s-lm-result-v2", "outputs": [{"text": "x", "metadata": {}}]}"""
      val _    = Files.writeString(jsonFile(directory), text)
      assertEquals(DiskLmCache(directory).get(key), Some(LmResponse(outputs = Vector(LmOutput(text = "x")))))
    }
  }

  test("a JSON record round trips across cache instances") {
    withDirectory { directory =>
      DiskLmCache(directory).put(key, response)
      assertEquals(DiskLmCache(directory).get(key), Some(response))
    }
  }

  test("a file that is not JSON is a miss, and get removes it") {
    withDirectory { directory =>
      val _ = Files.writeString(jsonFile(directory), "not json")
      assertEquals(DiskLmCache(directory).get(key), None)
      assert(!Files.exists(jsonFile(directory)))
    }
  }

  test("a record with another format is a miss, and get removes it") {
    withDirectory { directory =>
      val _ = Files.writeString(jsonFile(directory), """{"_dspy4s_format": "other-v9", "outputs": []}""")
      assertEquals(DiskLmCache(directory).get(key), None)
      assert(!Files.exists(jsonFile(directory)))
    }
  }

  test("a record with a value of the wrong JSON type is a miss, and get removes it") {
    withDirectory { directory =>
      val text = """{"_dspy4s_format": "dspy4s-lm-result-v2", "outputs": [{"text": "x", "metadata": []}]}"""
      val _    = Files.writeString(jsonFile(directory), text)
      assertEquals(DiskLmCache(directory).get(key), None)
      assert(!Files.exists(jsonFile(directory)))
    }
  }

  test("size and clear count the JSON entries") {
    withDirectory { directory =>
      val cache = DiskLmCache(directory)
      cache.put(CacheKey.of("other"), response)
      cache.put(key, response)
      assertEquals(cache.size, 2)
      cache.clear()
      assertEquals(cache.size, 0)
    }
  }
