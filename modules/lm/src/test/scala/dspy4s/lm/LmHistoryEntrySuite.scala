package dspy4s.lm

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.LmAuthError
import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.TokenCategory
import dspy4s.core.contracts.ToolCall
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.ContentPart
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.lm.runtime.CacheCapacity
import dspy4s.lm.runtime.InMemoryLmCache
import dspy4s.lm.runtime.ManagedLanguageModel
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

import java.util.UUID

/** The history entry of an LM call holds the messages and the outputs (upstream `finalize` in
  * `dspy/clients/execution.py`; PORT_GAPS G-27, upgrade item I11).
  */
class LmHistoryEntrySuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class Fixed(result: Either[DspyError, LmResponse], override val mode: LmMode = LmMode.Chat)
      extends LanguageModel:
    override val id: String                                                                    = "fixed"
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] = result

  private final class Streams(chunks: Vector[LmChunk]) extends StreamingLanguageModel:
    override val id: String                                                                    = "fixed"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "the non-stream path is not used"))
    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] = chunks.iterator

  private val search = ToolCall(name = "search", args = DynamicValues.record("query" := "cats"), id = Some("c1"))

  private val request = LmRequest(
    model = "gpt-test",
    messages = Vector(
      Message(role = MessageRole.System, text = Some("Be terse.")),
      Message(
        role = MessageRole.User,
        text = Some("Find cats."),
        parts = Vector(ContentPart(kind = "image_url", payload = "data:x"))
      ),
      Message(role = MessageRole.Assistant, toolCalls = Vector(search)),
      Message(role = MessageRole.Tool, text = Some("3 cats"), toolCallId = Some("c1"), name = Some("search"))
    ),
    options = DynamicValues.record("temperature" := 0.5)
  )

  private val response = LmResponse(
    outputs = Vector(
      LmOutput(
        text = "There are 3 cats.",
        toolCalls = Vector(ToolCall(name = "lookup", args = DynamicValue.Record.empty))
      ),
      LmOutput(text = "Three.")
    ),
    usage =
      Some(LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5, extras = Map(TokenCategory.Cached -> 3L))),
    modelName = Some("gpt-test-2026")
  )

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

  private def lmEntries: Vector[HistoryEntry] = RuntimeEnvironment.current.history.filter(_.component == "lm:fixed")

  private def keys(entry: HistoryEntry): Vector[String] = entry.payload.fields.toVector.map(_._1)

  private def value(entry: HistoryEntry, key: String): Any =
    DynamicValues.recordGet(entry.payload, key).map(plain).getOrElse(fail(s"no key $key in ${entry.payload}"))

  private val expectedMessages: Vector[Map[String, Any]] = Vector(
    Map("role" -> "system", "content" -> "Be terse."),
    Map(
      "role"    -> "user",
      "content" -> "Find cats.",
      "parts"   -> Vector(Map("kind" -> "image_url", "payload" -> "data:x"))
    ),
    Map(
      "role"       -> "assistant",
      "content"    -> "",
      "tool_calls" -> Vector(Map("name" -> "search", "args" -> Map("query" -> "cats"), "id" -> "c1"))
    ),
    Map("role" -> "tool", "content" -> "3 cats", "tool_call_id" -> "c1", "name" -> "search")
  )

  test("the entry of a call has the upstream keys, the messages, the options, the outputs, and the usage") {
    given RuntimeContext = RuntimeEnvironment.current
    assert(ManagedLanguageModel(delegate = Fixed(Right(response))).call(request).isRight)
    val entry = lmEntries.lastOption.getOrElse(fail("no LM entry"))
    assertEquals(
      keys(entry),
      Vector(
        "model",
        "model_type",
        "cache_hit",
        "messages",
        "kwargs",
        "outputs",
        "usage",
        "cost",
        "cost_details",
        "response_model",
        "uuid"
      )
    )
    assertEquals(value(entry, "model"), "gpt-test")
    assertEquals(value(entry, "model_type"), "chat")
    assertEquals(value(entry, "cache_hit"), false)
    assertEquals(value(entry, "messages"), expectedMessages)
    assertEquals(value(entry, "kwargs"), Map("temperature" -> BigDecimal(0.5)))
    assertEquals(
      value(entry, "outputs"),
      Vector(
        Map(
          "text"       -> "There are 3 cats.",
          "tool_calls" -> Vector(Map("name" -> "lookup", "args" -> Map.empty[String, Any]))
        ),
        Map("text" -> "Three.", "tool_calls" -> Vector.empty)
      )
    )
    assertEquals(
      value(entry, "usage"),
      Map(
        "prompt_tokens"     -> BigDecimal(4),
        "completion_tokens" -> BigDecimal(5),
        "total_tokens"      -> BigDecimal(9),
        "cached_tokens"     -> BigDecimal(3)
      )
    )
    assertEquals(value(entry, "response_model"), "gpt-test-2026")
    value(entry, "uuid") match
      case text: String => assertEquals(UUID.fromString(text).toString, text)
      case other        => fail(s"the uuid is not a string: $other")
  }

  test("the model_type of an entry is the mode of the model, for a success and for a failure") {
    given RuntimeContext = RuntimeEnvironment.current
    Vector(LmMode.Chat, LmMode.Text, LmMode.Responses).foreach { mode =>
      assert(ManagedLanguageModel(delegate = Fixed(Right(response), mode)).call(request).isRight)
      assert(ManagedLanguageModel(delegate = Fixed(Left(LmAuthError("bad key")), mode)).call(request).isLeft)
    }
    assertEquals(
      lmEntries.map(value(_, "model_type")),
      Vector("chat", "chat", "text", "text", "responses", "responses")
    )
  }

  test("the entry of a cache hit has cache_hit, and two entries have different uuids") {
    given RuntimeContext = RuntimeEnvironment.current
    val model            = ManagedLanguageModel(
      delegate = Fixed(Right(response)),
      cache = Some(new InMemoryLmCache(CacheCapacity(4)))
    )
    assert(model.call(request).isRight)
    assert(model.call(request).isRight)
    val entries = lmEntries
    assertEquals(entries.map(value(_, "cache_hit")), Vector(false, true))
    assertEquals(value(entries(1), "messages"), expectedMessages)
    assertNotEquals(value(entries(0), "uuid"), value(entries(1), "uuid"))
  }

  test("the entry of a failure has the messages and the error") {
    given RuntimeContext = RuntimeEnvironment.current
    assert(ManagedLanguageModel(delegate = Fixed(Left(LmAuthError("bad key")))).call(request).isLeft)
    val entry = lmEntries.lastOption.getOrElse(fail("no LM entry"))
    assertEquals(
      keys(entry),
      Vector("model", "model_type", "cache_hit", "messages", "kwargs", "error_code", "error_message", "uuid")
    )
    assertEquals(value(entry, "messages"), expectedMessages)
    assertEquals(value(entry, "error_code"), "auth")
    assertEquals(value(entry, "error_message"), "bad key")
  }

  test("the kwargs of an entry have no top-level key that starts with api_ (upstream finalize)") {
    given RuntimeContext = RuntimeEnvironment.current
    val withSecrets      = request.copy(options =
      DynamicValues.record(
        "api_key"     := "sk-secret",
        "temperature" := 0.5,
        "api_base"    := "https://example.test/v1",
        "extra_body"  -> DynamicValues.record("api_version" := "2024-01-01")
      )
    )
    assert(ManagedLanguageModel(delegate = Fixed(Right(response))).call(withSecrets).isRight)
    assert(ManagedLanguageModel(delegate = Fixed(Left(LmAuthError("bad key")))).call(withSecrets).isLeft)
    val entries = lmEntries
    assertEquals(entries.size, 2)
    entries.foreach { entry =>
      val kwargs = DynamicValues.recordGet(entry.payload, "kwargs") match
        case Some(record: DynamicValue.Record) => record
        case other                             => fail(s"kwargs is not an object: $other")
      // Upstream removes only the top-level keys, so a nested key that starts with `api_` stays.
      assertEquals(kwargs.fields.toVector.map(_._1), Vector("temperature", "extra_body"))
      assertEquals(
        value(entry, "kwargs"),
        Map("temperature" -> BigDecimal(0.5), "extra_body" -> Map("api_version" -> "2024-01-01"))
      )
    }
  }

  test("the entry of a streamed call has the assembled outputs and the usage of the chunks") {
    given RuntimeContext = RuntimeEnvironment.current
    val chunks           = Vector(
      LmChunk(text = "There are "),
      LmChunk(text = "3 cats.", finishReason = Some("stop")),
      LmChunk(usage = Some(LmUsage(totalTokens = 9, promptTokens = 4, completionTokens = 5)))
    )
    val _     = ManagedLanguageModel(delegate = Streams(chunks)).stream(request).toVector
    val entry = lmEntries.lastOption.getOrElse(fail("no LM entry"))
    assertEquals(value(entry, "messages"), expectedMessages)
    assertEquals(value(entry, "outputs"), Vector(Map("text" -> "There are 3 cats.", "tool_calls" -> Vector.empty)))
    assertEquals(
      value(entry, "usage"),
      Map("prompt_tokens" -> BigDecimal(4), "completion_tokens" -> BigDecimal(5), "total_tokens" -> BigDecimal(9))
    )
  }
