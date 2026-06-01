package dspy4s.lm.providers

import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import io.github.cdimascio.dotenv.Dotenv
import java.io.File
import munit.FunSuite

/**
 * Integration tests that hit the real OpenAI chat-completions endpoint.
 *
 * Run conditions:
 *   - Set OPENAI_API_KEY in the environment, in JVM system properties, or in a
 *     `.env` file at the project root (resolved by walking up from the test cwd).
 *     If the variable is missing or empty these tests are marked "ignored" via
 *     MUnit's `assume(...)`, so `sbt test` still succeeds without credentials.
 *   - Optionally set OPENAI_BASE_URL to point at Azure OpenAI, OpenRouter,
 *     LiteLLM proxy, or any OpenAI-compatible endpoint.
 *
 * Cost: each test pins `max_tokens` to a tiny value and `temperature` to 0,
 * so a full suite run costs roughly 0.01 USD on gpt-4o-mini.
 */
class OpenAiLiveSuite extends FunSuite:

  private val dotenv: Dotenv =
    val dir = Iterator
      .iterate(new File(".").getAbsoluteFile)(_.getParentFile)
      .takeWhile(_ != null)
      .take(8)
      .find(d => new File(d, ".env").isFile)
      .map(_.getAbsolutePath)
      .getOrElse(".")
    Dotenv.configure().directory(dir).ignoreIfMissing().ignoreIfMalformed().load()

  private def lookup(key: String): Option[String] =
    sys.env.get(key)
      .orElse(sys.props.get(key))
      .orElse(Option(dotenv.get(key)))
      .filter(_.nonEmpty)

  private val apiKey: Option[String] = lookup("OPENAI_API_KEY")
  private val baseUrl: Option[String] = lookup("OPENAI_BASE_URL")
  private val model: String = lookup("OPENAI_LIVE_MODEL").getOrElse("gpt-4o-mini")

  private def hasOptIn: Boolean =
    lookup("OPENAI_LIVE_ENABLED").exists(v => v != "0" && v != "false")

  private def requireLive(): Unit =
    assume(
      apiKey.isDefined && hasOptIn,
      "Live OpenAI tests require OPENAI_API_KEY *and* OPENAI_LIVE_ENABLED=true (set both in .env)"
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
