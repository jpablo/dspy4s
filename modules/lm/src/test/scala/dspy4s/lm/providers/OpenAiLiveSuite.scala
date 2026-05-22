package dspy4s.lm.providers

import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite

/**
 * Integration tests that hit the real OpenAI chat-completions endpoint.
 *
 * Run conditions:
 *   - Set OPENAI_API_KEY in the environment (or in `.env`, loaded by sbt-dotenv).
 *     If the variable is missing or empty these tests are marked "ignored" via
 *     MUnit's `assume(...)`, so `sbt test` still succeeds without credentials.
 *   - Optionally set OPENAI_BASE_URL to point at Azure OpenAI, OpenRouter,
 *     LiteLLM proxy, or any OpenAI-compatible endpoint.
 *
 * Cost: each test pins `max_tokens` to a tiny value and `temperature` to 0,
 * so a full suite run costs roughly 0.01 USD on gpt-4o-mini.
 */
class OpenAiLiveSuite extends FunSuite:

  private val apiKey: Option[String] =
    sys.env.get("OPENAI_API_KEY").orElse(sys.props.get("OPENAI_API_KEY")).filter(_.nonEmpty)
  private val baseUrl: Option[String] =
    sys.env.get("OPENAI_BASE_URL").orElse(sys.props.get("OPENAI_BASE_URL")).filter(_.nonEmpty)
  private val model: String =
    sys.env.getOrElse("OPENAI_LIVE_MODEL", sys.props.getOrElse("OPENAI_LIVE_MODEL", "gpt-4o-mini"))

  private def requireLive(): Unit =
    assume(
      apiKey.isDefined,
      "OPENAI_API_KEY not set — skipping live OpenAI tests (copy .env.example → .env to enable)"
    )

  private def buildLm(): OpenAiLanguageModel =
    val client = OpenAiClient(apiKey = apiKey.get, baseUrl = baseUrl.getOrElse(OpenAiClient.defaultBaseUrl))
    OpenAiLanguageModel(model = model, client = client)

  test("live: call() returns a real chat completion with usage accounting") {
    requireLive()
    val lm = buildLm()
    given RuntimeContext = RuntimeEnvironment.current

    val request = LmRequest(
      model = model,
      mode = LmMode.Chat,
      messages = Vector(Message(role = MessageRole.User, text = Some("Reply with exactly the text: integration-test-passed"))),
      options = Map("max_tokens" -> 10, "temperature" -> 0.0)
    )
    val result = lm.call(request)
    assert(result.isRight, s"call() failed: ${result.left.toOption.map(_.message).getOrElse("?")}")

    val response = result.toOption.get
    assert(response.outputs.nonEmpty, "no output choices")
    val text = response.outputs.head.text
    assert(text.nonEmpty, "response text is empty")
    assert(text.contains("integration"), s"unexpected text: $text")

    val usage = response.usage.getOrElse(fail("usage was not reported"))
    assert(usage.totalTokens > 0L, "usage.totalTokens should be > 0")
    assert(usage.promptTokens > 0L, "usage.promptTokens should be > 0")
    assert(usage.completionTokens > 0L, "usage.completionTokens should be > 0")
    assertEquals(usage.totalTokens, usage.promptTokens + usage.completionTokens)
  }

  test("live: stream() yields multiple text chunks and a final usage chunk") {
    requireLive()
    val lm = buildLm()
    given RuntimeContext = RuntimeEnvironment.current

    val request = LmRequest(
      model = model,
      mode = LmMode.Chat,
      messages = Vector(
        Message(role = MessageRole.System, text = Some("Be concise.")),
        Message(role = MessageRole.User, text = Some("Describe the color of the sky in two short sentences."))
      ),
      options = Map("max_tokens" -> 30, "temperature" -> 0.0)
    )
    val chunks = lm.stream(request).toVector
    assert(chunks.nonEmpty, "streamed no chunks")

    val textOnly = chunks.filter(_.text.nonEmpty)
    assert(textOnly.size >= 2, s"expected multiple text chunks, got ${textOnly.size}")

    val fullText = chunks.map(_.text).mkString
    assert(fullText.nonEmpty, "concatenated text is empty")
    assert(fullText.length >= 2, s"text suspiciously short: '$fullText'")

    val finishChunk = chunks.find(_.finishReason.isDefined)
    assert(finishChunk.isDefined, "no chunk carried a finish_reason")
    val finishReason = finishChunk.get.finishReason.get
    assert(
      finishReason == "stop" || finishReason == "length",
      s"unexpected finish_reason: $finishReason"
    )

    val usageChunks = chunks.filter(_.usage.isDefined)
    assert(usageChunks.nonEmpty, s"no usage chunk observed; check stream_options.include_usage=true is still sent. chunk count=${chunks.size}")
    val usage = usageChunks.last.usage.get
    assert(usage.completionTokens > 0L)
  }

  test("live: client reports openai_auth on a bogus API key") {
    val badClient = OpenAiClient(apiKey = "sk-this-key-does-not-exist-12345", baseUrl = baseUrl.getOrElse(OpenAiClient.defaultBaseUrl))
    val lm = OpenAiLanguageModel(model = model, client = badClient)
    given RuntimeContext = RuntimeEnvironment.current
    val request = LmRequest(
      model = model,
      messages = Vector(Message(role = MessageRole.User, text = Some("x"))),
      options = Map("max_tokens" -> 1)
    )
    val result = lm.call(request)
    assert(result.isLeft, "expected auth failure")
    val error = result.swap.toOption.get match
      case e: dspy4s.core.contracts.RuntimeError => e
      case other => fail(s"expected RuntimeError, got ${other.getClass.getSimpleName}: ${other.message}")
    assert(
      error.component == "openai_auth" || error.component == "openai_http",
      s"unexpected error component: ${error.component} / ${error.message}"
    )
  }
