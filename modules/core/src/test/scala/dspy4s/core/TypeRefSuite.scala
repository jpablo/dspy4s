package dspy4s.core

import dspy4s.core.contracts.TypeRef
import munit.FunSuite

class TypeRefSuite extends FunSuite:

  test("well-known wire types expose their Python/DSPy names") {
    assertEquals(TypeRef.string.pythonTypeName, Some("str"))
    assertEquals(TypeRef.int.pythonTypeName, Some("int"))
    assertEquals(TypeRef.double.pythonTypeName, Some("float"))
    assertEquals(TypeRef.bool.pythonTypeName, Some("bool"))
    assertEquals(TypeRef.list.pythonTypeName, Some("list"))
    assertEquals(TypeRef.json.pythonTypeName, Some("dict"))
  }

  test("opaque and special wire types have no direct Python type name") {
    assertEquals(TypeRef("custom").pythonTypeName, None)
    assertEquals(TypeRef.toolCalls.pythonTypeName, None)
  }
