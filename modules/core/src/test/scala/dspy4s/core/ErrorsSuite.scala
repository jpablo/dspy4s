package dspy4s.core

import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.LmAuthError
import dspy4s.core.contracts.LmBillingError
import dspy4s.core.contracts.LmError
import dspy4s.core.contracts.LmErrorDetails
import dspy4s.core.contracts.LmInvalidRequestError
import dspy4s.core.contracts.LmNotConfiguredError
import dspy4s.core.contracts.LmProviderError
import dspy4s.core.contracts.LmProviderResponseError
import dspy4s.core.contracts.LmRateLimitError
import dspy4s.core.contracts.LmServerError
import dspy4s.core.contracts.LmTimeoutError
import dspy4s.core.contracts.LmTransportError
import dspy4s.core.contracts.LmUnexpectedError
import dspy4s.core.contracts.LmUnsupportedModelError
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeError
import munit.FunSuite

import scala.concurrent.duration.*

class ErrorsSuite extends FunSuite:

  /** Each LM error with its upstream code (`dspy/utils/exceptions.py`) and its retry class. */
  private val lmErrors: Vector[(LmError, String, Boolean)] = Vector(
    (LmTransportError("m"), "transport", true),
    (LmNotConfiguredError("m"), "not_configured", false),
    (LmUnexpectedError("m"), "unexpected", false),
    (LmAuthError("m"), "auth", false),
    (LmBillingError("m"), "billing", false),
    (LmRateLimitError("m"), "rate_limit", true),
    (LmInvalidRequestError("m"), "invalid_request", false),
    (ContextWindowExceededError(), "context_window_exceeded", false),
    (LmUnsupportedModelError("m"), "unsupported_model", false),
    (LmTimeoutError("m"), "timeout", true),
    (LmServerError("m"), "server", true),
    (LmProviderResponseError("m"), "provider", false)
  )

  test("ContextWindowExceededError exposes the right code and defaults") {
    val err = ContextWindowExceededError()
    assertEquals(err.code, "context_window_exceeded")
    assertEquals(err.details, LmErrorDetails.empty)
    assertEquals(err.message, "Context window exceeded")
    assert(err.isInstanceOf[DspyError])
  }

  test("ContextWindowExceededError carries model and custom message") {
    val err = ContextWindowExceededError(message = "too long", details = LmErrorDetails(model = Some("gpt-4")))
    assertEquals(err.code, "context_window_exceeded")
    assertEquals(err.details.model, Some("gpt-4"))
    assertEquals(err.message, "too long")
  }

  test("each LM error has the upstream code") {
    lmErrors.foreach { (error, code, _) => assertEquals(error.code, code, error.toString) }
  }

  test("only a rate limit, a timeout, a server failure, and a transport failure are transient") {
    lmErrors.foreach { (error, _, transient) =>
      assertEquals(error.isTransient, transient, error.toString)
      assertEquals(LmError.isRetryable(error), transient, error.toString)
    }
  }

  test("an error that is not an LM error is never retryable") {
    val others: Vector[DspyError] =
      Vector(RuntimeError("lm", "boom"), ConfigurationError("bad"), ParseError("adapter", "bad output"))
    others.foreach(error => assert(!LmError.isRetryable(error), error.toString))
  }

  test("the provider errors are the errors that a provider response gives") {
    val provider = lmErrors.collect { case (_: LmProviderError, code, _) => code }.toSet
    assertEquals(
      provider,
      Set(
        "auth",
        "billing",
        "rate_limit",
        "invalid_request",
        "context_window_exceeded",
        "unsupported_model",
        "timeout",
        "server",
        "provider"
      )
    )
  }

  test("withDetails replaces the metadata and keeps the class and the message") {
    val details = LmErrorDetails(
      model = Some("gpt-test"),
      provider = Some("openai"),
      providerCode = Some("rate_limit_exceeded"),
      status = Some(429),
      requestId = Some("req_1"),
      retryAfter = Some(2.seconds)
    )
    lmErrors.foreach { (error, code, _) =>
      val updated = error.withDetails(details)
      assertEquals(updated.code, code)
      assertEquals(updated.message, error.message)
      assertEquals(updated.details, details)
    }
  }

  test("LmError.retryAfter reads the hint of an LM error only") {
    val hinted = LmRateLimitError("slow down", LmErrorDetails(retryAfter = Some(1500.millis)))
    assertEquals(LmError.retryAfter(hinted), Some(1500.millis))
    assertEquals(LmError.retryAfter(LmRateLimitError("slow down")), None)
    assertEquals(LmError.retryAfter(RuntimeError("lm", "boom")), None)
  }
