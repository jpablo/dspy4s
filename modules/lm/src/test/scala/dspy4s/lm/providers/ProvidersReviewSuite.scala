package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmRateLimitError
import dspy4s.core.contracts.LmServerError
import dspy4s.core.contracts.LmUnexpectedError
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ToolCall
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Locale
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt

/** The bugs of the code review of the lm providers: each test failed before its fix. */
class ProvidersReviewSuite extends FunSuite:

  /** A transport that records the URL of each request and gives one reply to each plain call and each stream. */
  private final class Recording(reply: String, streamLines: Vector[String] = Vector.empty) extends HttpTransport:
    val urls: ArrayBuffer[String] = ArrayBuffer.empty

    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      urls += url
      Right(HttpResponse(200, Map.empty, reply))

    override def streamSse(
        url    : String,
        headers: Map[String, String],
        body   : String
    ): Either[DspyError, HttpStreamResponse] =
      urls += url
      Right(HttpStreamResponse(200, Map.empty, Lines(streamLines)))

  private final class Lines(items: Vector[String]) extends ClosableIterator[String]:
    private var at                = 0
    override def hasNext: Boolean = at < items.size
    override def next(): String   =
      val line = items(at)
      at += 1
      line
    override def close(): Unit = at = items.size

  private def model(reply: String, mode: LmMode, streamLines: Vector[String] = Vector.empty) =
    val transport = Recording(reply, streamLines)
    val client    = OpenAiClient(apiKey = "x", baseUrl = "https://api.test/v1", transport = transport)
    (OpenAiLanguageModel(model = "gpt-test", mode = mode, client = client), transport)

  private val request = LmRequest(model = "gpt-test", messages = Vector(Message(MessageRole.User, Some("Q?"))))

  private def json(text: String): DynamicValue =
    DynamicJson.decode(text).fold(error => fail(s"the test JSON is not valid: $error"), identity)

  test("an error object in a chat reply with status 200 gives the typed error of its code") {
    given RuntimeContext = RuntimeContext()
    val (chat, _)        = model("""{"error": {"code": "rate_limit_exceeded", "message": "slow down"}}""", LmMode.Chat)
    chat.call(request) match
      case Left(error: LmRateLimitError) => assert(error.message.contains("slow down"), error.message)
      case other                         => fail(s"expected an LmRateLimitError, got $other")
  }

  test("an error object in a Responses reply with status 200 gives the typed error of its code") {
    given RuntimeContext = RuntimeContext()
    val reply            = """{"status": "failed", "error": {"code": "server_error", "message": "boom"}, "output": []}"""
    val (responses, _)   = model(reply, LmMode.Responses)
    responses.call(request) match
      case Left(error: LmServerError) => assert(error.message.contains("boom"), error.message)
      case other                      => fail(s"expected an LmServerError, got $other")
  }

  test("the text mode calls the completions endpoint, and its stream reads the text of each choice") {
    given RuntimeContext  = RuntimeContext()
    val (text, transport) = model(
      """{"choices": [{"text": "Hi there", "index": 0}]}""",
      LmMode.Text,
      streamLines = Vector(
        """data: {"choices": [{"text": "Hi", "index": 0, "finish_reason": null}]}""",
        """data: {"choices": [{"text": " there", "index": 0, "finish_reason": "stop"}]}""",
        "data: [DONE]"
      )
    )
    assertEquals(text.call(request).map(_.outputs.map(_.text)), Right(Vector("Hi there")))
    assertEquals(text.stream(request).map(_.text).mkString, "Hi there")
    assertEquals(transport.urls.toVector, Vector.fill(2)("https://api.test/v1/completions"))
  }

  test("an empty finish reason in a stream chunk is no finish reason, for the typed codec and the lenient reader") {
    val typed   = """{"choices": [{"delta": {"content": "a"}, "finish_reason": ""}]}"""
    val lenient = """{"choices": [{"delta": {"content": "a"}, "finish_reason": ""}], "usage": 5}"""
    Vector(typed, lenient).foreach { data =>
      val chunk = OpenAiStreamChunk.parse(data).map(_.map(_.toLmChunk))
      assertEquals(chunk.map(_.map(_.finishReason)), Right(Some(None)), data)
    }
  }

  test("an interrupt before the headers is a Left, not an exception") {
    val transport = HttpTransport.jdk(HttpTimeouts(connect = 2.seconds, read = 2.seconds))
    val server    = new ServerSocket(0, 8, InetAddress.getLoopbackAddress)
    val url       = s"http://${InetAddress.getLoopbackAddress.getHostAddress}:${server.getLocalPort}/v1/chat"
    try
      def check(label: String, call: => Either[DspyError, ?]): Unit =
        Thread.currentThread().interrupt()
        val result =
          try call
          catch case error: InterruptedException => fail(s"$label threw $error")
          finally
            val _ = Thread.interrupted()
        assert(result.left.exists(_.isInstanceOf[LmUnexpectedError]), s"$label: $result")
      check("sendJson", transport.sendJson(url, Map.empty, "{}"))
      check("streamSse", transport.streamSse(url, Map.empty, "{}"))
    finally
      server.close()
      transport.close()
  }

  test("the header names are in lower case also in a Turkish locale") {
    val server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress)
    val reply  = "HTTP/1.1 500 Internal Server Error\r\nX-Request-Id: req-1\r\nContent-Length: 2\r\n" +
      "Connection: close\r\n\r\n{}"
    val worker = Thread.ofVirtual().start { () =>
      val socket = server.accept()
      try
        val in     = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.ISO_8859_1))
        var length = 0
        var line   = in.readLine()
        while line != null && line.nonEmpty do
          if line.toLowerCase(Locale.ROOT).startsWith("content-length:") then
            length = line.drop("content-length:".length).trim.toInt
          line = in.readLine()
        val _ = in.skip(length.toLong)
        socket.getOutputStream.write(reply.getBytes(StandardCharsets.ISO_8859_1))
        socket.getOutputStream.flush()
      finally socket.close()
    }
    val previous  = Locale.getDefault
    val transport = HttpTransport.jdk(HttpTimeouts(connect = 2.seconds, read = 5.seconds))
    try
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      val url    = s"http://${InetAddress.getLoopbackAddress.getHostAddress}:${server.getLocalPort}/v1/chat"
      val result = transport.sendJson(url, Map.empty, "{}")
      assertEquals(result.map(_.headers.get("x-request-id")), Right(Some("req-1")))
    finally
      Locale.setDefault(previous)
      worker.join(5000)
      server.close()
      transport.close()
  }

  test("the provider encode keeps a nested null, and an assistant message with tool calls sends a null content") {
    val nested = DynamicValues.record(
      "temperature" -> DynamicValue.Null,
      "schema"      -> DynamicValues.record("default" -> DynamicValue.Null)
    )
    assertEquals(DynamicJson.encode(nested), """{"schema":{"default":null}}""")

    val call = ToolCall(name = "search", args = DynamicValues.record(), id = Some("call_1"))
    val body = ChatWire.request(LmRequest(
      model = "gpt-test",
      messages = Vector(Message(role = MessageRole.Assistant, text = None, toolCalls = Vector(call)))
    ))
    assert(DynamicJson.encode(body).contains(""""content":null"""), DynamicJson.encode(body))
  }

  test("an embeddings reply with a duplicate index is a ParseError") {
    given RuntimeContext = RuntimeContext()
    val reply            = """{"data": [{"index": 0, "embedding": [1.0]}, {"index": 0, "embedding": [2.0]}]}"""
    val client           = OpenAiClient(apiKey = "x", baseUrl = "https://api.test/v1", transport = Recording(reply))
    OpenAiEmbedder(model = "embed-test", client = client).embed(Vector("a", "b")) match
      case Left(_: ParseError) => ()
      case other               => fail(s"expected a ParseError, got $other")
  }

  test("a usage field of the wrong type keeps the other counts") {
    val usage = OpenAiUsage.fromDynamic(
      json("""{"prompt_tokens": 3, "completion_tokens": 4, "prompt_tokens_details": {"cached_tokens": "many"}}""")
    ).toLmUsage
    assertEquals((usage.promptTokens, usage.completionTokens, usage.totalTokens), (3L, 4L, 7L))
  }

  test("each trailing slash of the base URL is removed") {
    val transport = Recording("""{"data": []}""")
    val client    = OpenAiClient(apiKey = "x", baseUrl = "https://api.test/v1//", transport = transport)
    val _         = client.post("/embeddings", DynamicValues.record(), None)
    assertEquals(transport.urls.toVector, Vector("https://api.test/v1/embeddings"))
  }
