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

  test("well-known wire types have the JSON types of upstream Tool._parse_function") {
    assertEquals(
      Vector(TypeRef.string, TypeRef.int, TypeRef.double, TypeRef.bool, TypeRef.list, TypeRef.json).map(_.jsonType),
      Vector("string", "integer", "number", "boolean", "array", "object").map(Some(_))
    )
    assertEquals(TypeRef.toolCalls.jsonType, None)
    assertEquals(TypeRef("list[str]").jsonType, None)
  }

  test("a collection type with an item type gives its kind and the text of its item type") {
    assertEquals(TypeRef("list[str]").collection, Some((TypeRef.Collection.List, "str")))
    assertEquals(TypeRef("tuple[int, str]").collection, Some((TypeRef.Collection.Tuple, "int, str")))
    assertEquals(TypeRef("set[str]").collection, Some((TypeRef.Collection.Set, "str")))
    assertEquals(TypeRef("frozenset[list[int]]").collection, Some((TypeRef.Collection.FrozenSet, "list[int]")))
    assertEquals(TypeRef.list.collection, None)
    assertEquals(TypeRef("list[]").collection, None)
    assertEquals(TypeRef("dict[str, int]").collection, None)
  }
