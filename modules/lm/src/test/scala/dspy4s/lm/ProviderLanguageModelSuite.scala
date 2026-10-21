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

  test("a null option removes the option, also a default option of the model") {
    // Upstream `_canonical` (`dspy/clients/execution.py`) drops each option whose value is `None` after the merge.
    val request = LmRequest(
      model = "openai/test",
      options = DynamicValues.record("reasoning_effort" -> DynamicValue.Null, "max_tokens" := 8)
    )
    val defaults = DynamicValues.record("reasoning_effort" := "high", "temperature" := 0.1)

    assertEquals(
      ProviderRequestNormalizer.effectiveOptions(request, defaults),
      DynamicValues.record("temperature" := 0.1, "max_tokens" := 8)
    )
    val wire = DynamicValues.recordKeys(ProviderRequestNormalizer.normalize(request, defaults))
    assert(!wire.contains("reasoning_effort"), wire.toString)
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

    // Provider knobs from `options` are spread in; the dedicated control field is not.
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
    val rawResponse               = DynamicValues.fromAny(Map(
      "model"   -> "openai/test",
      "choices" -> Vector(
        Map(
          "message" -> Map(
            "role"       -> "assistant",
            "content"    -> "The answer is Brussels.",
            "tool_calls" -> Vector(
              Map(
                "function" -> Map(
                  "name"      -> "search",
                  "arguments" -> Map("query" -> "capital of belgium")
                )
              )
            )
          )
        )
      ),
      "usage" -> Map(
        "prompt_tokens"     -> 5,
        "completion_tokens" -> 7,
        "total_tokens"      -> 12
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
    val result           = lm.call(
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

  test("provider language model emits a tool-only output when the model returns tool calls with no content") {
    // The canonical function-calling response: the assistant message has content:null (here: absent) and a
    // tool_calls array. The output and its toolCalls must be emitted even though there is no text content —
    // dropping it would make native function-calling impossible (the tool call would be lost).
    val rawResponse = DynamicValues.fromAny(Map(
      "model"   -> "openai/test",
      "choices" -> Vector(
        Map(
          "message" -> Map(
            "role"       -> "assistant",
            "tool_calls" -> Vector(
              Map("function" -> Map("name" -> "search", "arguments" -> Map("query" -> "capital of belgium")))
            )
          )
        )
      )
    ))

    val lm = ProviderLanguageModel(
      id = "openai/test",
      mode = LmMode.Chat,
      invoke = _ => Right(rawResponse)
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result           = lm.call(
      LmRequest(
        model = "openai/test",
        mode = LmMode.Chat,
        messages = Vector(Message(role = MessageRole.User, text = Some("Q?")))
      )
    )

    assert(result.isRight, s"a tool-only output must not be dropped; got: $result")
    val out = result.toOption.get.outputs.head
    assertEquals(out.toolCalls.map(_.name), Vector("search"))
    assertEquals(out.text, "")
  }

  test("provider language model parses responses API output blocks") {
    val rawResponse = DynamicValues.fromAny(Map(
      "model"  -> "openai/responses-test",
      "output" -> Vector(
        Map(
          "type"    -> "message",
          "content" -> Vector(
            Map("type" -> "output_text", "text" -> "First line"),
            Map("type" -> "output_text", "text" -> "Second line")
          )
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
    val result           = lm.call(
      LmRequest(
        model = "openai/responses-test",
        mode = LmMode.Responses,
        messages = Vector(Message(role = MessageRole.User, text = Some("Q?")))
      )
    )

    assert(result.isRight)
    val response = result.toOption.get
    // Upstream `responses_outputs` reads only the typed text parts, and joins them with no separator.
    assertEquals(response.outputs.head.text, "First lineSecond line")
    assertEquals(response.usage.get.promptTokens, 3L)
    assertEquals(response.usage.get.completionTokens, 4L)
  }

  test("the mode of the provider selects the payload mode and the parse, not the mode of the request") {
    // Upstream `LM.model_type` is fixed when the LM is built. `OpenAiLanguageModel` follows the same rule.
    val chatBody = DynamicValues.fromAny(Map(
      "choices" -> Vector(Map("message" -> Map("role" -> "assistant", "content" -> "From chat.")))
    ))
    val responsesBody = DynamicValues.fromAny(Map(
      "output" ->
        Vector(Map("type" -> "message", "content" -> Vector(Map("type" -> "output_text", "text" -> "From responses."))))
    ))
    val cases = Vector(
      (LmMode.Chat, LmMode.Responses, chatBody, "chat", "From chat."),
      (LmMode.Responses, LmMode.Chat, responsesBody, "responses", "From responses.")
    )
    given RuntimeContext = RuntimeEnvironment.current
    cases.foreach { (providerMode, requestMode, body, wireMode, text) =>
      var seenMode: Option[String] = None
      val lm                       = ProviderLanguageModel(
        id = "openai/test",
        mode = providerMode,
        invoke = payload =>
          seenMode = DynamicJson.field(payload, "mode").flatMap(DynamicJson.asString)
          Right(body)
      )
      val request = LmRequest(
        model = "openai/test",
        mode = requestMode,
        messages = Vector(Message(role = MessageRole.User, text = Some("Q?")))
      )
      lm.call(request) match
        case Right(response) => assertEquals(response.outputs.map(_.text), Vector(text), providerMode.toString)
        case Left(error)     => fail(s"the $providerMode provider failed on a $requestMode request: $error")
      assertEquals(seenMode, Some(wireMode), providerMode.toString)
    }
  }

  test("provider language model returns parse error when provider output is empty") {
    val lm = ProviderLanguageModel(
      id = "openai/empty",
      mode = LmMode.Chat,
      invoke = _ => Right(DynamicValues.fromAny(Map("model" -> "openai/empty", "choices" -> Vector.empty)))
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result           = lm.call(
      LmRequest(
        model = "openai/empty",
        mode = LmMode.Chat,
        messages = Vector(Message(role = MessageRole.User, text = Some("Q?")))
      )
    )

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[ParseError])
  }
