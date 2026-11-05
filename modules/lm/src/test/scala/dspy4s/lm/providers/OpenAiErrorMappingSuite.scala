package dspy4s.lm.providers

import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmAuthError
import dspy4s.core.contracts.LmBillingError
import dspy4s.core.contracts.LmError
import dspy4s.core.contracts.LmErrorDetails
import dspy4s.core.contracts.LmNotConfiguredError
import dspy4s.core.contracts.LmRateLimitError
import dspy4s.core.contracts.LmTransportError
import dspy4s.core.contracts.LmUnsupportedModelError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmMode
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.*

/** The OpenAI client and the embedder give each failure its LM error class (upstream `map_http_error` in
  * `dspy/_vendor/lm15/errors.py`, and `normalize_error` in `dspy/_vendor/lm15/providers/openai.py`).
  */
class OpenAiErrorMappingSuite extends FunSuite:

  /** Answers each request with one fixed result. */
  private final class FixedTransport(
      status : Int,
      body   : String,
      headers: Map[String, String] = Map.empty
  ) extends HttpTransport:
    override def sendJson(url: String, sent: Map[String, String], payload: String): Either[DspyError, HttpResponse] =
      Right(HttpResponse(status, headers, body))

    override def streamSse(
        url    : String,
        sent   : Map[String, String],
        payload: String
    ): Either[DspyError, HttpStreamResponse] =
      val lines = body.linesIterator
      Right(HttpStreamResponse(
        status,
        headers,
        new ClosableIterator[String]:
          override def hasNext: Boolean = lines.hasNext
          override def next(): String   = lines.next()
          override def close(): Unit    = ()
      ))

  /** Fails each request before a response comes. */
  private final class FailingTransport(error: DspyError) extends HttpTransport:
    override def sendJson(url: String, sent: Map[String, String], payload: String): Either[DspyError, HttpResponse] =
      Left(error)

    override def streamSse(
        url    : String,
        sent   : Map[String, String],
        payload: String
    ): Either[DspyError, HttpStreamResponse] =
      Left(error)

  private val payload: DynamicValue.Record = DynamicValues.record("model" := "gpt-test")

  private def errorBody(message: String, code: Option[String] = None, kind: Option[String] = None): String =
    val fields =
      Vector(Some(s""""message":"$message""""), code.map(c => s""""code":"$c""""), kind.map(t => s""""type":"$t""""))
    s"""{"error":{${fields.flatten.mkString(",")}}}"""

  private def invokeError(status: Int, body: String, headers: Map[String, String] = Map.empty): DspyError =
    OpenAiClient(apiKey = "x", transport = new FixedTransport(status, body, headers)).invoke(LmMode.Chat, payload) match
      case Left(error) => error
      case Right(ok)   => fail(s"expected a failure for HTTP $status, got $ok")

  private def lmError(error: DspyError): LmError =
    error match
      case lm: LmError => lm
      case other       => fail(s"expected an LmError, got $other")

  test("each HTTP status gives the class of the upstream table") {
    val expected: Vector[(Int, String)] = Vector(
      401 -> "auth",
      403 -> "auth",
      402 -> "billing",
      408 -> "timeout",
      504 -> "timeout",
      429 -> "rate_limit",
      400 -> "invalid_request",
      404 -> "invalid_request",
      409 -> "invalid_request",
      413 -> "invalid_request",
      422 -> "invalid_request",
      500 -> "server",
      502 -> "server",
      503 -> "server",
      599 -> "server",
      418 -> "provider",
      302 -> "provider"
    )
    expected.foreach { (status, code) =>
      assertEquals(invokeError(status, errorBody("no")).code, code, s"HTTP $status")
    }
  }

  test("the error carries the status, the provider, the model, the provider code, and the request id") {
    val error = lmError(invokeError(
      429,
      errorBody("Rate limit reached", code = Some("rate_limit_exceeded"), kind = Some("requests")),
      headers = Map("x-request-id" -> "req_123")
    ))
    assertEquals(
      error.details,
      LmErrorDetails(
        model = Some("gpt-test"),
        provider = Some("openai"),
        providerCode = Some("rate_limit_exceeded"),
        status = Some(429),
        requestId = Some("req_123"),
        retryAfter = None
      )
    )
  }

  test("the message has the status and the message of the provider") {
    val error = invokeError(500, errorBody("The server had an error"))
    assert(error.message.contains("500"), error.message)
    assert(error.message.contains("The server had an error"), error.message)
  }

  test("the provider code falls back to the error type") {
    val error = lmError(invokeError(400, errorBody("Unknown parameter", kind = Some("invalid_request_error"))))
    assertEquals(error.details.providerCode, Some("invalid_request_error"))
  }

  test("the request id is read without regard to the case of the header name, and from request-id too") {
    assertEquals(
      lmError(invokeError(500, errorBody("no"), Map("X-Request-Id" -> "req_a"))).details.requestId,
      Some("req_a")
    )
    assertEquals(
      lmError(invokeError(500, errorBody("no"), Map("request-id" -> "req_b"))).details.requestId,
      Some("req_b")
    )
  }

  test("Retry-After in seconds becomes the retry hint") {
    assertEquals(
      lmError(invokeError(429, errorBody("slow"), Map("retry-after" -> "2"))).details.retryAfter,
      Some(2.seconds)
    )
    assertEquals(
      lmError(invokeError(429, errorBody("slow"), Map("Retry-After" -> "1.5"))).details.retryAfter,
      Some(1500.millis)
    )
    assertEquals(
      lmError(invokeError(503, errorBody("busy"), Map("retry-after" -> "0"))).details.retryAfter,
      Some(Duration.Zero)
    )
  }

  test("Retry-After as an HTTP date becomes the time that remains") {
    val formatter = DateTimeFormatter.RFC_1123_DATE_TIME
    val future    = ZonedDateTime.now(ZoneOffset.UTC).plusHours(1).format(formatter)
    val hint      = lmError(invokeError(429, errorBody("slow"), Map("retry-after" -> future))).details.retryAfter
    assert(hint.exists(d => d > 58.minutes && d <= 60.minutes), hint.toString)

    val past = ZonedDateTime.now(ZoneOffset.UTC).minusHours(1).format(formatter)
    assertEquals(
      lmError(invokeError(429, errorBody("slow"), Map("retry-after" -> past))).details.retryAfter,
      Some(Duration.Zero)
    )
  }

  test("retry-after-ms is read when Retry-After is absent") {
    assertEquals(
      lmError(invokeError(429, errorBody("slow"), Map("retry-after-ms" -> "250"))).details.retryAfter,
      Some(250.millis)
    )
    assertEquals(
      lmError(invokeError(429, errorBody("slow"), Map("x-ms-retry-after-ms" -> "750"))).details.retryAfter,
      Some(750.millis)
    )
  }

  test("an invalid Retry-After gives no hint") {
    Vector("soon", "-1", "NaN", "Infinity", "").foreach { value =>
      assertEquals(
        lmError(invokeError(429, errorBody("slow"), Map("retry-after" -> value))).details.retryAfter,
        None,
        s"Retry-After: '$value'"
      )
    }
  }

  test("insufficient_quota on HTTP 429 is a billing error, which is not transient") {
    val byCode = invokeError(429, errorBody("You exceeded your current quota", code = Some("insufficient_quota")))
    assert(byCode.isInstanceOf[LmBillingError], byCode.toString)
    assert(!LmError.isRetryable(byCode))

    val byType = invokeError(429, errorBody("You exceeded your current quota", kind = Some("insufficient_quota")))
    assert(byType.isInstanceOf[LmBillingError], byType.toString)
  }

  test("a model error code gives LmUnsupportedModelError") {
    Vector("model_not_found", "model_not_available", "unsupported_model", "DeploymentNotFound").foreach { code =>
      val error = invokeError(404, errorBody("The model does not exist", code = Some(code)))
      assert(error.isInstanceOf[LmUnsupportedModelError], s"$code: $error")
    }
  }

  test("the context-window code gives ContextWindowExceededError with the details") {
    val error = invokeError(
      400,
      errorBody("This model's maximum context length is 8192 tokens", code = Some("context_length_exceeded")),
      Map("x-request-id" -> "req_ctx")
    )
    error match
      case cwe: ContextWindowExceededError =>
        assertEquals(cwe.details.model, Some("gpt-test"))
        assertEquals(cwe.details.status, Some(400))
        assertEquals(cwe.details.providerCode, Some("context_length_exceeded"))
        assertEquals(cwe.details.requestId, Some("req_ctx"))
      case other => fail(s"expected ContextWindowExceededError, got $other")
  }

  test("a context-window text marker on HTTP 400 without a code still gives ContextWindowExceededError") {
    val error = invokeError(400, errorBody("Please reduce the length of the messages."))
    assert(error.isInstanceOf[ContextWindowExceededError], error.toString)
  }

  test("an authentication code or type gives LmAuthError") {
    val byCode = invokeError(400, errorBody("Incorrect API key provided", code = Some("invalid_api_key")))
    assert(byCode.isInstanceOf[LmAuthError], byCode.toString)
    val byType = invokeError(400, errorBody("Incorrect API key provided", kind = Some("authentication_error")))
    assert(byType.isInstanceOf[LmAuthError], byType.toString)
  }

  test("a rate-limit code or type gives LmRateLimitError") {
    val byCode = invokeError(400, errorBody("Too many requests", code = Some("rate_limit_exceeded")))
    assert(byCode.isInstanceOf[LmRateLimitError], byCode.toString)
    val byType = invokeError(400, errorBody("Too many requests", kind = Some("rate_limit_error")))
    assert(byType.isInstanceOf[LmRateLimitError], byType.toString)
  }

  test("a body that is not the OpenAI error envelope is classified by the status") {
    val html = invokeError(502, "<html><body>Bad Gateway</body></html>")
    assertEquals(html.code, "server")
    assert(html.message.contains("Bad Gateway"), html.message)
    assertEquals(lmError(html).details.providerCode, None)

    val text = invokeError(429, """{"error":"rate limit"}""")
    assertEquals(text.code, "rate_limit")
    assert(text.message.contains("rate limit"), text.message)
  }

  test("a streamed call gives the same class and the same hint") {
    val client = OpenAiClient(
      apiKey = "x",
      transport =
        new FixedTransport(429, errorBody("slow", code = Some("rate_limit_exceeded")), Map("retry-after" -> "3"))
    )
    client.stream(LmMode.Chat, payload) match
      case Left(error: LmRateLimitError) =>
        assertEquals(error.details.retryAfter, Some(3.seconds))
        assertEquals(error.details.status, Some(429))
        assertEquals(error.details.model, Some("gpt-test"))
      case other => fail(s"expected LmRateLimitError, got $other")
  }

  test("a transport failure keeps its class and gets the model and the provider") {
    val client =
      OpenAiClient(apiKey = "x", transport = new FailingTransport(LmTransportError("Connection failed: refused")))
    val expected = LmTransportError(
      "Connection failed: refused",
      LmErrorDetails(model = Some("gpt-test"), provider = Some("openai"))
    )
    assertEquals(client.invoke(LmMode.Chat, payload), Left(expected))
    client.stream(LmMode.Chat, payload) match
      case Left(error) => assertEquals(error, expected)
      case Right(_)    => fail("expected a failure")
  }

  test("a missing API key is LmNotConfiguredError") {
    val missing = "DSPY4S_TEST_ABSENT_API_KEY"
    assume(!sys.env.contains(missing))
    OpenAiClient.fromEnv(envVar = missing) match
      case Left(error: LmNotConfiguredError) => assert(error.message.contains(missing), error.message)
      case other                             => fail(s"expected LmNotConfiguredError, got $other")
    OpenAiLanguageModel.fromEnv(model = "gpt-test", envVar = missing) match
      case Left(error) => assert(error.isInstanceOf[LmNotConfiguredError], error.toString)
      case Right(_)    => fail("expected a failure")
    OpenAiEmbedder.fromEnv(model = "text-embedding-3-small", envVar = missing) match
      case Left(error) => assert(error.isInstanceOf[LmNotConfiguredError], error.toString)
      case Right(_)    => fail("expected a failure")
  }

  test("the embedder gives the same classes and details") {
    given RuntimeContext                                                                           = RuntimeEnvironment.current
    def embedError(status: Int, body: String, headers: Map[String, String] = Map.empty): DspyError =
      val embedder = OpenAiEmbedder(
        model = "text-embedding-3-small",
        client = OpenAiClient(apiKey = "sk-test", transport = new FixedTransport(status, body, headers))
      )
      embedder.embed(Vector("hello")) match
        case Left(error) => error
        case Right(rows) => fail(s"expected a failure, got $rows")

    val limited = lmError(embedError(429, errorBody("slow"), Map("retry-after" -> "4", "x-request-id" -> "req_e")))
    assertEquals(limited.code, "rate_limit")
    assertEquals(
      limited.details,
      LmErrorDetails(
        model = Some("text-embedding-3-small"),
        provider = Some("openai"),
        status = Some(429),
        requestId = Some("req_e"),
        retryAfter = Some(4.seconds)
      )
    )
    assertEquals(embedError(401, errorBody("bad key")).code, "auth")
    assertEquals(embedError(500, errorBody("boom")).code, "server")
  }
