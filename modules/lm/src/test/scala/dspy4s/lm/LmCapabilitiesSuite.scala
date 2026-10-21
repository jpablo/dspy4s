package dspy4s.lm

import dspy4s.lm.contracts.LmCapabilities
import dspy4s.lm.providers.{HttpTransport, OpenAiLanguageModel}
import dspy4s.lm.runtime.ManagedLanguageModel
import munit.FunSuite

/** The capabilities of a model are data (decision L5, PORT_GAPS G-26; upstream `dspy/clients/capabilities.py`). The
  * OpenAI capabilities hold for the official address only. An other OpenAI-compatible server has no capability until
  * the user declares one.
  */
class LmCapabilitiesSuite extends FunSuite:

  // The tests make no request, so the transport opens no connection.
  private val unusedTransport: HttpTransport = HttpTransport.jdk()

  private def flags(lm: OpenAiLanguageModel): (Boolean, Boolean, Boolean) =
    (lm.supportsFunctionCalling, lm.supportsResponseSchema, lm.supportsReasoning)

  test("the official OpenAI address gives the OpenAI capabilities") {
    assertEquals(LmCapabilities.forBaseUrl("https://api.openai.com/v1"), LmCapabilities.openAi)
    assertEquals(LmCapabilities.forBaseUrl("https://API.OpenAI.com/v1/"), LmCapabilities.openAi)
    assertEquals(LmCapabilities.forBaseUrl("https://api.openai.com"), LmCapabilities.openAi)
  }

  test("each other address gives no capability") {
    val others = Vector(
      "http://localhost:11434/v1",
      "http://127.0.0.1:8000/v1",
      "https://openrouter.ai/api/v1",
      "https://my-resource.openai.azure.com/openai",
      "https://api.openai.com.example.org/v1",
      "https://example.org/api.openai.com/v1",
      "not a url",
      ""
    )
    assertEquals(others.map(LmCapabilities.forBaseUrl), others.map(_ => LmCapabilities.none))
  }

  test("an undeclared capability is false") {
    assertEquals(
      flags(OpenAiLanguageModel(model = "m", apiKey = "k").withCapabilities(LmCapabilities())),
      (false, false, false)
    )
    assertEquals(
      LmCapabilities.none,
      LmCapabilities(functionCalling = false, responseSchema = false, reasoning = false)
    )
  }

  test("a model at the official address has the OpenAI capabilities") {
    assertEquals(flags(OpenAiLanguageModel(model = "gpt-4o-mini", apiKey = "k")), (true, true, false))
  }

  test("a model at an other address has no capability") {
    assertEquals(
      flags(OpenAiLanguageModel.local("llama3", baseUrl = "http://localhost:11434/v1")),
      (false, false, false)
    )
    assertEquals(
      flags(OpenAiLanguageModel("m", apiKey = "k", baseUrl = "http://localhost:8000/v1", transport = unusedTransport)),
      (false, false, false)
    )
  }

  test("declared capabilities win over the capabilities of the address") {
    val local = OpenAiLanguageModel.local("llama3", baseUrl = "http://localhost:8000/v1")
    assertEquals(flags(local.withCapabilities(LmCapabilities(functionCalling = true))), (true, false, false))
    assertEquals(flags(local.withCapabilities(LmCapabilities.openAi.copy(reasoning = true))), (true, true, true))
    val official = OpenAiLanguageModel(model = "gpt-4o-mini", apiKey = "k")
    assertEquals(flags(official.withCapabilities(LmCapabilities.none)), (false, false, false))
  }

  test("a managed model reports the capabilities of its delegate") {
    val local   = OpenAiLanguageModel.local("llama3", baseUrl = "http://localhost:8000/v1")
    val managed = ManagedLanguageModel(local.withCapabilities(LmCapabilities(responseSchema = true)))
    assertEquals((managed.supportsFunctionCalling, managed.supportsResponseSchema), (false, true))
  }
