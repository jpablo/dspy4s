package dspy4s.lm.providers

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmErrorDetails
import dspy4s.core.contracts.LmTransportError
import dspy4s.core.contracts.LmUnexpectedError
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeError
import munit.FunSuite

import java.time.Instant
import scala.concurrent.duration.*

/** Edge cases of the LM error mapping that `OpenAiErrorMappingSuite` and `JdkHttpTransportErrorSuite` do not cover: a
  * body that is not the error envelope, the limits of the retry hint, and the order of the headers.
  */
class OpenAiErrorEdgeCasesSuite extends FunSuite:

  private val now: Instant = Instant.parse("2026-10-21T07:28:00Z")

  private def details(status: Int, body: String, headers: Map[String, String] = Map.empty): LmErrorDetails =
    OpenAiErrors.fromResponse(status, headers, body, model = Some("gpt-test"), now = now).details

  private def retryAfter(headers: (String, String)*): Option[FiniteDuration] =
    details(429, """{"error":{"message":"slow"}}""", headers.toMap).retryAfter

  test("a code that is a JSON number becomes the provider code") {
    val error = OpenAiErrors.fromResponse(429, Map.empty, """{"error":{"message":"no balance","code":1113}}""", None)
    assertEquals(error.details.providerCode, Some("1113"))
    assertEquals(error.code, "rate_limit")
  }

  test("a code that is null gives the type as the provider code") {
    val body = """{"error":{"message":"The server had an error","code":null,"type":"server_error"}}"""
    assertEquals(details(500, body).providerCode, Some("server_error"))
  }

  test("a body that is not the error envelope gives the class of the status and no provider code") {
    val bodies = Vector(
      "",
      "   ",
      "null",
      "42",
      "[]",
      "{not json",
      "[" * 100000,
      """{"detail":"no"}""",
      """{"error":null}""",
      """{"error":[1,2]}""",
      """{"error":{"message":{"nested":true},"code":{"nested":true}}}"""
    )
    bodies.foreach { body =>
      val error = OpenAiErrors.fromResponse(500, Map.empty, body, None)
      val clue  = s"body: '${body.take(60)}'"
      assertEquals(error.code, "server", clue)
      assertEquals(error.details.providerCode, None, clue)
      assert(error.message.startsWith("OpenAI HTTP 500"), clue)
    }
  }

  test("a body that is nested too deep for the stack gives a parse failure, and the next decode works") {
    DynamicJson.decode("[" * 100000) match
      case Left(error: ParseError) => assert(error.message.contains("nested too deep"), error.message)
      case other                   => fail(s"expected a ParseError, got $other")
    assertEquals(DynamicJson.decode("""{"ok":"yes"}""").map(DynamicValues.recordGet(_, "ok").isDefined), Right(true))
  }

  test("a number that is out of range in the error envelope does not fail") {
    val bodies = Vector(
      """{"error":{"message":"no","code":1e999999999}}""",
      """{"error":{"message":"no","code":123456789012345678901234567890}}""",
      """{"error":{"message":"no","code":-0.5}}""",
      """{"error":{"message":12,"code":true,"type":false}}"""
    )
    bodies.foreach { body =>
      assertEquals(OpenAiErrors.fromResponse(500, Map.empty, body, None).code, "server", s"body: '$body'")
    }
  }

  test("an empty body gives a message without a text part") {
    assertEquals(OpenAiErrors.fromResponse(500, Map.empty, "", None).message, "OpenAI HTTP 500")
  }

  test("a long body that is not the error envelope gives a short excerpt") {
    val message = OpenAiErrors.fromResponse(502, Map.empty, "x" * 5000, None).message
    assertEquals(message, s"OpenAI HTTP 502: ${"x" * 400}")
  }

  test("a Retry-After date gives the time from now to that date") {
    assertEquals(retryAfter("retry-after" -> "Wed, 21 Oct 2026 07:28:30 GMT"), Some(30.seconds))
    assertEquals(retryAfter("retry-after" -> "Wed, 21 Oct 2026 07:28:00 GMT"), Some(Duration.Zero))
  }

  test("a retry hint that is too large for a FiniteDuration gives no hint") {
    assertEquals(retryAfter("retry-after" -> "1e30"), None)
    assertEquals(retryAfter("retry-after" -> "9223372036854775807"), None)
    assertEquals(retryAfter("retry-after" -> "Fri, 31 Dec 9999 23:59:59 GMT"), None)
    assertEquals(retryAfter("retry-after-ms" -> "1e300"), None)
  }

  test("a number form that only the JVM accepts gives no hint") {
    Vector("2d", "2f", "0x1p3", "1_0", "+Infinity", "-0.5").foreach { value =>
      assertEquals(retryAfter("retry-after" -> value), None, s"Retry-After: '$value'")
      assertEquals(retryAfter("retry-after-ms" -> value), None, s"retry-after-ms: '$value'")
    }
  }

  test("Retry-After wins over retry-after-ms, and an invalid Retry-After falls back to retry-after-ms") {
    assertEquals(retryAfter("retry-after" -> "2", "retry-after-ms" -> "250"), Some(2.seconds))
    assertEquals(retryAfter("retry-after" -> "soon", "retry-after-ms" -> "250"), Some(250.millis))
    assertEquals(retryAfter("Retry-After-Ms" -> "soon", "X-MS-Retry-After-MS" -> "750"), Some(750.millis))
  }

  test("the first request id header that is not empty wins") {
    val headers = Map("x-request-id" -> " ", "x-ms-request-id" -> "req_ms", "Request-Id" -> "req_b")
    assertEquals(details(500, "", headers).requestId, Some("req_b"))
    assertEquals(details(500, "", Map("apim-request-id" -> "req_apim")).requestId, Some("req_apim"))
    assertEquals(details(500, "", Map("x-request-id" -> "")).requestId, None)
  }

  test("withRequestDetails keeps a field that is already set, and passes each other error unchanged") {
    val set =
      LmTransportError("reset", LmErrorDetails(model = Some("first"), provider = Some("proxy"), status = Some(0)))
    assertEquals(OpenAiErrors.withRequestDetails(set, Some("second")), set)

    val other = RuntimeError("test", "not an LM error")
    assertEquals(OpenAiErrors.withRequestDetails(other, Some("gpt-test")), other)
  }

  test("the JDK transport gives LmUnexpectedError for a URL that it cannot read") {
    HttpTransport.jdk(HttpTimeouts(connect = 1.second, read = 1.second)).sendJson("not a url", Map.empty, "{}") match
      case Left(error: LmUnexpectedError) => assert(error.message.nonEmpty)
      case other                          => fail(s"expected LmUnexpectedError, got $other")
  }
