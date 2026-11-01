package dspy4s.core

import dspy4s.core.contracts.{DynamicValues, LenientValues, TypeRef}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** One meaning for a number text and a bool text on each path. Each text that upstream `parse_value` reads as an `int`,
  * a `float`, or a `bool` (its `json_repair` and pydantic rules, checked by a run), the port reads with the same value.
  * The port also reads a currency symbol and thousands commas. A text that only Java reads (`3d`, `0x1p3`) is no
  * number. Each test failed before the change.
  */
class LenientCoercionSuite extends FunSuite:

  private def text(value: String): DynamicValue = DynamicValues.string(value)

  private def int(n   : Int): DynamicValue     = DynamicValue.Primitive(PrimitiveValue.Int(n))
  private def long(n  : Long): DynamicValue    = DynamicValue.Primitive(PrimitiveValue.Long(n))
  private def big(n   : BigInt): DynamicValue  = DynamicValue.Primitive(PrimitiveValue.BigInt(n))
  private def double(n: Double): DynamicValue  = DynamicValue.Primitive(PrimitiveValue.Double(n))
  private def bool(b  : Boolean): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Boolean(b))

  test("an int text") {
    val cases = Vector(
      "42"                   -> int(42),
      "+5"                   -> int(5),
      " 7 "                  -> int(7),
      "-3"                   -> int(-3),
      "$1,200"               -> int(1200),
      "1,200"                -> int(1200),
      "1_000"                -> int(1000),
      "3.0"                  -> int(3),
      "5."                   -> int(5),
      "1e3"                  -> int(1000),
      "1E3"                  -> int(1000),
      "1.5e2"                -> int(150),
      "1.0e0"                -> int(1),
      "3000000000"           -> long(3000000000L),
      "12345678901234567890" -> big(BigInt("12345678901234567890"))
    )
    cases.foreach((input, expected) =>
      assertEquals(LenientValues.valueOf(text(input), TypeRef.int), Some(expected), input)
    )
  }

  test("a text that is no int") {
    Vector("3.5", ".5", "+3.5", "3d", "3f", "3D", "0x1p3", "NaN", "nan", "inf", "Infinity", "1e400", "n/a", "", "abc")
      .foreach(input => assertEquals(LenientValues.valueOf(text(input), TypeRef.int), None, input))
  }

  test("a float text") {
    val cases = Vector(
      "3.5"       -> 3.5,
      ".5"        -> 0.5,
      "5."        -> 5.0,
      "+3.5"      -> 3.5,
      " 7 "       -> 7.0,
      "-3"        -> -3.0,
      "1e3"       -> 1000.0,
      "1_000"     -> 1000.0,
      "$2,399.50" -> 2399.5,
      "inf"       -> Double.PositiveInfinity,
      "-inf"      -> Double.NegativeInfinity,
      "Infinity"  -> Double.PositiveInfinity,
      "-Infinity" -> Double.NegativeInfinity,
      "1e400"     -> Double.PositiveInfinity
    )
    cases.foreach((input, expected) =>
      assertEquals(LenientValues.valueOf(text(input), TypeRef.double), Some(double(expected)), input)
    )
    Vector("nan", "NaN").foreach { input =>
      LenientValues.valueOf(text(input), TypeRef.double) match
        case Some(DynamicValue.Primitive(PrimitiveValue.Double(number))) => assert(number.isNaN, input)
        case other                                                       => fail(s"$input gave $other")
    }
  }

  test("a text that is no float") {
    Vector("3d", "3f", "3D", "0x1p3", "n/a", "", "abc")
      .foreach(input => assertEquals(LenientValues.valueOf(text(input), TypeRef.double), None, input))
  }

  test("a bool text and a bool number") {
    Vector("true", "True", "TRUE", " true ", "yes", "Yes", "on", "1", "t", "y")
      .foreach(input => assertEquals(LenientValues.valueOf(text(input), TypeRef.bool), Some(bool(true)), input))
    Vector("false", "False", "no", "off", "0", "f", "n")
      .foreach(input => assertEquals(LenientValues.valueOf(text(input), TypeRef.bool), Some(bool(false)), input))
    Vector("maybe", "n/a", "", "2").foreach(input =>
      assertEquals(LenientValues.valueOf(text(input), TypeRef.bool), None, input)
    )
    assertEquals(LenientValues.valueOf(int(1), TypeRef.bool), Some(bool(true)))
    assertEquals(LenientValues.valueOf(int(0), TypeRef.bool), Some(bool(false)))
    assertEquals(LenientValues.valueOf(int(2), TypeRef.bool), None)
  }

  test("a float text has the value of a Double, as a Python float has") {
    Vector("3.0000000000000001" -> int(3), "1e-400" -> int(0), "1e1_0" -> long(10000000000L))
      .foreach((input, expected) =>
        assertEquals(LenientValues.valueOf(text(input), TypeRef.int), Some(expected), input)
      )
    assertEquals(
      LenientValues.toBigDecimal(text("0.1234567890123456789012345")),
      Some(BigDecimal("0.1234567890123456789012345"))
    )
    assertEquals(LenientValues.toBigDecimal(text("nan")), None)
  }

  test("a float word in any case, with a sign and with no currency symbol") {
    Vector("INF", "iNf", "+inf", "INFINITY", " inf ").foreach { input =>
      assertEquals(LenientValues.toDouble(text(input)), Some(Double.PositiveInfinity), input)
    }
    assertEquals(LenientValues.toDouble(text("-INF")), Some(Double.NegativeInfinity))
    Vector("nAn", "+nan", "-NaN").foreach(input => assert(LenientValues.toDouble(text(input)).exists(_.isNaN), input))
    Vector("$inf", "$nan", "1inf").foreach(input => assertEquals(LenientValues.toDouble(text(input)), None, input))
  }

  test("a sign and a separator at a wrong position give no number") {
    Vector("+-5", "++5", "--5", "1__000", "_1", "1_", "1_.5", "1._5", "1_e3", "1_,000", "5e", "e5", ".", "1.5.5")
      .foreach(input => assertEquals(LenientValues.toDouble(text(input)), None, input))
  }

  test("a number text with the value 1 or 0 is a bool, as the number is") {
    Vector("1.0", "1e0").foreach(input => assertEquals(LenientValues.toBoolean(text(input)), Some(true), input))
    Vector("0.0", "-0").foreach(input => assertEquals(LenientValues.toBoolean(text(input)), Some(false), input))
    Vector("2.0", "0.5", "nan", "inf").foreach(input => assertEquals(LenientValues.toBoolean(text(input)), None, input))
    assertEquals(LenientValues.toBoolean(double(1.0)), Some(true))
    assertEquals(LenientValues.toBoolean(double(0.5)), None)
  }

  test("a number value for an int and a float type") {
    assertEquals(LenientValues.valueOf(double(3.0), TypeRef.int), Some(int(3)))
    assertEquals(LenientValues.valueOf(double(3.5), TypeRef.int), None)
    assertEquals(LenientValues.valueOf(long(3000000000L), TypeRef.int), Some(long(3000000000L)))
    assertEquals(LenientValues.valueOf(int(3), TypeRef.double), Some(double(3.0)))
  }

  test("a type with no rule gives no value") {
    assertEquals(LenientValues.valueOf(text("x"), TypeRef.string), None)
    assertEquals(LenientValues.valueOf(text("[1]"), TypeRef.list), None)
  }

  test("the accessors for a target type have the same rule") {
    assertEquals(LenientValues.toInt(text("3.0")), Some(3))
    assertEquals(LenientValues.toLong(text("1e3")), Some(1000L))
    assertEquals(LenientValues.toBigInt(text("1_000")), Some(BigInt(1000)))
    assertEquals(LenientValues.toInt(text("3.5")), None)
    assertEquals(LenientValues.toInt(text("3d")), None)
    assertEquals(LenientValues.toInt(text("3000000000")), None)
    assertEquals(LenientValues.toDouble(text("3d")), None)
    assertEquals(LenientValues.toFloat(text("3f")), None)
    assertEquals(LenientValues.toBoolean(text("yes")), Some(true))
    assertEquals(LenientValues.toBoolean(int(0)), Some(false))
  }

  test("a comma counts only when it separates the thousands") {
    assertEquals(LenientValues.valueOf(text("1,200,300"), TypeRef.int), Some(int(1200300)))
    assertEquals(LenientValues.valueOf(text("1,234.5"), TypeRef.double), Some(double(1234.5)))
    assertEquals(LenientValues.valueOf(text("-1,200"), TypeRef.int), Some(int(-1200)))
    // A decimal comma must not become a larger number: `3,5` is no 35.
    Vector("3,5", "1,2", "1,20", "1,2000", "1234,567", "1,200,30", ",200", "1,").foreach { input =>
      assertEquals(LenientValues.valueOf(text(input), TypeRef.int), None, input)
      assertEquals(LenientValues.valueOf(text(input), TypeRef.double), None, input)
    }
    assertEquals(LenientValues.toDouble(text("3,5")), None)
  }
