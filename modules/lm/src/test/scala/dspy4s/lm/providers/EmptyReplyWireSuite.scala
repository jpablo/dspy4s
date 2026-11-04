package dspy4s.lm.providers

import dspy4s.core.contracts.{DynamicValues, PythonText}
import dspy4s.lm.contracts.LmOutput
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** A reply with no text is an output with an empty text, as upstream `_process_completion` and `responses_outputs` keep
  * it (`dspy/clients/base_lm.py`, `dspy/clients/legacy_outputs.py`). The adapter call then gives the parse error, so
  * the reply can reach the fallback. Only a chat reply with no choices has no output.
  */
class EmptyReplyWireSuite extends FunSuite:

  private def record(json: String): DynamicValue =
    PythonText.parseJson(json).fold(error => fail(error.toString), identity)

  private def chat(json: String): Vector[LmOutput] =
    ChatWire.outputs(record(json)).fold(error => fail(error.toString), identity)

  private def responses(json: String): Vector[LmOutput] =
    ResponsesWire.outputs(record(json)).fold(error => fail(error.toString), identity)

  test("a chat choice with an empty, a null, or a blank content is one output with an empty text") {
    Vector(""""content": """"", """"content": null""", """"content": "  \n """").foreach { content =>
      val outputs = chat(s"""{"choices": [{"message": {"role": "assistant", $content}, "finish_reason": "stop"}]}""")
      assertEquals(outputs.map(_.text), Vector(""), content)
    }
  }

  test("a chat choice with no text stays in its place among the choices") {
    val outputs = chat(
      """{"choices": [{"message": {"content": "a"}}, {"message": {"content": ""}}, {"message": {"content": "c"}}]}"""
    )
    assertEquals(outputs.map(_.text), Vector("a", "", "c"))
  }

  test("a chat reply with no choices has no output") {
    assertEquals(chat("""{"choices": []}"""), Vector.empty)
  }

  test("a text choice with an empty text is one output with an empty text") {
    val outputs =
      ChatWire.textOutputs(record("""{"choices": [{"text": ""}]}""")).fold(error => fail(error.toString), identity)
    assertEquals(outputs.map(_.text), Vector(""))
  }

  test("a Responses body with reasoning and no text is one output with an empty text and the reasoning") {
    val outputs = responses(
      """{"output": [{"type": "reasoning", "content": [{"type": "reasoning_text", "text": "why"}]}]}"""
    )
    assertEquals(outputs.map(output => (output.text, output.reasoning)), Vector(("", "why")))
  }

  test("a Responses body with an empty output array is one output with an empty text") {
    assertEquals(responses("""{"output": []}""").map(_.text), Vector(""))
  }

  test("the JSON helper of this suite reads the bodies") {
    assert(DynamicValues.asRecord(record("""{"output": []}""")).isDefined)
  }

  test("a blank top-level text with no choices is one output with an empty text in the text mode") {
    val outputs = ChatWire.textOutputs(record("""{"text": "  \n "}""")).fold(error => fail(error.toString), identity)
    assertEquals(outputs.map(_.text), Vector(""))
  }

  test("a reply with no top-level text and no choices has no output in the text mode") {
    val outputs = ChatWire.textOutputs(record("""{"choices": []}""")).fold(error => fail(error.toString), identity)
    assertEquals(outputs, Vector.empty)
  }

  test("a chat choice with a blank content and tool calls keeps the tool calls and has an empty text") {
    val outputs = chat(
      """{"choices": [{"message": {"content": " ", "tool_calls": [
        |{"id": "c1", "function": {"name": "search", "arguments": "{}"}}]}}]}""".stripMargin
    )
    assertEquals(outputs.map(_.text), Vector(""))
    assertEquals(outputs.flatMap(_.toolCalls).map(_.name), Vector("search"))
  }

  test("a chat choice with no text keeps the metadata of the choice") {
    val outputs = chat("""{"choices": [{"message": {"content": null}, "finish_reason": "content_filter"}]}""")
    assertEquals(
      outputs.flatMap(output => DynamicValues.recordGet(output.metadata, "finish_reason")).flatMap(
        DynamicValues.asString
      ),
      Vector("content_filter")
    )
  }

  test("a Responses body with a blank text is one output with an empty text, its citations, and its tool calls") {
    val outputs = responses(
      """{"output": [
        |{"type": "message", "content": [{"type": "output_text", "text": "  \n ",
        |"annotations": [{"url": "https://a.test"}]}]},
        |{"type": "function_call", "name": "search", "arguments": "{}", "call_id": "c1"}]}""".stripMargin
    )
    assertEquals(outputs.map(_.text), Vector(""))
    assertEquals(outputs.flatMap(_.toolCalls).map(_.name), Vector("search"))
    assertEquals(outputs.flatMap(_.citations).size, 1)
  }

  test("a Responses text that is not blank keeps its white space") {
    val outputs = responses(
      """{"output": [{"type": "message", "content": [{"type": "output_text", "text": " a "}]}]}"""
    )
    assertEquals(outputs.map(_.text), Vector(" a "))
  }
