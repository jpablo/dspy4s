package dspy4s.core

import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import munit.FunSuite

class ErrorsSuite extends FunSuite:

  test("ContextWindowExceededError exposes the right code and defaults") {
    val err = ContextWindowExceededError()
    assertEquals(err.code, "context_window_exceeded")
    assertEquals(err.model, None)
    assertEquals(err.message, "Context window exceeded")
    assert(err.isInstanceOf[DspyError])
  }

  test("ContextWindowExceededError carries model and custom message") {
    val err = ContextWindowExceededError(model = Some("gpt-4"), message = "too long")
    assertEquals(err.code, "context_window_exceeded")
    assertEquals(err.model, Some("gpt-4"))
    assertEquals(err.message, "too long")
  }
