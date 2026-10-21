package dspy4s.adapters

import dspy4s.adapters.internal.AdapterTextSupport
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.TypeRef
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

/** The text rules of upstream `parse_value` (`dspy/adapters/utils.py`): `json_repair` reads the text, then pydantic
  * validates it in lax mode.
  */
class TextValueRulesSuite extends FunSuite:

  private def value(field: FieldSpec, raw: String): DynamicValue =
    AdapterTextSupport.outputTextValue(field, raw).fold(error => fail(s"$raw: $error"), identity)

  test("an int text may have spaces and a zero fraction, as pydantic lax mode accepts") {
    val count = FieldSpec("count", typeRef = TypeRef.int)
    assertEquals(value(count, " 3 "), DynamicValue.Primitive(PrimitiveValue.Int(3)))
    assertEquals(value(count, "3.0"), DynamicValue.Primitive(PrimitiveValue.Int(3)))
    assert(AdapterTextSupport.outputTextValue(count, "3.5").isLeft)
    assertEquals(
      value(FieldSpec("ratio", typeRef = TypeRef.double), " 2.5\n"),
      DynamicValue.Primitive(PrimitiveValue.Double(2.5))
    )
  }

  test("a list[T] field reads the JSON list in its text") {
    value(FieldSpec("tags", typeRef = TypeRef("list[str]")), """["a", "b"]""") match
      case sequence: DynamicValue.Sequence =>
        assertEquals(sequence.elements.toVector.map(DynamicValues.renderText), Vector("a", "b"))
      case other => fail(s"expected a list, got $other")
    assert(AdapterTextSupport.outputTextValue(FieldSpec("tags", typeRef = TypeRef("list[str]")), "not a list").isLeft)
  }

  test("the text null of an optional tool_calls field is a null") {
    val calls = FieldSpec("tool_calls", typeRef = TypeRef.toolCalls, optional = true)
    assertEquals(value(calls, "null"), DynamicValue.Null)
    assertEquals(value(calls, "None"), DynamicValue.Null)
  }
