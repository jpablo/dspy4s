package dspy4s.core

import dspy4s.core.contracts.{DynamicValues, Reasoning, SignatureLayout, TypeRef}
import munit.FunSuite
import zio.blocks.schema.Schema

/** The type `Reasoning` (upstream `dspy.Reasoning`, `tests/adapters/test_reasoning.py`). */
class ReasoningSuite extends FunSuite:

  test("a reasoning value is a text") {
    val reasoning    = Reasoning("  Hello World  ")
    val text: String = reasoning
    assertEquals(text, "  Hello World  ")
    assertEquals(reasoning.content, "  Hello World  ")
    assertEquals(reasoning.strip, "Hello World")
    assertEquals(reasoning.length, 15)
    assert(reasoning.contains("World"))
    assertEquals(reasoning + "!", "  Hello World  !")
  }

  test("a reasoning value is equal to a reasoning value and to a text with the same content") {
    val reasoning = Reasoning("why")
    assert(reasoning == Reasoning("why"))
    assert(reasoning == "why")
    assert("why" == reasoning)
    assert(reasoning != "because")
    assert(reasoning != Reasoning("because"))
  }

  test("the schema of a reasoning value is a text with the type id of the type") {
    val schema = summon[Schema[Reasoning]]
    assertEquals(schema.toDynamicValue(Reasoning("why")), DynamicValues.string("why"))
    assertEquals(schema.fromDynamicValue(DynamicValues.string("why")).map(_.content), Right("why"))
    assertEquals(schema.reflect.typeId.name, "Reasoning")
    assert(Reasoning.hasTypeId(schema.reflect.typeId))
    assert(!Reasoning.hasTypeId(summon[Schema[String]].reflect.typeId))
  }

  test("the wire type shows as a text") {
    assertEquals(TypeRef.reasoning.pythonTypeName, Some("str"))
    assertEquals(TypeRef.reasoning.jsonType, Some("string"))
  }

  test("the type token of a string signature gives the wire type") {
    assertEquals(TypeRef.fromToken("Reasoning"), TypeRef.reasoning)
    assertEquals(TypeRef.fromToken("dspy.Reasoning"), TypeRef.reasoning)
    val layout = SignatureLayout.parse("question -> reasoning: dspy.Reasoning, answer")
      .fold(error => fail(error.toString), identity)
    assertEquals(layout.outputFields.map(_.typeRef), Vector(TypeRef.reasoning, TypeRef.string))
  }
