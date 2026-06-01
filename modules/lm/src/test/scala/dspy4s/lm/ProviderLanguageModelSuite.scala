package dspy4s.lm

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.lm.providers.DynamicJson
import dspy4s.lm.runtime.ProviderLanguageModel
import dspy4s.lm.runtime.ProviderRequestNormalizer
import zio.blocks.schema.DynamicValue
import munit.FunSuite

class ProviderLanguageModelSuite extends FunSuite:
  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("normalizer encodes chat request with options and request id") {
    val request = LmRequest(
      model = "openai/test",
      mode = LmMode.Chat,
      messages = Vector(
        Message(role = MessageRole.System, text = Some("You are helpful")),
        Message(role = MessageRole.User, text = Some("Hello"))
      ),
      options = DynamicValues.record("temperature" := 0.2),
      requestId = Some("req-1")
    )

    val n = DynamicValues.recordToMap(
      ProviderRequestNormalizer.normalize(request, defaultOptions = DynamicValues.record("max_tokens" := 64))
    )

    assertEquals(n("model"), "openai/test": Any)
    assertEquals(n("mode"), "chat": Any)
    assertEquals(n("request_id"), "req-1": Any)
    assertEquals(n("temperature"), 0.2: Any)
    assertEquals(n("max_tokens"), 64: Any)
    val messages = n("messages").asInstanceOf[List[Map[String, Any]]]
    assertEquals(messages.head("role"), "system": Any)
    assertEquals(messages.last("content"), "Hello": Any)
  }

  test("normalizer never serializes the framework-only rolloutId into the provider body") {
    val request = LmRequest(
      model = "openai/test",
      mode = LmMode.Chat,
      messages = Vector(Message(role = MessageRole.User, text = Some("Hi"))),
      options = DynamicValues.record("temperature" := 0.2),
      rolloutId = Some(5)
    )

    val n = DynamicValues.recordToMap(ProviderRequestNormalizer.normalize(request))

    // Provider knobs from `options` are spread in; the typed control field is not.
    assertEquals(n("temperature"), 0.2: Any)
    assert(!n.contains("rollout_id"), s"rolloutId leaked into the request body: $n")
    assert(!n.contains("rolloutId"), s"rolloutId leaked into the request body: $n")
  }

  test("normalizer encodes text mode as prompt") {
    val request = LmRequest(
      model = "openai/test",
      mode = LmMode.Text,
      messages = Vector(
        Message(role = MessageRole.User, text = Some("Line one")),
        Message(role = MessageRole.User, text = Some("Line two"))
      )
    )

    val n = DynamicValues.recordToMap(ProviderRequestNormalizer.normalize(request))
    assertEquals(n("mode"), "text": Any)
    assertEquals(n("prompt"), "Line one\nLine two": Any)
  }

  test("provider language model parses chat responses including tool calls and usage") {
    var seenRequest: DynamicValue = DynamicValue.Record.empty
    val rawResponse = DynamicValues.fromAny(Map(
      "model" -> "openai/test",
      "choices" -> Vector(
        Map(
          "message" -> Map(
            "role" -> "assistant",
            "content" -> "The answer is Brussels.",
            "tool_calls" -> Vector(
              Map(
                "function" -> Map(
                  "name" -> "search",
                  "arguments" -> Map("query" -> "capital of belgium")
                )
              )
            )
          )
        )
      ),
      "usage" -> Map(
        "prompt_tokens" -> 5,
        "completion_tokens" -> 7,
        "total_tokens" -> 12
      )
    ))

    val lm = ProviderLanguageModel(
      id = "openai/test",
      mode = LmMode.Chat,
      invoke = request =>
        seenRequest = request
        Right(rawResponse)
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = lm.call(
      LmRequest(
        model = "openai/test",
        mode = LmMode.Chat,
        messages = Vector(Message(role = MessageRole.User, text = Some("Q?")))
      )
    )

    assert(result.isRight)
    val response = result.toOption.get
    assertEquals(DynamicJson.field(seenRequest, "model").flatMap(DynamicJson.asString), Some("openai/test"))
    assertEquals(response.outputs.head.text, "The answer is Brussels.")
    assertEquals(response.outputs.head.toolCalls.head.name, "search")
    assertEquals(response.usage.get.totalTokens, 12L)
  }

  test("provider language model parses responses API output blocks") {
    val rawResponse = DynamicValues.fromAny(Map(
      "model" -> "openai/responses-test",
      "output" -> Vector(
        Map(
          "type" -> "message",
          "content" -> Vector(Map("type" -> "output_text", "text" -> "First line"), Map("text" -> "Second line"))
        )
      ),
      "usage" -> Map("input_tokens" -> 3, "output_tokens" -> 4, "total_tokens" -> 7)
    ))

    val lm = ProviderLanguageModel(
      id = "openai/responses-test",
      mode = LmMode.Responses,
      invoke = _ => Right(rawResponse)
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = lm.call(
      LmRequest(
        model = "openai/responses-test",
        mode = LmMode.Responses,
        messages = Vector(Message(role = MessageRole.User, text = Some("Q?")))
      )
    )

    assert(result.isRight)
    val response = result.toOption.get
    assertEquals(response.outputs.head.text, "First line\nSecond line")
    assertEquals(response.usage.get.promptTokens, 3L)
    assertEquals(response.usage.get.completionTokens, 4L)
  }

  test("provider language model returns parse error when provider output is empty") {
    val lm = ProviderLanguageModel(
      id = "openai/empty",
      mode = LmMode.Chat,
      invoke = _ => Right(DynamicValues.fromAny(Map("model" -> "openai/empty", "choices" -> Vector.empty)))
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = lm.call(
      LmRequest(
        model = "openai/empty",
        mode = LmMode.Chat,
        messages = Vector(Message(role = MessageRole.User, text = Some("Q?")))
      )
    )

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[ParseError])
  }
