package dspy4s.lm

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.providers.OpenAiLanguageModel
import munit.FunSuite

class LmCapabilityFlagsSuite extends FunSuite:
  private object BareLm extends LanguageModel:
    override val id: String = "bare"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("bare_lm", "not implemented"))

  test("bare LanguageModel reports all capability flags as false by default"):
    assertEquals(BareLm.supportsFunctionCalling, false)
    assertEquals(BareLm.supportsResponseSchema, false)
    assertEquals(BareLm.supportsReasoning, false)

  test("OpenAiLanguageModel supports function calling and response schema"):
    val lm = OpenAiLanguageModel(model = "gpt-4o-mini", apiKey = "test-key")
    assertEquals(lm.supportsFunctionCalling, true)
    assertEquals(lm.supportsResponseSchema, true)
