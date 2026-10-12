package dspy4s.gepa

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

/** The values of [[ReflectiveRecordJsonSuite]]. Top-level so the derivation of each `Schema` sees the Mirror. */
enum Mood derives Schema:
  case Calm, Tense

sealed trait Shape derives Schema
object Shape:
  final case class Circle(radius: Int)          extends Shape
  final case class Box(width: Int, height: Int) extends Shape

final case class Row(hint: Option[String], note: Option[String], mood: Mood, shape: Shape) derives Schema

/** The forms of [[ReflectiveRecord.toJson]] for the values that `InstructionProposerSuite` does not cover: the numbers,
  * the escapes, and the values that Python's `json.dumps` does not write. The expected text of each number is the
  * output of `json.dumps` of Python 3.12.
  */
class ReflectiveRecordJsonSuite extends FunSuite:

  /** The JSON text of `value` alone: the text of the generated outputs of one record. */
  private def json(value: DynamicValue): String =
    ReflectiveRecord
      .toJson(Vector(ReflectiveRecord(DynamicValue.Record.empty, value, "")))
      .stripPrefix("""[{"Inputs": {}, "Generated Outputs": """)
      .stripSuffix(""", "Feedback": ""}]""")

  private def double(value: Double): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Double(value))

  test("a Double has the text of the Python repr") {
    val expected = Vector(
      0.0                    -> "0.0",
      -0.0                   -> "-0.0",
      0.5                    -> "0.5",
      1.0                    -> "1.0",
      100.0                  -> "100.0",
      123.456                -> "123.456",
      3.14                   -> "3.14",
      2.0 / 3                -> "0.6666666666666666",
      1e-3                   -> "0.001",
      1e-4                   -> "0.0001",
      0.00012345             -> "0.00012345",
      1e-5                   -> "1e-05",
      1.5e-7                 -> "1.5e-07",
      -2.5e-10               -> "-2.5e-10",
      1e7                    -> "10000000.0",
      12345678.0             -> "12345678.0",
      -1234.5e10             -> "-12345000000000.0",
      999999999999999.9      -> "999999999999999.9",
      1e15                   -> "1000000000000000.0",
      9999999999999998.0     -> "9999999999999998.0",
      1e16                   -> "1e+16",
      1.2345e16              -> "1.2345e+16",
      123456789012345680.0   -> "1.2345678901234568e+17",
      1e22                   -> "1e+22",
      1.7976931348623157e308 -> "1.7976931348623157e+308"
    )
    expected.foreach((value, text) => assertEquals(json(double(value)), text))
  }

  test("a Double that is not finite has the text of Python") {
    assertEquals(json(double(Double.NaN)), "NaN")
    assertEquals(json(double(Double.PositiveInfinity)), "Infinity")
    assertEquals(json(double(Double.NegativeInfinity)), "-Infinity")
  }

  test("a Float has its shortest digits, and each integer type has its digits") {
    assertEquals(json(DynamicValue.Primitive(PrimitiveValue.Float(0.1f))), "0.1")
    assertEquals(json(DynamicValue.Primitive(PrimitiveValue.Float(1.0e-5f))), "1e-05")
    assertEquals(json(DynamicValue.Primitive(PrimitiveValue.Long(-9007199254740993L))), "-9007199254740993")
    assertEquals(json(DynamicValue.Primitive(PrimitiveValue.Short(7))), "7")
    assertEquals(
      json(DynamicValue.Primitive(PrimitiveValue.BigInt(BigInt("123456789012345678901234567890")))),
      "123456789012345678901234567890"
    )
  }

  test("each control character gets the escape of Python, and each other character stays") {
    val controls = Vector(0x00, 0x1f, 0x08, 0x0c, 0x0d).map(_.toChar).mkString
    assertEquals(json(DynamicValues.fromAny(controls)), "\"" + """\u0000\u001f\b\f\r""" + "\"")
    // DEL and the line separator U+2028 get no escape in Python.
    val others = Vector(0x7f, 0x2028).map(_.toChar).mkString
    assertEquals(json(DynamicValues.fromAny(others)), "\"" + others + "\"")
  }

  test("the key of a record gets the same escapes as a string") {
    assertEquals(json(DynamicValues.record("a \"b\"\n" := 1)), """{"a \"b\"\n": 1}""")
  }

  test("the case of an enum is its name, and an Option is its value or null") {
    val row = Row(hint = Some("look"), note = None, mood = Mood.Tense, shape = Shape.Circle(2))
    assertEquals(
      json(summon[Schema[Row]].toDynamicValue(row)),
      """{"hint": "look", "note": null, "mood": "Tense", "shape": {"Circle": {"radius": 2}}}"""
    )
  }

  test("a variant with a value is an object with the name of the case as its key") {
    assertEquals(
      json(summon[Schema[Shape]].toDynamicValue(Shape.Box(2, 3))),
      """{"Box": {"width": 2, "height": 3}}"""
    )
    assertEquals(json(DynamicValue.Variant("Count", DynamicValues.fromAny(3))), """{"Count": 3}""")
  }

  test("a map is an object, and a key that is not a string is the text of that key") {
    val byName = DynamicValue.Map(Chunk(DynamicValues.fromAny("a") -> DynamicValues.fromAny(1)))
    assertEquals(json(byName), """{"a": 1}""")
    val byNumber = DynamicValue.Map(
      Chunk(
        DynamicValues.fromAny(1)    -> DynamicValues.fromAny("one"),
        DynamicValues.fromAny(true) -> DynamicValues.fromAny("yes"),
        double(2.5)                 -> DynamicValue.Null
      )
    )
    assertEquals(json(byNumber), """{"1": "one", "true": "yes", "2.5": null}""")
    assertEquals(json(DynamicValue.Map(Chunk.empty)), "{}")
  }

  test("a primitive that JSON does not have is the text that an adapter shows, and the unit value is null") {
    assertEquals(json(DynamicValue.Primitive(PrimitiveValue.Char('x'))), "\"x\"")
    val uuid =
      DynamicValue.Primitive(PrimitiveValue.UUID(java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000")))
    assertEquals(json(uuid), "\"" + DynamicValues.renderText(uuid) + "\"")
    assertEquals(json(DynamicValue.Primitive(PrimitiveValue.Unit)), "null")
  }

  test("an empty record and an empty sequence have the Python form") {
    assertEquals(json(DynamicValue.Record.empty), "{}")
    assertEquals(json(DynamicValue.Sequence(Chunk.empty)), "[]")
  }
