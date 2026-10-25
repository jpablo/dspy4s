package dspy4s.lm.providers

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.:=
import dspy4s.lm.contracts.ContentPart
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

/** The Responses wire shapes (upstream `chat_to_responses` in `dspy/clients/legacy_requests.py` and `responses_outputs`
  * in `dspy/clients/legacy_outputs.py`; PORT_GAPS G-26, upgrade item L7).
  */
class ResponsesWireSuite extends FunSuite:

  private def record(json: String): DynamicValue.Record =
    DynamicJson.decode(json) match
      case Right(value: DynamicValue.Record) => value
      case other                             => fail(s"not a JSON object: $other")

  /** A JSON value as plain Scala data: an object is an unordered map, and a number is a `BigDecimal`. */
  private def plain(value: DynamicValue): Any =
    value match
      case rec: DynamicValue.Record                              => DynamicValues.recordEntries(rec).map((name, item) => name -> plain(item)).toMap
      case sequence: DynamicValue.Sequence                       => sequence.elements.toVector.map(plain)
      case DynamicValue.Primitive(PrimitiveValue.String(text))   => text
      case DynamicValue.Primitive(PrimitiveValue.Boolean(flag))  => flag
      case DynamicValue.Primitive(PrimitiveValue.Int(number))    => BigDecimal(number)
      case DynamicValue.Primitive(PrimitiveValue.Long(number))   => BigDecimal(number)
      case DynamicValue.Primitive(PrimitiveValue.Double(number)) => BigDecimal(number)
      case _: DynamicValue.Null.type                             => None
      case other                                                 => other.toString

  /** The Responses body of a request for the model `m` with `messages` and the options of the JSON object `options`. */
  private def same(messages: Vector[Message], options: String, responses: String): Unit =
    val request = LmRequest(model = "m", messages = messages, options = record(options))
    assertEquals(plain(ResponsesWire.request(request)), plain(record(responses)))

  private def user(text: String): Message = Message(role = MessageRole.User, text = Some(text))

  // ── The request ─────────────────────────────────────────────────────────────────────────────────────────────

  test("messages become input items with text blocks, and the role sets the block type") {
    same(
      Vector(
        Message(role = MessageRole.System, text = Some("Be terse.")),
        Message(role = MessageRole.User, text = Some("Hi"), name = Some("ann")),
        Message(role = MessageRole.Assistant, text = Some("Hello."))
      ),
      "{}",
      """{"model": "m", "input": [
        |  {"role": "system", "content": [{"type": "input_text", "text": "Be terse."}]},
        |  {"role": "user", "content": [{"type": "input_text", "text": "Hi"}], "name": "ann"},
        |  {"role": "assistant", "content": [{"type": "output_text", "text": "Hello."}]}
        |]}""".stripMargin
    )
  }

  test("tool calls become function_call items, and a tool message becomes a function_call_output item") {
    same(
      Vector(
        Message(
          role = MessageRole.Assistant,
          toolCalls = Vector(ToolCall(name = "search", args = DynamicValues.record("q" := "cats"), id = Some("c1")))
        ),
        Message(role = MessageRole.Tool, text = Some("3 cats"), toolCallId = Some("c1"), name = Some("search")),
        Message(
          role = MessageRole.Assistant,
          text = Some("Next."),
          toolCalls = Vector(ToolCall(name = "lookup", args = DynamicValue.Record.empty))
        )
      ),
      "{}",
      """{"model": "m", "input": [
        |  {"type": "function_call", "name": "search", "arguments": "{\"q\":\"cats\"}", "call_id": "c1"},
        |  {"type": "function_call_output", "output": "3 cats", "call_id": "c1"},
        |  {"role": "assistant", "content": [{"type": "output_text", "text": "Next."}]},
        |  {"type": "function_call", "name": "lookup", "arguments": "{}"}
        |]}""".stripMargin
    )
  }

  test("a tool message with parts gives the joined text of its text parts") {
    same(
      Vector(Message(
        role = MessageRole.Tool,
        parts = Vector(
          ContentPart(kind = "text", payload = "3 "),
          ContentPart(kind = "image_url", payload = "https://x/cat.png"),
          ContentPart(kind = "input_text", payload = "cats")
        )
      )),
      "{}",
      """{"model": "m", "input": [{"type": "function_call_output", "output": "3 cats"}]}"""
    )
  }

  test("content parts become blocks: text blocks change type, an image becomes input_image, others stay") {
    same(
      Vector(Message(
        role = MessageRole.User,
        parts = Vector(
          ContentPart(kind = "text", payload = "Describe."),
          ContentPart(kind = "image_url", payload = "https://x/dog.png"),
          ContentPart(kind = "input_audio", payload = "abc", metadata = Map("format" -> "wav"))
        )
      )),
      "{}",
      """{"model": "m", "input": [{"role": "user", "content": [
        |  {"type": "input_text", "text": "Describe."},
        |  {"type": "input_image", "image_url": "https://x/dog.png"},
        |  {"type": "input_audio", "text": "abc", "metadata": {"format": "wav"}}
        |]}]}""".stripMargin
    )
  }

  test("token limits, reasoning effort, and null options get the upstream names") {
    same(
      Vector.empty,
      """{"max_completion_tokens": 50, "reasoning_effort": "low", "temperature": null, "stop": [], "top_p": 0.5}""",
      """{"model": "m", "input": [], "max_output_tokens": 50, "reasoning": {"effort": "low", "summary": "auto"},
        |  "top_p": 0.5}""".stripMargin
    )
    same(Vector.empty, """{"max_tokens": null}""", """{"model": "m", "input": []}""")
    same(
      Vector.empty,
      """{"reasoning_effort": "high", "reasoning": {"effort": "low"}}""",
      """{"model": "m", "input": [], "reasoning": {"effort": "low"}}"""
    )
  }

  test("tools and the tool choice are flattened") {
    same(
      Vector.empty,
      """{"tools": [
        |  {"type": "function", "function": {"name": "search", "description": "Find.", "parameters": {"type": "object"}}},
        |  {"type": "function", "function": {"name": "lookup", "description": null, "parameters": {}}}
        |], "tool_choice": {"type": "function", "function": {"name": "search"}}}""".stripMargin,
      """{"model": "m", "input": [], "tools": [
        |  {"type": "function", "name": "search", "description": "Find.", "parameters": {"type": "object"}},
        |  {"type": "function", "name": "lookup", "parameters": {}}
        |], "tool_choice": {"type": "function", "name": "search"}}""".stripMargin
    )
  }

  test("response_format moves to text.format, and a json_schema format is flattened") {
    same(
      Vector.empty,
      """{"response_format": {"type": "json_schema",
        |  "json_schema": {"name": "DSPyProgramOutputs", "schema": {"type": "object"}, "strict": true}}}""".stripMargin,
      """{"model": "m", "input": [], "text": {"format": {"type": "json_schema", "name": "DSPyProgramOutputs",
        |  "schema": {"type": "object"}, "strict": true}}}""".stripMargin
    )
    same(
      Vector.empty,
      """{"response_format": {"type": "json_object"}}""",
      """{"model": "m", "input": [], "text": {"format": {"type": "json_object"}}}"""
    )
  }

  test("an input option stays, and the body has no other change") {
    same(
      Vector(user("x")),
      """{"input": "Hi", "max_tokens": 5}""",
      """{"model": "m", "input": "Hi", "max_tokens": 5}"""
    )
  }

  test("a messages option does not go into the body, and the default options are under the request options") {
    val request = LmRequest(
      model = "m",
      messages = Vector(user("Hi")),
      options = record("""{"messages": [{"role": "user", "content": "old"}], "top_p": 0.5}""")
    )
    assertEquals(
      plain(ResponsesWire.request(request, defaultOptions = record("""{"top_p": 0.1, "temperature": 0.2}"""))),
      plain(record(
        """{"model": "m", "top_p": 0.5, "temperature": 0.2,
          | "input": [{"role": "user", "content": [{"type": "input_text", "text": "Hi"}]}]}""".stripMargin
      ))
    )
  }

  // ── The outputs ─────────────────────────────────────────────────────────────────────────────────────────────

  test("a Responses body gives one output with the joined text, the tool calls, the reasoning, and the citations") {
    val body = record(
      """{"model": "m", "output": [
        |  {"type": "reasoning", "id": "r1", "summary": [{"type": "summary_text", "text": "Think. "}, {"text": "Done."}]},
        |  {"type": "message", "role": "assistant", "content": [
        |    {"type": "output_text", "text": "Paris is ", "annotations": [
        |      {"type": "url_citation", "url": "https://x", "title": "X", "start_index": 0}
        |    ]},
        |    {"type": "output_text", "text": "the capital."}
        |  ]},
        |  {"type": "function_call", "id": "fc1", "call_id": "call_1", "name": "search", "arguments": "{\"q\":\"x\"}"}
        |]}""".stripMargin
    )
    ResponsesWire.outputs(body) match
      case Right(Vector(output)) =>
        assertEquals(output.text, "Paris is the capital.")
        assertEquals(
          output.toolCalls,
          Vector(ToolCall(name = "search", args = record("""{"q":"x"}"""), id = Some("call_1")))
        )
        assertEquals(DynamicValues.recordGet(output.metadata, "reasoning_content").map(plain), Some("Think. Done."))
        assertEquals(
          DynamicValues.recordGet(output.metadata, "citations").map(plain),
          Some(Vector(Map(
            "type"     -> "citation",
            "title"    -> "X",
            "url"      -> "https://x",
            "metadata" -> Map("type" -> "url_citation", "start_index" -> BigDecimal(0))
          )))
        )
      case other => fail(s"expected one output, got $other")
  }

  test("function-call arguments that are not JSON give an empty record, and the chat path keeps its rule") {
    // Upstream `responses_outputs` (`dspy/clients/legacy_outputs.py`): `json.loads` fails, and the arguments are `{}`.
    val body = record(
      """{"output": [{"type": "function_call", "call_id": "call_1", "name": "search", "arguments": "not json"}]}"""
    )
    ResponsesWire.outputs(body) match
      case Right(Vector(output)) => assertEquals(
          output.toolCalls,
          Vector(ToolCall(name = "search", args = DynamicValue.Record.empty, id = Some("call_1")))
        )
      case other => fail(s"expected one output, got $other")
    val chat = record(
      """{"choices": [{"message": {"role": "assistant", "content": null, "tool_calls": [
        |  {"id": "call_1", "type": "function", "function": {"name": "search", "arguments": "not json"}}
        |]}}]}""".stripMargin
    )
    ChatWire.outputs(chat) match
      case Right(outputs) =>
        assertEquals(outputs.flatMap(_.toolCalls).map(_.args), Vector(record("""{"input": "not json"}""")))
      case Left(error) => fail(s"the chat parse failed: $error")
  }

  test("a Responses body with no text and no tool call gives no output, and a body with no output array is an error") {
    assertEquals(
      ResponsesWire.outputs(record("""{"output": [{"type": "reasoning", "summary": []}]}""")),
      Right(Vector.empty)
    )
    assert(ResponsesWire.outputs(record("""{"output": "x"}""")).left.exists(_.isInstanceOf[ParseError]))
  }
