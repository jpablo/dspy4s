package dspy4s.lm

import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.lm.runtime.ProviderLanguageModel
import dspy4s.lm.runtime.ProviderRequestNormalizer
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
      options = Map("temperature" -> 0.2),
      requestId = Some("req-1")
    )

    val normalized = ProviderRequestNormalizer.normalize(request, defaultOptions = Map("max_tokens" -> 64))

    assertEquals(normalized("model"), "openai/test")
    assertEquals(normalized("mode"), "chat")
    assertEquals(normalized("request_id"), "req-1")
    assertEquals(normalized("temperature"), 0.2)
    assertEquals(normalized("max_tokens"), 64)
    val messages = normalized("messages").asInstanceOf[Vector[Map[String, Any]]]
    assertEquals(messages.head("role"), "system")
    assertEquals(messages.last("content"), "Hello")
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

    val normalized = ProviderRequestNormalizer.normalize(request)
    assertEquals(normalized("mode"), "text")
    assertEquals(normalized("prompt"), "Line one\nLine two")
  }

  test("provider language model parses chat responses including tool calls and usage") {
    var seenRequest = Map.empty[String, Any]
    val rawResponse = Map(
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
    )

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
    assertEquals(seenRequest("model"), "openai/test")
    assertEquals(response.outputs.head.text, "The answer is Brussels.")
    assertEquals(response.outputs.head.toolCalls.head.name, "search")
    assertEquals(response.usage.get.totalTokens, 12L)
  }

  test("provider language model parses responses API output blocks") {
    val rawResponse = Map(
      "model" -> "openai/responses-test",
      "output" -> Vector(
        Map(
          "type" -> "message",
          "content" -> Vector(Map("type" -> "output_text", "text" -> "First line"), Map("text" -> "Second line"))
        )
      ),
      "usage" -> Map("input_tokens" -> 3, "output_tokens" -> 4, "total_tokens" -> 7)
    )

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
      invoke = _ => Right(Map("model" -> "openai/empty", "choices" -> Vector.empty))
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
