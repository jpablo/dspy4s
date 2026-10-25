package dspy4s.core

import dspy4s.core.contracts.Constraint
import dspy4s.core.contracts.FieldConstraints
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.TypeRef
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

class FieldConstraintsSuite extends FunSuite:

  test("FieldConstraints helpers render the exact upstream strings") {
    assertEquals(FieldConstraints.gt(0).render, "greater than: 0")
    assertEquals(FieldConstraints.ge(1).render, "greater than or equal to: 1")
    assertEquals(FieldConstraints.lt(100).render, "less than: 100")
    assertEquals(FieldConstraints.le(10).render, "less than or equal to: 10")
    assertEquals(FieldConstraints.minLength(3).render, "minimum length: 3")
    assertEquals(FieldConstraints.maxLength(10).render, "maximum length: 10")
    assertEquals(FieldConstraints.multipleOf(5).render, "a multiple of the given number: 5")
  }

  test("FieldConstraints renders whole numbers without trailing .0 noise") {
    assertEquals(FieldConstraints.gt(0.0).render, "greater than: 0")
    assertEquals(FieldConstraints.lt(2.5).render, "less than: 2.5")
    assertEquals(FieldConstraints.multipleOf(0.25).render, "a multiple of the given number: 0.25")
  }

  test("FieldConstraints map to JSON-Schema keywords") {
    assertEquals(FieldConstraints.gt(0).schemaKeyword, "exclusiveMinimum")
    assertEquals(FieldConstraints.ge(1).schemaKeyword, "minimum")
    assertEquals(FieldConstraints.lt(100).schemaKeyword, "exclusiveMaximum")
    assertEquals(FieldConstraints.le(10).schemaKeyword, "maximum")
    assertEquals(FieldConstraints.minLength(3).schemaKeyword, "minLength")
    assertEquals(FieldConstraints.maxLength(10).schemaKeyword, "maxLength")
    assertEquals(FieldConstraints.multipleOf(5).schemaKeyword, "multipleOf")
  }

  test("a length is an Int schema value, a whole bound a Long, and a fractional bound a Double") {
    def int(n   : Int)    = DynamicValue.Primitive(PrimitiveValue.Int(n))
    def long(n  : Long)   = DynamicValue.Primitive(PrimitiveValue.Long(n))
    def double(n: Double) = DynamicValue.Primitive(PrimitiveValue.Double(n))
    assertEquals(FieldConstraints.gt(0).schemaValue, long(0))
    assertEquals(FieldConstraints.ge(1).schemaValue, long(1))
    assertEquals(FieldConstraints.lt(2.5).schemaValue, double(2.5))
    assertEquals(FieldConstraints.le(-10).schemaValue, long(-10))
    assertEquals(FieldConstraints.minLength(3).schemaValue, int(3))
    assertEquals(FieldConstraints.maxLength(10).schemaValue, int(10))
    assertEquals(FieldConstraints.multipleOf(0.25).schemaValue, double(0.25))
  }

  test("FieldSpec carries constraints and normalize preserves them") {
    val field = FieldSpec(
      name = "score",
      typeRef = TypeRef.int,
      constraints = Vector(FieldConstraints.gt(0), FieldConstraints.maxLength(10))
    )
    assertEquals(field.constraints.map(_.render), Vector("greater than: 0", "maximum length: 10"))
    val normalized = FieldSpec.normalize(field)
    assertEquals(normalized.constraints.map(_.render), Vector("greater than: 0", "maximum length: 10"))
  }

  test("a whole bound at or above 2^63 renders and serializes with all its digits") {
    assertEquals(FieldConstraints.lt(1e20).render, "less than: 100000000000000000000")
    assertEquals(
      Constraint.Lt(1e20).schemaValue,
      DynamicValue.Primitive(PrimitiveValue.BigInt(BigInt("100000000000000000000")))
    )
    assertEquals(FieldConstraints.ge(-1e20).render, "greater than or equal to: -100000000000000000000")
  }
