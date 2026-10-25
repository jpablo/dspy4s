package dspy4s.lm

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.CacheKey
import dspy4s.lm.contracts.ContentPart
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.lm.providers.DynamicJson
import dspy4s.lm.providers.HttpResponse
import dspy4s.lm.providers.HttpStreamResponse
import dspy4s.lm.providers.HttpTransport
import dspy4s.lm.providers.OpenAiClient
import dspy4s.lm.providers.ChatWire
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.lm.providers.ToolCallAssembler
import dspy4s.lm.runtime.CacheCapacity
import dspy4s.lm.runtime.DiskLmCache
import dspy4s.lm.runtime.ManagedLanguageModel
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.collection.mutable.ArrayBuffer

/** The cases of the tool call id that `ToolCallWireSuite` does not cover (PORT_GAPS G-28): the paths that copy a tool
  * call, the absent keys on the wire, the prompt of the text mode, and a disk cache entry that the cache cannot read.
  */
class ToolCallIdSuite extends FunSuite:

  private val args: DynamicValue.Record = DynamicValues.record("query" := "cats")
  private val search: ToolCall          = ToolCall(name = "search", args = args, id = Some("call_1"))

  private val question: LmRequest =
    LmRequest(model = "gpt-test", messages = Vector(Message(role = MessageRole.User, text = Some("Q1"))))

  private val okBody: String =
    """{"choices":[{"message":{"role":"assistant","content":"cat"},"finish_reason":"stop"}]}"""

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A transport that records each body. It gives `reply` to a call and `lines` to a stream. */
  private final class RecordingTransport(reply: String = okBody, lines: Vector[String] = Vector.empty)
      extends HttpTransport:
    val bodies: ArrayBuffer[String] = ArrayBuffer.empty

    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      bodies += body
      Right(HttpResponse(200, Map.empty, reply))

    override def streamSse(
        url    : String,
        headers: Map[String, String],
        body   : String
    ): Either[DspyError, HttpStreamResponse] =
      bodies += body
      val source = lines.iterator
      Right(HttpStreamResponse(
        200,
        Map.empty,
        new ClosableIterator[String]:
          override def hasNext: Boolean = source.hasNext
          override def next(): String   = source.next()
          override def close(): Unit    = ()
      ))

  private def json(text: String): DynamicValue =
    DynamicJson.decode(text) match
      case Right(value) => value
      case Left(error)  => fail(s"the test JSON is not valid: ${error.message}")

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
    sequence(get(ChatWire.request(request), "messages")).map(record)

  private def withDirectory(body: Path => Unit): Unit =
    val directory = Files.createTempDirectory("dspy4s-lm-tool-call-id")
    try body(directory)
    finally Files.walk(directory).sorted(java.util.Comparator.reverseOrder()).forEach { path =>
        val _ = Files.deleteIfExists(path)
      }

  // ── From the provider ───────────────────────────────────────────────────────────────────────────────────────

  test("a provider tool call with an empty id has no id, and an empty call_id gives the id") {
    val raw = json(
      """{"choices":[{"message":{"content":null,"tool_calls":[
        |  {"id":"","type":"function","function":{"name":"search","arguments":"{\"query\":\"cats\"}"}},
        |  {"id":"call_1","call_id":"","type":"function",
        |   "function":{"name":"search","arguments":"{\"query\":\"cats\"}"}}
        |]}}]}""".stripMargin
    )
    assertEquals(
      ChatWire.outputs(raw).map(_.flatMap(_.toolCalls)),
      Right(Vector(search.copy(id = None), search))
    )
  }

  test("an OpenAI stream keeps the id of each tool call") {
    def delta(call: String): String = s"""data: {"choices":[{"delta":{"tool_calls":[$call]}}]}"""
    val transport                   = new RecordingTransport(lines =
      Vector(
        delta("""{"index":0,"id":"call_1","type":"function","function":{"name":"search","arguments":""}}"""),
        delta("""{"index":0,"function":{"arguments":"{\"query\":"}}"""),
        delta("""{"index":0,"function":{"arguments":"\"cats\"}"}}"""),
        delta("""{"index":1,"id":"call_2","type":"function","function":{"name":"search","arguments":"{}"}}"""),
        """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        "data: [DONE]"
      )
    )
    val model = OpenAiLanguageModel(model = "gpt-test", client = OpenAiClient(apiKey = "x", transport = transport))

    given RuntimeContext = RuntimeEnvironment.current
    val chunks           = model.stream(question).toVector
    assertEquals(
      ToolCallAssembler.assemble(chunks.flatMap(_.toolCalls)),
      Vector(search, ToolCall(name = "search", args = DynamicValue.Record.empty, id = Some("call_2")))
    )
  }

  test("the stream of a managed model with a non-stream delegate keeps the tool calls and their ids") {
    val calls    = Vector(search, ToolCall(name = "lookup", args = DynamicValue.Record.empty))
    val delegate = new LanguageModel:
      override val id: String   = "plain"
      override val mode: LmMode = LmMode.Chat

      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        Right(LmResponse(outputs = Vector(LmOutput(text = "I should search.", toolCalls = calls))))

    given RuntimeContext = RuntimeEnvironment.current
    val chunks           = ManagedLanguageModel(delegate = delegate).stream(question).toVector
    assertEquals(chunks.map(_.text), Vector("I should search."))
    assertEquals(ToolCallAssembler.assemble(chunks.flatMap(_.toolCalls)), calls)
  }

  // ── To the provider ─────────────────────────────────────────────────────────────────────────────────────────

  test("a tool call without an id has no id key on the wire") {
    val wire = wireMessages(Vector(Message(role = MessageRole.Assistant, toolCalls = Vector(search.copy(id = None)))))
    assertEquals(
      sequence(get(wire.head, "tool_calls")),
      Vector(json("""{"type":"function","function":{"name":"search","arguments":"{\"query\":\"cats\"}"}}"""))
    )
  }

  test("a tool message without a call id and a name has no key for them") {
    val wire = wireMessages(Vector(Message(role = MessageRole.Tool, text = Some("cat"))))
    assertEquals(wire.head, DynamicValues.record("role" := "tool", "content" := "cat"))
  }

  test("the arguments of a tool call keep a null value on the wire") {
    val call     = search.copy(args = DynamicValues.record("query" := "cats", "limit" -> DynamicValue.Null))
    val wire     = wireMessages(Vector(Message(role = MessageRole.Assistant, toolCalls = Vector(call))))
    val function =
      record(get(record(sequence(get(wire.head, "tool_calls")).head), "function").getOrElse(fail("no function")))
    assertEquals(text(function, "arguments"), Some("""{"query":"cats","limit":null}"""))
  }

  test("an assistant message with tool calls and parts keeps the parts as its content") {
    val wire = wireMessages(Vector(Message(
      role = MessageRole.Assistant,
      parts = Vector(ContentPart(kind = "text", payload = "I should search.")),
      toolCalls = Vector(search)
    )))
    assertEquals(
      get(wire.head, "content"),
      Some(json("""[{"type":"text","text":"I should search."}]"""))
    )
    assertEquals(sequence(get(wire.head, "tool_calls")).size, 1)
  }

  test("the prompt of the text mode takes the text of a tool message, and no tool field") {
    val request = LmRequest(
      model = "gpt-test",
      mode = LmMode.Text,
      messages = Vector(
        Message(role = MessageRole.User, text = Some("Q1")),
        Message(role = MessageRole.Assistant, text = Some("I should search."), toolCalls = Vector(search)),
        Message(role = MessageRole.Tool, text = Some("cat"), toolCallId = Some("call_1"), name = Some("search"))
      )
    )
    val wire = ChatWire.textRequest(request)
    assertEquals(text(wire, "prompt"), Some("Q1\nI should search.\ncat"))
    assertEquals(get(wire, "messages"), None)

    // An assistant message that has tool calls only gives an empty line, as each message with no text does.
    val toolOnly = Message(role = MessageRole.Assistant, toolCalls = Vector(search))
    assertEquals(
      text(
        ChatWire.textRequest(request.copy(messages = request.messages.updated(1, toolOnly))),
        "prompt"
      ),
      Some("Q1\n\ncat")
    )
  }

  test("the HTTP body of the OpenAI client carries the tool calls and the tool message") {
    val transport = new RecordingTransport()
    val model     = OpenAiLanguageModel(model = "gpt-test", client = OpenAiClient(apiKey = "x", transport = transport))
    val request   = question.copy(messages =
      question.messages ++ Vector(
        Message(role = MessageRole.Assistant, toolCalls = Vector(search)),
        Message(role = MessageRole.Tool, text = Some("cat"), toolCallId = Some("call_1"), name = Some("search"))
      )
    )

    given RuntimeContext = RuntimeEnvironment.current
    assertEquals(model.call(request).map(_.outputs.map(_.text)), Right(Vector("cat")))

    val messages = sequence(get(record(json(transport.bodies.head)), "messages"))
    assertEquals(messages.size, 3)
    // `DynamicJson.encode` removes only the null fields of the top level, so the assistant message keeps its null
    // `content` (the OpenAI chat format for an assistant message with tool calls and no text).
    assertEquals(
      messages(1),
      json(
        """{"role":"assistant","content":null,"tool_calls":[
          |  {"id":"call_1","type":"function","function":{"name":"search","arguments":"{\"query\":\"cats\"}"}}
          |]}""".stripMargin
      )
    )
    assertEquals(messages(2), json("""{"role":"tool","content":"cat","tool_call_id":"call_1","name":"search"}"""))
  }

  // ── Caches ──────────────────────────────────────────────────────────────────────────────────────────────────

  test("the disk cache counts an entry that it cannot read as a miss") {
    withDirectory { directory =>
      val key   = CacheKey.of("question")
      val path  = directory.resolve(s"${key.hex}.json")
      val _     = Files.write(path, "not a JSON record".getBytes(StandardCharsets.UTF_8))
      val cache = new DiskLmCache(directory, maxEntries = CacheCapacity(8))

      assertEquals(cache.get(key), None)
      assert(!Files.exists(path), "the cache must remove the entry that it cannot read")

      // The key is usable again.
      cache.put(key, LmResponse(outputs = Vector(LmOutput(text = "", toolCalls = Vector(search)))))
      assertEquals(cache.get(key).map(_.outputs.flatMap(_.toolCalls)), Some(Vector(search)))
    }
  }
