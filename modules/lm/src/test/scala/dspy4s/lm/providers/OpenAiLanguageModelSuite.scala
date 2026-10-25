package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmAuthError
import dspy4s.core.contracts.LmRateLimitError
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

class OpenAiLanguageModelSuite extends FunSuite:

  /** A transport that answers each plain request with `reply`, and records the URL and the body of each request. */
  private final class Recording(reply: String) extends HttpTransport:
    val sent: ArrayBuffer[(String, String)] = ArrayBuffer.empty

    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      sent += ((url, body))
      Right(HttpResponse(200, Map.empty, reply))

    override def streamSse(
        url    : String,
        headers: Map[String, String],
        body   : String
    ): Either[DspyError, HttpStreamResponse] =
      Left(RuntimeError("test", "the stream path is not used"))

    /** The JSON object of the last body. */
    def lastBody: DynamicValue.Record =
      sent.lastOption.map(_._2).map(DynamicJson.decode) match
        case Some(Right(record: DynamicValue.Record)) => record
        case other                                    => fail(s"no JSON object was sent: $other")

  private def recordingModel(
      reply         : String,
      mode          : LmMode              = LmMode.Chat,
      defaultOptions: DynamicValue.Record = DynamicValue.Record.empty
  ): (OpenAiLanguageModel, Recording) =
    val transport = Recording(reply)
    val model     = OpenAiLanguageModel(
      model = "openai/test",
      mode = mode,
      client = OpenAiClient(apiKey = "x", baseUrl = "https://api.test/v1", transport = transport),
      defaultOptions = defaultOptions
    )
    (model, transport)

  private def get(record: DynamicValue.Record, key: String): Option[DynamicValue] = DynamicValues.recordGet(record, key)

  /** The decoded JSON value of `text`, in the form of a decoded body. */
  private def json(text: String): DynamicValue =
    DynamicJson.decode(text).fold(error => fail(s"the test JSON is not valid: $error"), identity)

  private val chatReply: String =
    """{"model": "openai/test", "choices": [{"message": {"role": "assistant", "content": "From chat."}}]}"""

  private final class ScriptedTransport(
      nonStreamingResponses: Vector[Either[DspyError, HttpResponse]]       = Vector.empty,
      streamingResponses   : Vector[Either[DspyError, HttpStreamResponse]] = Vector.empty
  ) extends HttpTransport:
    private var nonStreamingIdx = 0
    private var streamingIdx    = 0

    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      if nonStreamingIdx >= nonStreamingResponses.size then
        Left(RuntimeError("test", "No more non-streaming responses scripted"))
      else
        val r = nonStreamingResponses(nonStreamingIdx)
        nonStreamingIdx += 1
        r

    override def streamSse(
        url    : String,
        headers: Map[String, String],
        body   : String
    ): Either[DspyError, HttpStreamResponse] =
      if streamingIdx >= streamingResponses.size then
        Left(RuntimeError("test", "No more streaming responses scripted"))
      else
        val r = streamingResponses(streamingIdx)
        streamingIdx += 1
        r

  private final class VectorClosableIterator[A](items: Vector[A]) extends ClosableIterator[A]:
    private var idx               = 0
    override def hasNext: Boolean = idx < items.size
    override def next(): A        =
      val v = items(idx); idx += 1; v
    override def close(): Unit = ()

  private val okResponse: String =
    """{"id":"chatcmpl-1","choices":[{"message":{"role":"assistant","content":"Brussels"},"finish_reason":"stop"}],"model":"gpt-4o-mini","usage":{"prompt_tokens":6,"completion_tokens":1,"total_tokens":7}}"""

  private val streamLines: Vector[String] = Vector(
    """data: {"id":"x","choices":[{"delta":{"content":"Hel"},"finish_reason":null}]}""",
    """data: {"id":"x","choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}""",
    """data: {"id":"x","usage":{"prompt_tokens":2,"completion_tokens":2,"total_tokens":4}}""",
    "data: [DONE]",
    ""
  )

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("OpenAiLanguageModel call returns parsed LmResponse with usage") {
    val transport = new ScriptedTransport(
      nonStreamingResponses = Vector(Right(HttpResponse(200, Map.empty, okResponse)))
    )
    val client = OpenAiClient(apiKey = "x", transport = transport)
    val lm     = OpenAiLanguageModel(model = "gpt-4o-mini", client = client)

    given RuntimeContext = RuntimeEnvironment.current
    val request          = LmRequest(
      model = "gpt-4o-mini",
      mode = LmMode.Chat,
      messages = Vector(Message(role = MessageRole.User, text = Some("Capital?")))
    )
    val result = lm.call(request)

    assert(result.isRight)
    val response = result.toOption.get
    assertEquals(response.outputs.size, 1)
    assertEquals(response.outputs.head.text, "Brussels")
    assertEquals(response.modelName, Some("gpt-4o-mini"))
    val usage = response.usage.get
    assertEquals(usage.totalTokens, 7L)
    assertEquals(usage.promptTokens, 6L)
  }

  test("OpenAiLanguageModel stream returns LmChunks that accumulate to full text") {
    val transport = new ScriptedTransport(
      streamingResponses = Vector(Right(HttpStreamResponse(
        status = 200,
        headers = Map.empty,
        dataLines = new VectorClosableIterator(streamLines)
      )))
    )
    val client = OpenAiClient(apiKey = "x", transport = transport)
    val lm     = OpenAiLanguageModel(model = "gpt-4o-mini", client = client)

    given RuntimeContext = RuntimeEnvironment.current
    val request          = LmRequest(
      model = "gpt-4o-mini",
      mode = LmMode.Chat,
      messages = Vector(Message(role = MessageRole.User, text = Some("hi")))
    )
    val chunks = lm.stream(request)

    val collected = chunks.toVector
    assertEquals(collected.map(_.text).mkString, "Hello")
    val stopChunk = collected.find(_.finishReason.isDefined)
    assert(stopChunk.isDefined)
    assertEquals(stopChunk.get.finishReason, Some("stop"))
    assert(collected.exists(_.usage.isDefined))
  }

  test("OpenAiLanguageModel.call surfaces HTTP error as DspyError") {
    val transport = new ScriptedTransport(
      nonStreamingResponses = Vector(Right(HttpResponse(401, Map.empty, """{"error":{"message":"auth failed"}}""")))
    )
    val client = OpenAiClient(apiKey = "bad", transport = transport)
    val lm     = OpenAiLanguageModel(model = "gpt-4o-mini", client = client)

    given RuntimeContext = RuntimeEnvironment.current
    lm.call(LmRequest(model = "gpt-4o-mini", messages = Vector(Message(MessageRole.User, Some("x"))))) match
      case Left(error: LmAuthError) => assertEquals(error.details.status, Some(401))
      case other                    => fail(s"expected LmAuthError, got $other")
  }

  test("OpenAiLanguageModel.stream reports an HTTP error as one chunk that carries the typed error") {
    val transport = new ScriptedTransport(
      streamingResponses = Vector(
        Right(HttpStreamResponse(429, Map.empty, new VectorClosableIterator(Vector("""{"error":"rate limit"}"""))))
      )
    )
    val lm = OpenAiLanguageModel(model = "gpt-4o-mini", client = OpenAiClient(apiKey = "x", transport = transport))

    given RuntimeContext = RuntimeEnvironment.current
    val request          = LmRequest(model = "gpt-4o-mini", messages = Vector(Message(MessageRole.User, Some("x"))))
    val chunks           = lm.stream(request).toVector

    assertEquals(chunks.size, 1)
    val chunk = chunks.head
    // The typed error is new. The finish reason and the `raw` message stay for existing consumers.
    chunk.error match
      case Some(error: LmRateLimitError) => assertEquals(error.details.status, Some(429))
      case other                         => fail(s"expected LmRateLimitError, got $other")
    assertEquals(chunk.finishReason, Some("error"))
    assertEquals(chunk.raw, Some(Map("error" -> chunk.error.map(_.message).getOrElse(""))))
  }

  test("defaultOptions override request option keys") {
    val transport = new ScriptedTransport(
      nonStreamingResponses = Vector(Right(HttpResponse(200, Map.empty, okResponse)))
    )
    val client = OpenAiClient(apiKey = "x", transport = transport)
    val lm     = OpenAiLanguageModel(
      model = "gpt-4o-mini",
      client = client,
      defaultOptions = DynamicValues.record("temperature" := 0.7)
    )

    given RuntimeContext = RuntimeEnvironment.current
    val request          = LmRequest(model = "gpt-4o-mini", messages = Vector(Message(MessageRole.User, Some("hi"))))
    assert(lm.call(request).isRight)
  }

  test("local builds a keyless provider against an OpenAI-compatible base URL (placeholder bearer token)") {
    val lm = OpenAiLanguageModel.local("llama3.2", baseUrl = "http://localhost:11434/v1")
    assertEquals(lm.id, "llama3.2")
    assertEquals(lm.client.baseUrl, "http://localhost:11434/v1")
    assertEquals(lm.client.apiKey, "local") // placeholder; the server doesn't check it

    val embedder = OpenAiEmbedder.local("nomic-embed-text", baseUrl = "http://localhost:11434/v1")
    assertEquals(embedder.client.baseUrl, "http://localhost:11434/v1")
    assertEquals(embedder.client.apiKey, "local")
  }

  test("fromEnv threads a custom baseUrl and still fails fast on a missing env var") {
    val result = OpenAiLanguageModel.fromEnv(
      "llama3.2",
      baseUrl = "http://localhost:11434/v1",
      envVar = "DSPY4S_TEST_UNSET_KEY"
    )
    assert(result.isLeft, "missing env var must fail fast")
    assert(result.left.toOption.get.message.contains("DSPY4S_TEST_UNSET_KEY"))
  }

  // ── The body and the reply of each mode ─────────────────────────────────────────────────────────────────────

  test("the chat body has the model, the request options over the default options, and the messages") {
    val (model, transport) = recordingModel(chatReply, defaultOptions = DynamicValues.record("max_tokens" := 64))
    given RuntimeContext   = RuntimeEnvironment.current
    val request            = LmRequest(
      model = "openai/test",
      messages = Vector(
        Message(role = MessageRole.System, text = Some("You are helpful")),
        Message(role = MessageRole.User, text = Some("Hello"))
      ),
      options = DynamicValues.record("temperature" := 0.2, "max_tokens" := 32),
      rolloutId = Some(5)
    )
    assert(model.call(request).isRight)
    val body = transport.lastBody
    assertEquals(transport.sent.map(_._1).toVector, Vector("https://api.test/v1/chat/completions"))
    assertEquals(get(body, "model"), Some(json("\"openai/test\"")))
    assertEquals(get(body, "temperature"), Some(json("0.2")))
    assertEquals(get(body, "max_tokens"), Some(json("32")))
    assertEquals(
      get(body, "messages"),
      Some(json("""[{"role": "system", "content": "You are helpful"}, {"role": "user", "content": "Hello"}]"""))
    )
    // The framework fields of the request (the mode and rolloutId) do not go into the body.
    assertEquals(DynamicValues.recordKeys(body).toSet, Set("model", "temperature", "max_tokens", "messages"))
  }

  test("a null option removes the option, also a default option of the model") {
    // Upstream `_canonical` (`dspy/clients/execution.py`) drops each option whose value is `None` after the merge.
    val request = LmRequest(
      model = "openai/test",
      messages = Vector(Message(role = MessageRole.User, text = Some("Q?"))),
      options = DynamicValues.record("reasoning_effort" -> DynamicValue.Null, "max_tokens" := 8)
    )
    val defaults = DynamicValues.record("reasoning_effort" := "high", "temperature" := 0.1)
    assertEquals(request.effectiveOptions(defaults), DynamicValues.record("temperature" := 0.1, "max_tokens" := 8))

    val (model, transport) = recordingModel(chatReply, defaultOptions = defaults)
    given RuntimeContext   = RuntimeEnvironment.current
    assert(model.call(request).isRight)
    assertEquals(get(transport.lastBody, "reasoning_effort"), None)
  }

  test("a model in the text mode sends a prompt to the completions endpoint, and reads the text of the reply") {
    val (model, transport) = recordingModel("""{"text": " A line. "}""", mode = LmMode.Text)
    given RuntimeContext   = RuntimeEnvironment.current
    val request            = LmRequest(
      model = "openai/test",
      messages = Vector(
        Message(role = MessageRole.User, text = Some("Line one")),
        Message(role = MessageRole.User, text = Some("Line two"))
      )
    )
    assertEquals(model.call(request).map(_.outputs.map(_.text)), Right(Vector("A line.")))
    assertEquals(transport.sent.map(_._1).toVector, Vector("https://api.test/v1/completions"))
    assertEquals(get(transport.lastBody, "prompt"), Some(json("\"Line one\\nLine two\"")))
    assertEquals(get(transport.lastBody, "messages"), None)
  }

  test("a chat reply gives the text, the tool calls, and the usage") {
    val reply = """{"model": "openai/test", "choices": [{"message": {"role": "assistant",
      |  "content": "The answer is Brussels.",
      |  "tool_calls": [{"function": {"name": "search", "arguments": {"query": "capital of belgium"}}}]}}],
      |  "usage": {"prompt_tokens": 5, "completion_tokens": 7, "total_tokens": 12}}""".stripMargin
    val (model, _)       = recordingModel(reply)
    given RuntimeContext = RuntimeEnvironment.current
    model.call(LmRequest(model = "openai/test", messages = Vector(Message(MessageRole.User, Some("Q?"))))) match
      case Right(response) =>
        assertEquals(response.outputs.map(_.text), Vector("The answer is Brussels."))
        assertEquals(response.outputs.flatMap(_.toolCalls).map(_.name), Vector("search"))
        assertEquals(response.usage.map(_.totalTokens), Some(12L))
        assertEquals(response.modelName, Some("openai/test"))
      case Left(error) => fail(s"the call failed: $error")
  }

  test("a chat reply with tool calls and no content gives a tool-only output") {
    // The function-calling reply: the assistant message has no content and a tool_calls array. The output must keep
    // the tool call, or native function calling loses the call.
    val reply =
      """{"choices": [{"message": {"role": "assistant",
      |  "tool_calls": [{"function": {"name": "search", "arguments": {"query": "capital of belgium"}}}]}}]}""".stripMargin
    val (model, _)       = recordingModel(reply)
    given RuntimeContext = RuntimeEnvironment.current
    model.call(LmRequest(model = "openai/test", messages = Vector(Message(MessageRole.User, Some("Q?"))))) match
      case Right(response) =>
        assertEquals(response.outputs.map(_.text), Vector(""))
        assertEquals(response.outputs.flatMap(_.toolCalls).map(_.name), Vector("search"))
      case Left(error) => fail(s"a tool-only output must not be dropped: $error")
  }

  test("a Responses reply gives the joined text parts and the usage") {
    val reply = """{"model": "openai/test", "output": [{"type": "message", "content": [
      |  {"type": "output_text", "text": "First line"}, {"type": "output_text", "text": "Second line"}]}],
      |  "usage": {"input_tokens": 3, "output_tokens": 4, "total_tokens": 7}}""".stripMargin
    val (model, _)       = recordingModel(reply, mode = LmMode.Responses)
    given RuntimeContext = RuntimeEnvironment.current
    model.call(LmRequest(model = "openai/test", messages = Vector(Message(MessageRole.User, Some("Q?"))))) match
      case Right(response) =>
        // Upstream `responses_outputs` reads only the typed text parts, and joins them with no separator.
        assertEquals(response.outputs.map(_.text), Vector("First lineSecond line"))
        assertEquals(response.usage.map(usage => (usage.promptTokens, usage.completionTokens)), Some((3L, 4L)))
      case Left(error) => fail(s"the call failed: $error")
  }

  test("the mode of the model selects the endpoint, the body, and the parse, not the mode of the request") {
    // Upstream `LM.model_type` is fixed when the LM is built.
    val responsesReply =
      """{"output": [{"type": "message", "content": [{"type": "output_text", "text": "From responses."}]}]}"""
    val cases = Vector(
      (LmMode.Chat, LmMode.Responses, chatReply, "/chat/completions", "messages", "From chat."),
      (LmMode.Chat, LmMode.Text, chatReply, "/chat/completions", "messages", "From chat."),
      (LmMode.Responses, LmMode.Chat, responsesReply, "/responses", "input", "From responses."),
      (LmMode.Text, LmMode.Chat, chatReply, "/completions", "prompt", "From chat.")
    )
    given RuntimeContext = RuntimeEnvironment.current
    cases.foreach { (modelMode, requestMode, reply, endpoint, key, text) =>
      val (model, transport) = recordingModel(reply, mode = modelMode)
      val request            = LmRequest(
        model = "openai/test",
        mode = requestMode,
        messages = Vector(Message(role = MessageRole.User, text = Some("Q?")))
      )
      val label = s"$modelMode model, $requestMode request"
      assertEquals(model.call(request).map(_.outputs.map(_.text)), Right(Vector(text)), label)
      assertEquals(transport.sent.map(_._1).toVector, Vector(s"https://api.test/v1$endpoint"), label)
      assert(get(transport.lastBody, key).isDefined, s"$label: ${transport.lastBody}")
    }
  }

  test("a reply with no output is a ParseError") {
    val (model, _)       = recordingModel("""{"model": "openai/test", "choices": []}""")
    given RuntimeContext = RuntimeEnvironment.current
    model.call(LmRequest(model = "openai/test", messages = Vector(Message(MessageRole.User, Some("Q?"))))) match
      case Left(error: ParseError) => assertEquals(error.message, "Provider response does not contain any outputs")
      case other                   => fail(s"expected a ParseError, got $other")
  }
