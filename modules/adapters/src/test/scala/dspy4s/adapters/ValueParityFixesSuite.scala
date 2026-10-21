package dspy4s.adapters

import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.TypeRef
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LmOutput
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

/** Value rules of upstream `parse_value` (`dspy/adapters/utils.py`) that the JSON and XML adapters follow. */
class ValueParityFixesSuite extends FunSuite:

  private given RuntimeContext = RuntimeContext()

  private def layoutWith(outputs: FieldSpec*): SignatureLayout =
    SignatureLayout.create(name = "Probe", inputFields = Vector(FieldSpec("question")), outputFields = outputs.toVector)
      .fold(error => fail(s"bad layout: $error"), identity)

  private def jsonValue(layout: SignatureLayout, text: String, field: String): DynamicValue =
    JSONAdapter().parse(layout, LmOutput(text = text))
      .fold(
        error => fail(s"parse failed: $error"),
        output => DynamicValues.recordGet(output.values, field).getOrElse(fail(s"no $field"))
      )

  private def int(value: Int): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Int(value))

  test("a JSON string for an int, a float, or a bool field is read from its text, as upstream") {
    val layout = layoutWith(
      FieldSpec("count", typeRef = TypeRef.int),
      FieldSpec("ratio", typeRef = TypeRef.double),
      FieldSpec("ok", typeRef = TypeRef.bool)
    )
    val text = """{"count": "3", "ratio": "2.5", "ok": "true"}"""
    assertEquals(jsonValue(layout, text, "count"), int(3))
    assertEquals(jsonValue(layout, text, "ratio"), DynamicValue.Primitive(PrimitiveValue.Double(2.5)))
    assertEquals(jsonValue(layout, text, "ok"), DynamicValue.Primitive(PrimitiveValue.Boolean(true)))
  }

  test("a JSON string for a list field is read as the JSON list in its text") {
    val layout = layoutWith(FieldSpec("items", typeRef = TypeRef.list))
    jsonValue(layout, """{"items": "[1, 2]"}""", "items") match
      case sequence: DynamicValue.Sequence => assertEquals(sequence.elements.size, 2)
      case other                           => fail(s"expected a list, got $other")
  }

  test("a JSON list for a list[T] field of a string signature is a list, with the rule of the chat format") {
    // Upstream `parse_value` validates the JSON list with the annotation of the field (`dspy/adapters/utils.py`).
    val layout = SignatureDsl
      .parse("question -> items: list[str], scores: List[int], extra: Optional[list[str]], data: dict[str, int]")
      .fold(error => fail(s"bad signature: $error"), identity)
    val text = """{"items": ["a", "b"], "scores": [1, 2], "extra": null, "data": {"x": 1}}"""

    def list(values: DynamicValue*): DynamicValue = DynamicValue.Sequence(Chunk.from(values))
    def str(value  : String): DynamicValue        = DynamicValue.Primitive(PrimitiveValue.String(value))
    assertEquals(jsonValue(layout, text, "items"), list(str("a"), str("b")))
    assertEquals(jsonValue(layout, text, "scores"), list(int(1), int(2)))
    assertEquals(jsonValue(layout, text, "extra"), DynamicValue.Null)
    assertEquals(
      jsonValue(layout, """{"items": [], "scores": [], "extra": ["c"], "data": {}}""", "extra"),
      list(str("c"))
    )
    assertEquals(jsonValue(layout, text, "data"), DynamicValue.Record(Chunk("x" -> int(1))))
    // The value is the value of the chat format for the same JSON text (`AdapterTextSupport.coerceText`).
    val chatText = "[[ ## items ## ]]\n[\"a\", \"b\"]\n\n[[ ## scores ## ]]\n[1, 2]\n\n[[ ## extra ## ]]\nnull\n\n" +
      "[[ ## data ## ]]\n{\"x\": 1}"
    assertEquals(
      ChatAdapter().parse(layout, LmOutput(text = chatText)).map(_.values),
      JSONAdapter().parse(layout, LmOutput(text = text)).map(_.values)
    )
  }

  test("a JSON value that is not a list for a list[T] field of a string signature is a parse error") {
    val layout = layoutWith(FieldSpec("items", typeRef = TypeRef("list[str]")))
    assert(JSONAdapter().parse(layout, LmOutput(text = """{"items": 3}""")).isLeft)
    assert(JSONAdapter().parse(layout, LmOutput(text = """{"items": {"a": 1}}""")).isLeft)
    assert(ChatAdapter().parse(layout, LmOutput(text = "[[ ## items ## ]]\n3")).isLeft)
  }

  test("a JSON string that is not a number for an int field is still a parse error") {
    val layout = layoutWith(FieldSpec("count", typeRef = TypeRef.int))
    assert(JSONAdapter().parse(layout, LmOutput(text = """{"count": "many"}""")).isLeft)
  }

  test("a JSON number for a string field is its text") {
    val layout = layoutWith(FieldSpec("answer"))
    assertEquals(jsonValue(layout, """{"answer": 3}""", "answer"), DynamicValue.Primitive(PrimitiveValue.String("3")))
  }

  test("an XML text tool_calls field is read as the JSON list of calls, as in the chat format") {
    val layout = layoutWith(FieldSpec("answer"), FieldSpec("tool_calls", typeRef = TypeRef.toolCalls))
    val text   =
      """<outputs><answer>Paris</answer><tool_calls>[{"name": "search", "args": {"q": "x"}}]</tool_calls></outputs>"""
    val parsed =
      XMLAdapter().parse(layout, LmOutput(text = text)).fold(error => fail(s"parse failed: $error"), identity)
    assertEquals(
      DynamicValues.recordGet(parsed.values, "tool_calls"),
      Some(NativeFunctionCalling.encodeToolCalls(Vector(ToolCall(
        name = "search",
        args = DynamicValues.record("q" -> DynamicValues.fromAny("x"))
      ))))
    )
  }
