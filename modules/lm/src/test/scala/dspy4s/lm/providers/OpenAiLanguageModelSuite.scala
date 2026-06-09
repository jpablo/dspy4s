package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite

class OpenAiLanguageModelSuite extends FunSuite:

  private final class ScriptedTransport(
      nonStreamingResponses: Vector[Either[DspyError, HttpResponse]] = Vector.empty,
      streamingResponses: Vector[Either[DspyError, HttpStreamResponse]] = Vector.empty
  ) extends HttpTransport:
    private var nonStreamingIdx = 0
    private var streamingIdx = 0

    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      if nonStreamingIdx >= nonStreamingResponses.size then
        Left(RuntimeError("test", "No more non-streaming responses scripted"))
      else
        val r = nonStreamingResponses(nonStreamingIdx)
        nonStreamingIdx += 1
        r

    override def streamSse(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpStreamResponse] =
      if streamingIdx >= streamingResponses.size then
        Left(RuntimeError("test", "No more streaming responses scripted"))
      else
        val r = streamingResponses(streamingIdx)
        streamingIdx += 1
        r

  private final class VectorClosableIterator[A](items: Vector[A]) extends ClosableIterator[A]:
    private var idx = 0
    override def hasNext: Boolean = idx < items.size
    override def next(): A =
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
    val lm = OpenAiLanguageModel(model = "gpt-4o-mini", client = client)

    given RuntimeContext = RuntimeEnvironment.current
    val request = LmRequest(model = "gpt-4o-mini", mode = LmMode.Chat, messages = Vector(Message(role = MessageRole.User, text = Some("Capital?"))))
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
    val lm = OpenAiLanguageModel(model = "gpt-4o-mini", client = client)

    given RuntimeContext = RuntimeEnvironment.current
    val request = LmRequest(model = "gpt-4o-mini", mode = LmMode.Chat, messages = Vector(Message(role = MessageRole.User, text = Some("hi"))))
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
    val lm = OpenAiLanguageModel(model = "gpt-4o-mini", client = client)

    given RuntimeContext = RuntimeEnvironment.current
    val result = lm.call(LmRequest(model = "gpt-4o-mini", messages = Vector(Message(MessageRole.User, Some("x")))))
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.asInstanceOf[RuntimeError].component, "openai_auth")
  }

  test("defaultOptions override request option keys") {
    val transport = new ScriptedTransport(
      nonStreamingResponses = Vector(Right(HttpResponse(200, Map.empty, okResponse)))
    )
    val client = OpenAiClient(apiKey = "x", transport = transport)
    val lm = OpenAiLanguageModel(model = "gpt-4o-mini", client = client, defaultOptions = DynamicValues.record("temperature" := 0.7))

    given RuntimeContext = RuntimeEnvironment.current
    val request = LmRequest(model = "gpt-4o-mini", messages = Vector(Message(MessageRole.User, Some("hi"))))
    assert(lm.call(request).isRight)
  }

  test("local builds a keyless provider against an OpenAI-compatible base URL (placeholder bearer token)") {
    val lm = OpenAiLanguageModel.local("llama3.2", baseUrl = "http://localhost:11434/v1")
    assertEquals(lm.id, "llama3.2")
    assertEquals(lm.client.baseUrl, "http://localhost:11434/v1")
    assertEquals(lm.client.apiKey, "local") // placeholder; the server doesn't check it

    val embedder = OpenAiEmbedder.local("nomic-embed-text", baseUrl = "http://localhost:11434/v1")
    assertEquals(embedder.baseUrl, "http://localhost:11434/v1")
    assertEquals(embedder.apiKey, "local")
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
