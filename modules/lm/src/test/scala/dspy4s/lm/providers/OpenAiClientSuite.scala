package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.LmChunk
import munit.FunSuite

class OpenAiClientSuite extends FunSuite:

  private final class ScriptedTransport(
      nonStreamingResponses: Vector[Either[DspyError, HttpResponse]] = Vector.empty,
      streamingResponses: Vector[Either[DspyError, HttpStreamResponse]] = Vector.empty
  ) extends HttpTransport:
    private var nonStreamingIdx = 0
    private var streamingIdx = 0
    val sentBodies = scala.collection.mutable.ArrayBuffer.empty[(String, String, String)]
    val sentStreamBodies = scala.collection.mutable.ArrayBuffer.empty[(String, String, String)]

    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      sentBodies += ((url, body, headers.get("Authorization").getOrElse("")))
      if nonStreamingIdx >= nonStreamingResponses.size then
        Left(RuntimeError("test", "No more non-streaming responses scripted"))
      else
        val r = nonStreamingResponses(nonStreamingIdx)
        nonStreamingIdx += 1
        r

    override def streamSse(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpStreamResponse] =
      sentStreamBodies += ((url, body, headers.get("Authorization").getOrElse("")))
      if streamingIdx >= streamingResponses.size then
        Left(RuntimeError("test", "No more streaming responses scripted"))
      else
        val r = streamingResponses(streamingIdx)
        streamingIdx += 1
        r

  private val sampleOkBody: String =
    """{"id":"x","choices":[{"message":{"role":"assistant","content":"Paris"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}"""

  private val sampleStreamLines: Vector[String] = Vector(
    """data: {"id":"x","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}""",
    """data: {"id":"x","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}""",
    """data: {"id":"x","choices":[{"index":0,"delta":{"content":" there"},"finish_reason":"stop"}]}""",
    """data: {"id":"x","usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""",
    "data: [DONE]",
    ""
  )

  private final class VectorClosableIterator[A](items: Vector[A]) extends ClosableIterator[A]:
    private var idx = 0
    var closed = false
    override def hasNext: Boolean = idx < items.size && !closed
    override def next(): A =
      val v = items(idx); idx += 1; v
    override def close(): Unit = closed = true

  test("invoke posts to chat endpoint with auth header and returns parsed map") {
    val transport = new ScriptedTransport(
      nonStreamingResponses = Vector(Right(HttpResponse(200, Map.empty, sampleOkBody)))
    )
    val client = OpenAiClient(apiKey = "sk-test", baseUrl = "https://api.example.com/v1", transport = transport)

    val result = client.invoke(Map("model" -> "gpt-4o-mini", "messages" -> Vector(Map("role" -> "user", "content" -> "hi"))))

    assert(result.isRight)
    val payload = result.toOption.get
    val choices = payload("choices").asInstanceOf[Vector[Map[String, Any]]]
    assertEquals(choices.size, 1)
    assertEquals(choices(0)("message").asInstanceOf[Map[String, Any]]("content"), "Paris")

    assertEquals(transport.sentBodies.size, 1)
    val (url, body, auth) = transport.sentBodies.head
    assertEquals(url, "https://api.example.com/v1/chat/completions")
    assertEquals(auth, "Bearer sk-test")
    assert(body.contains("\"model\":\"gpt-4o-mini\""))
    assert(!body.contains("\"mode\""), s"payload should not include dspy4s-internal 'mode': $body")
  }

  test("invoke surfaces non-2xx status as runtime error with rate-limit component on 429") {
    val transport = new ScriptedTransport(
      nonStreamingResponses = Vector(Right(HttpResponse(429, Map.empty, """{"error":{"message":"rate limited"}}""")))
    )
    val client = OpenAiClient(apiKey = "x", transport = transport)

    val result = client.invoke(Map("model" -> "m"))
    assert(result.isLeft)
    val error = result.left.toOption.get.asInstanceOf[RuntimeError]
    assertEquals(error.component, "openai_rate_limit")
    assert(error.message.contains("429"))
  }

  test("invoke returns parse error for malformed JSON body") {
    val transport = new ScriptedTransport(
      nonStreamingResponses = Vector(Right(HttpResponse(200, Map.empty, "not valid json{{{")))
    )
    val client = OpenAiClient(apiKey = "x", transport = transport)
    val result = client.invoke(Map("model" -> "m"))
    assert(result.isLeft)
  }

  test("stream yields LmChunks from SSE lines and stops at [DONE]") {
    val transport = new ScriptedTransport(
      streamingResponses = Vector(Right(HttpStreamResponse(
        status = 200,
        headers = Map.empty,
        dataLines = new VectorClosableIterator(sampleStreamLines)
      )))
    )
    val client = OpenAiClient(apiKey = "x", transport = transport)

    val result = client.stream(Map("model" -> "gpt-4o-mini", "messages" -> Vector(Map("role" -> "user", "content" -> "hi"))))
    assert(result.isRight)
    val iter = result.toOption.get

    val chunks = scala.collection.mutable.ArrayBuffer.empty[LmChunk]
    while iter.hasNext do chunks += iter.next()

    assertEquals(chunks.size, 4)
    assertEquals(chunks(0).text, "")
    assertEquals(chunks(1).text, "Hi")
    assertEquals(chunks(2).text, " there")
    assertEquals(chunks(2).finishReason, Some("stop"))
    val finalUsage = chunks(3).usage
    assert(finalUsage.isDefined)
    assertEquals(finalUsage.get.totalTokens, 5L)
    assertEquals(finalUsage.get.promptTokens, 3L)
  }

  test("stream injects stream=true and include_usage into payload") {
    val transport = new ScriptedTransport(
      streamingResponses = Vector(Right(HttpStreamResponse(
        status = 200,
        headers = Map.empty,
        dataLines = new VectorClosableIterator(Vector("data: [DONE]", ""))
      )))
    )
    val client = OpenAiClient(apiKey = "x", transport = transport)
    client.stream(Map("model" -> "m"))

    val (_, body, _) = transport.sentStreamBodies.head
    assert(body.contains("\"stream\":true"))
    assert(body.contains("\"stream_options\""))
    assert(body.contains("\"include_usage\":true"))
  }

  test("stream surfaces non-2xx status as error draining response body") {
    val transport = new ScriptedTransport(
      streamingResponses = Vector(Right(HttpStreamResponse(
        status = 500,
        headers = Map.empty,
        dataLines = new VectorClosableIterator(Vector("something bad", ""))
      )))
    )
    val client = OpenAiClient(apiKey = "x", transport = transport)
    val result = client.stream(Map("model" -> "m"))
    assert(result.isLeft)
    val error = result.left.toOption.get.asInstanceOf[RuntimeError]
    assertEquals(error.component, "openai_server")
  }

  test("stream parses tool_calls deltas into LmChunk.toolCalls") {
    val toolCallLines = Vector(
      """data: {"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}""",
      """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"loc"}}]},"finish_reason":null}]}""",
      """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ation\":\"Paris\"}"}}]},"finish_reason":"tool_calls"}]}""",
      "data: [DONE]",
      ""
    )
    val transport = new ScriptedTransport(
      streamingResponses = Vector(Right(HttpStreamResponse(
        status = 200,
        headers = Map.empty,
        dataLines = new VectorClosableIterator(toolCallLines)
      )))
    )
    val client = OpenAiClient(apiKey = "x", transport = transport)
    val iter = client.stream(Map("model" -> "m")).toOption.get
    val chunks = scala.collection.mutable.ArrayBuffer.empty[LmChunk]
    while iter.hasNext do chunks += iter.next()

    assertEquals(chunks.size, 3)
    val firstDelta = chunks(0).toolCalls.head
    assertEquals(firstDelta.index, 0)
    assertEquals(firstDelta.id, Some("call_1"))
    assertEquals(firstDelta.name, Some("get_weather"))
    assertEquals(firstDelta.argumentsFragment, Some(""))

    assertEquals(chunks(1).toolCalls.head.argumentsFragment, Some("{\"loc"))
    assertEquals(chunks(2).toolCalls.head.argumentsFragment, Some("ation\":\"Paris\"}"))
    assertEquals(chunks(2).finishReason, Some("tool_calls"))
  }

  test("stream ignores malformed SSE JSON lines without failing") {
    val transport = new ScriptedTransport(
      streamingResponses = Vector(Right(HttpStreamResponse(
        status = 200,
        headers = Map.empty,
        dataLines = new VectorClosableIterator(Vector(
          "data: {not valid json",
          """data: {"choices":[{"delta":{"content":"ok"}}]}""",
          "data: [DONE]",
          ""
        ))
      )))
    )
    val client = OpenAiClient(apiKey = "x", transport = transport)
    val iter = client.stream(Map("model" -> "m")).toOption.get
    val chunks = scala.collection.mutable.ArrayBuffer.empty[LmChunk]
    while iter.hasNext do chunks += iter.next()
    assertEquals(chunks.size, 1)
    assertEquals(chunks(0).text, "ok")
  }
