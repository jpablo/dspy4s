package dspy4s.core.runtime

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

/** Pure unit tests for the variable-assignment prelude (no sandbox needed; the live behavior is covered by
  * [[DenoPyodideInterpreterSuite]]).
  */
class DenoPyodideVariablesSuite extends FunSuite:

  test("prelude is empty for no variables") {
    assertEquals(DenoPyodideVariables.prelude(Map.empty), Right(""))
  }

  test("prelude imports json and emits one json.loads assignment per variable") {
    val prelude = DenoPyodideVariables.prelude(Map[String, DynamicValue](
      "ctx" -> DynamicValues.fromAny("some text"),
      "n"   -> DynamicValues.fromAny(7)
    )).toOption.get
    val lines = prelude.linesIterator.toVector
    assertEquals(lines.head, "import json")
    assertEquals(lines.size, 3)
    assert(lines.exists(l => l.startsWith("ctx = json.loads(")), prelude)
    assert(lines.exists(l => l.startsWith("n = json.loads(")), prelude)
  }

  test("prelude depends only on the variables, so equal maps build identical text (cacheable)") {
    val build = () =>
      DenoPyodideVariables.prelude(Map[String, DynamicValue](
        "rec" -> DynamicValues.record("inner" := "ok"),
        "xs"  -> DynamicValues.fromAny(List(1, 2, 3))
      ))
    assertEquals(build(), build())
  }

  test("invalid variable names are rejected (non-identifier, Python keyword, and the reserved 'json')") {
    assert(DenoPyodideVariables.prelude(Map("not-an-identifier" -> DynamicValues.fromAny(1))).isLeft)
    assert(DenoPyodideVariables.prelude(Map("lambda" -> DynamicValues.fromAny(1))).isLeft)
    assert(DenoPyodideVariables.prelude(Map("json" -> DynamicValues.fromAny(1))).isLeft)
  }

  test("a text value with a lone surrogate is a Left, not an exception") {
    // The first half of an emoji, for example from a text that was cut at a fixed length.
    val cut = DynamicValue.Primitive(PrimitiveValue.String("cut \uD83D"))
    assert(DenoPyodideVariables.prelude(Map("ctx" -> cut)).isLeft)
  }

  test("a Unicode identifier is a valid variable name, as upstream str.isidentifier") {
    assert(DenoPyodideVariables.prelude(Map("café" -> DynamicValues.fromAny(1))).isRight)
  }
