package dspy4s.lm

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.:=
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.lm.runtime.CacheCapacity
import dspy4s.lm.runtime.DiskLmCache
import dspy4s.lm.runtime.ProviderRequestNormalizer
import dspy4s.lm.runtime.ProviderResponseParser
import dspy4s.lm.runtime.RequestHash
import dspy4s.lm.runtime.ToolCallAssembler
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** The id of a tool call survives the wire in the two directions, and a request can replay tool calls and tool results
  * (PORT_GAPS G-28; upstream 2ec31b3aa, `dspy/adapters/base.py` `_provider_tool_call_to_tool_call_dict` and
  * `_tool_call_as_openai_message_tool_call`).
  */
class ToolCallWireSuite extends FunSuite:

  private val args: DynamicValue.Record = DynamicValues.record("query" := "cats")
  private val search: ToolCall          = ToolCall(name = "search", args = args, id = Some("call_1"))

  private def json(text: String): DynamicValue =
    Schema.dynamic.jsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)) match
      case Right(value) => value
      case Left(error)  => fail(s"the test JSON is not valid: $error")

  private def record(value: DynamicValue): DynamicValue.Record =
    value match
      case found: DynamicValue.Record => found
      case other                      => fail(s"expected a record, got $other")

  private def sequence(value: Option[DynamicValue]): Vector[DynamicValue] =
    value match
      case Some(found: DynamicValue.Sequence) => found.elements.toVector
      case other                              => fail(s"expected a sequence, got $other")

  private def get(from: DynamicValue.Record, key: String): Option[DynamicValue] = DynamicValues.recordGet(from, key)

  private def text(from: DynamicValue.Record, key: String): Option[String] =
    get(from, key).flatMap(DynamicValues.asString)

  private def wireMessages(messages: Vector[Message]): Vector[DynamicValue.Record] =
    val request = LmRequest(model = "gpt-test", messages = messages)
    sequence(get(ProviderRequestNormalizer.normalize(request), "messages")).map(record)

  // ── From the provider ───────────────────────────────────────────────────────────────────────────────────────

  test("a chat response keeps the id of each tool call") {
    val raw = json(
      """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
        |  {"id":"call_1","type":"function","function":{"name":"search","arguments":"{\"query\":\"cats\"}"}},
        |  {"id":"call_2","type":"function","function":{"name":"search","arguments":"{\"query\":\"dogs\"}"}}
        |]},"finish_reason":"tool_calls"}]}""".stripMargin
    )
    val calls = ProviderResponseParser.parse(raw, LmMode.Chat).map(_.outputs.flatMap(_.toolCalls))
    assertEquals(calls.map(_.map(_.id)), Right(Vector(Some("call_1"), Some("call_2"))))
    assertEquals(calls.map(_.headOption), Right(Some(search)))
  }

  test("a chat tool call without an id has no id") {
    val raw = json(
      """{"choices":[{"message":{"content":null,"tool_calls":[
        |  {"type":"function","function":{"name":"search","arguments":"{\"query\":\"cats\"}"}}
        |]}}]}""".stripMargin
    )
    assertEquals(
      ProviderResponseParser.parse(raw, LmMode.Chat).map(_.outputs.flatMap(_.toolCalls)),
      Right(Vector(search.copy(id = None)))
    )
  }

  test("a Responses tool call takes its id from call_id, not from the item id") {
    // The item has an `id` ("fc_...") and the `call_id` that a tool result must name. Upstream prefers `call_id`.
    val raw = json(
      """{"output":[{"type":"function_call","id":"fc_1","call_id":"call_9","name":"search",
        |  "tool_calls":[{"id":"fc_1","call_id":"call_9","name":"search","arguments":"{\"query\":\"cats\"}"}]}]}""".stripMargin
    )
    val calls = ProviderResponseParser.parse(raw, LmMode.Responses).map(_.outputs.flatMap(_.toolCalls))
    assertEquals(calls.map(_.map(_.id)), Right(Vector(Some("call_9"))))
  }

  test("the stream assembler keeps the id of each tool call") {
    val deltas = Vector(
      LmToolCallDelta(index = 0, id = Some("call_1"), name = Some("search"), argumentsFragment = Some("""{"query":""")),
      LmToolCallDelta(index = 0, argumentsFragment = Some(""""cats"}""")),
      LmToolCallDelta(index = 1, id = Some("call_2"), name = Some("search"), argumentsFragment = Some("{}"))
    )
    assertEquals(
      ToolCallAssembler.assemble(deltas),
      Vector(search, ToolCall(name = "search", args = DynamicValue.Record.empty, id = Some("call_2")))
    )
  }

  // ── To the provider ─────────────────────────────────────────────────────────────────────────────────────────

  test("an assistant message with tool calls goes to the wire in the provider form") {
    val wire = wireMessages(Vector(
      Message(role = MessageRole.User, text = Some("Q1")),
      Message(role = MessageRole.Assistant, text = Some("I should search."), toolCalls = Vector(search))
    ))
    val assistant = wire(1)
    assertEquals(text(assistant, "role"), Some("assistant"))
    assertEquals(text(assistant, "content"), Some("I should search."))
    val calls = sequence(get(assistant, "tool_calls")).map(record)
    assertEquals(calls.size, 1)
    assertEquals(text(calls.head, "id"), Some("call_1"))
    assertEquals(text(calls.head, "type"), Some("function"))
    val function = record(get(calls.head, "function").getOrElse(fail("no function")))
    assertEquals(text(function, "name"), Some("search"))
    // The arguments are a JSON string, as the provider expects.
    assertEquals(text(function, "arguments").map(json), Some(args: DynamicValue))
    // The user message has no tool fields.
    assertEquals(get(wire(0), "tool_calls"), None)
    assertEquals(get(wire(0), "tool_call_id"), None)
  }

  test("an assistant message with tool calls and no text has a null content") {
    val wire = wireMessages(Vector(Message(role = MessageRole.Assistant, toolCalls = Vector(search))))
    assertEquals(get(wire.head, "content"), Some(DynamicValue.Null: DynamicValue))
    assertEquals(sequence(get(wire.head, "tool_calls")).size, 1)
  }

  test("a tool message goes to the wire with the call id and the tool name") {
    val wire = wireMessages(Vector(
      Message(
        role = MessageRole.Tool,
        text = Some("""{"items":["cat"]}"""),
        toolCallId = Some("call_1"),
        name = Some("search")
      )
    ))
    assertEquals(text(wire.head, "role"), Some("tool"))
    assertEquals(text(wire.head, "tool_call_id"), Some("call_1"))
    assertEquals(text(wire.head, "name"), Some("search"))
    assertEquals(text(wire.head, "content"), Some("""{"items":["cat"]}"""))
  }

  test("an ordinary message has the same wire form as before") {
    val wire = wireMessages(Vector(Message(role = MessageRole.User, text = Some("hello"))))
    assertEquals(wire.head, DynamicValues.record("role" := "user", "content" := "hello"))
  }

  // ── Caches ──────────────────────────────────────────────────────────────────────────────────────────────────

  private def requestWith(message: Message): LmRequest =
    LmRequest(model = "gpt-test", messages = Vector(Message(role = MessageRole.User, text = Some("Q1")), message))

  test("the cache key covers the tool calls of an assistant message") {
    val first  = requestWith(Message(role = MessageRole.Assistant, toolCalls = Vector(search)))
    val second =
      requestWith(Message(role = MessageRole.Assistant, toolCalls = Vector(search.copy(id = Some("call_2")))))
    val third = requestWith(Message(
      role = MessageRole.Assistant,
      toolCalls = Vector(search.copy(args = DynamicValues.record("query" := "dogs")))
    ))
    val none = requestWith(Message(role = MessageRole.Assistant))
    assertEquals(Set(first, second, third, none).map(RequestHash.forRequest).size, 4)
  }

  test("the cache key covers the call id and the name of a tool message") {
    val base   = Message(role = MessageRole.Tool, text = Some("cat"), toolCallId = Some("call_1"), name = Some("search"))
    val hashes = Set(
      requestWith(base),
      requestWith(base.copy(toolCallId = Some("call_2"))),
      requestWith(base.copy(name = Some("lookup"))),
      requestWith(base.copy(toolCallId = None, name = None))
    ).map(RequestHash.forRequest)
    assertEquals(hashes.size, 4)
  }

  test("the disk cache keeps the id of a tool call") {
    val directory = Files.createTempDirectory("dspy4s-lm-disk-cache-tool-id")
    try
      val request =
        LmRequest(model = "gpt-test", messages = Vector(Message(role = MessageRole.User, text = Some("Q1"))))
      val response =
        LmResponse(outputs = Vector(LmOutput(text = "", toolCalls = Vector(search, search.copy(id = None)))))
      new DiskLmCache(directory, maxEntries = CacheCapacity(8)).put(request, response)

      val cached = new DiskLmCache(directory, maxEntries = CacheCapacity(8)).get(request)
      assertEquals(cached.map(_.outputs.flatMap(_.toolCalls)), Some(Vector(search, search.copy(id = None))))
    finally
      Files.walk(directory).sorted(java.util.Comparator.reverseOrder()).forEach { path =>
        val _ = Files.deleteIfExists(path)
      }
  }
