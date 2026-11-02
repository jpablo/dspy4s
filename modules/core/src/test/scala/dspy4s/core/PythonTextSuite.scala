package dspy4s.core

import dspy4s.core.contracts.{:=, DspyError, DynamicValues, PythonRepr, PythonText}
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

/** The values of the JSON tests of [[PythonTextSuite]]. Top-level so the derivation of each `Schema` sees the Mirror.
  */
enum Mood derives Schema:
  case Calm, Tense

sealed trait Shape derives Schema
object Shape:
  final case class Circle(radius: Int)          extends Shape
  final case class Box(width: Int, height: Int) extends Shape

final case class Row(hint: Option[String], note: Option[String], mood: Mood, shape: Shape) derives Schema

/** The Python text forms that the prompts use (decision D3). The expected texts come from CPython 3.12: `repr` of a
  * float, `json.dumps(..., ensure_ascii=False)`, `textwrap.dedent`, `str.splitlines`, and `str.strip`.
  */
class PythonTextSuite extends FunSuite:

  private def double(value: Double): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Double(value))

  private val floats: Vector[(Double, String)] = Vector(
    0.0                     -> "0.0",
    -0.0                    -> "-0.0",
    1.0                     -> "1.0",
    2.5                     -> "2.5",
    0.1                     -> "0.1",
    (0.1 + 0.2)             -> "0.30000000000000004",
    1e-05                   -> "1e-05",
    2.5e-05                 -> "2.5e-05",
    0.0001                  -> "0.0001",
    123.456                 -> "123.456",
    1e15                    -> "1000000000000000.0",
    1e16                    -> "1e+16",
    1.5e300                 -> "1.5e+300",
    5e-324                  -> "5e-324",
    1e-320                  -> "1e-320",
    1.7976931348623157e308  -> "1.7976931348623157e+308",
    2.2250738585072014e-308 -> "2.2250738585072014e-308",
    1e22                    -> "1e+22",
    1e23                    -> "1e+23",
    123456789012345680.0    -> "1.2345678901234568e+17",
    -3.75                   -> "-3.75",
    4.35                    -> "4.35",
    100.0                   -> "100.0",
    1e7                     -> "10000000.0",
    12345678.9              -> "12345678.9",
    Double.PositiveInfinity -> "inf",
    Double.NegativeInfinity -> "-inf"
  )

  test("a float prints as Python repr") {
    floats.foreach((number, expected) => assertEquals(PythonRepr.float(number), expected, number.toString))
  }

  test("json escapes the quote, the backslash, and the control characters only") {
    val text = DynamicValue.Sequence(Chunk.single(DynamicValues.fromAny("a\"b\\c\n\r\t\b\f\u0001\u007f é")))
    assertEquals(PythonText.json(text), "[\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u0001\u007f é\"]")
  }

  test("json has the Python separators, and the indent form of json.dumps") {
    val value = DynamicValues.record(
      "a" -> DynamicValue.Sequence(Chunk.empty),
      "b" -> DynamicValue.Record(Chunk.empty),
      "c" -> DynamicValue.Sequence(Chunk(
        DynamicValue.Primitive(PrimitiveValue.Int(1)),
        DynamicValues.record("d" := 2.0)
      ))
    )
    assertEquals(PythonText.json(value), """{"a": [], "b": {}, "c": [1, {"d": 2.0}]}""")
    assertEquals(
      PythonText.json(value, Some(2)),
      "{\n  \"a\": [],\n  \"b\": {},\n  \"c\": [\n    1,\n    {\n      \"d\": 2.0\n    }\n  ]\n}"
    )
  }

  test("a Double in a JSON text has the text of the Python repr") {
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
    expected.foreach((value, text) => assertEquals(PythonText.json(double(value)), text))
  }

  test("a Double that is not finite has the JSON text of Python") {
    assertEquals(PythonText.json(double(Double.NaN)), "NaN")
    assertEquals(PythonText.json(double(Double.PositiveInfinity)), "Infinity")
    assertEquals(PythonText.json(double(Double.NegativeInfinity)), "-Infinity")
  }

  test("a Float has its shortest digits, and each integer type has its digits") {
    def text(primitive: PrimitiveValue): String = PythonText.json(DynamicValue.Primitive(primitive))
    assertEquals(text(PrimitiveValue.Float(0.1f)), "0.1")
    assertEquals(text(PrimitiveValue.Float(1.0e-5f)), "1e-05")
    assertEquals(text(PrimitiveValue.Long(-9007199254740993L)), "-9007199254740993")
    assertEquals(text(PrimitiveValue.Short(7)), "7")
    assertEquals(
      text(PrimitiveValue.BigInt(BigInt("123456789012345678901234567890"))),
      "123456789012345678901234567890"
    )
  }

  test("each control character gets the escape of Python, and each other character stays") {
    val controls = Vector(0x00, 0x1f, 0x08, 0x0c, 0x0d).map(_.toChar).mkString
    assertEquals(PythonText.json(DynamicValues.string(controls)), "\"" + """\u0000\u001f\b\f\r""" + "\"")
    // DEL and the line separator U+2028 get no escape in Python.
    val others = Vector(0x7f, 0x2028).map(_.toChar).mkString
    assertEquals(PythonText.json(DynamicValues.string(others)), "\"" + others + "\"")
  }

  test("the key of a record gets the same escapes as a string") {
    assertEquals(PythonText.json(DynamicValues.record("a \"b\"\n" := 1)), """{"a \"b\"\n": 1}""")
  }

  test("the case of an enum is its name, and an Option is its value or null") {
    val row = Row(hint = Some("look"), note = None, mood = Mood.Tense, shape = Shape.Circle(2))
    assertEquals(
      PythonText.json(summon[Schema[Row]].toDynamicValue(row)),
      """{"hint": "look", "note": null, "mood": "Tense", "shape": {"Circle": {"radius": 2}}}"""
    )
  }

  test("a variant with a value is an object with the name of the case as its key") {
    assertEquals(
      PythonText.json(summon[Schema[Shape]].toDynamicValue(Shape.Box(2, 3))),
      """{"Box": {"width": 2, "height": 3}}"""
    )
    assertEquals(PythonText.json(DynamicValue.Variant("Count", DynamicValues.fromAny(3))), """{"Count": 3}""")
  }

  test("a map is an object, and a key that is not a string is the text of that key") {
    val byName = DynamicValue.Map(Chunk(DynamicValues.fromAny("a") -> DynamicValues.fromAny(1)))
    assertEquals(PythonText.json(byName), """{"a": 1}""")
    val byNumber = DynamicValue.Map(
      Chunk(
        DynamicValues.fromAny(1)    -> DynamicValues.fromAny("one"),
        DynamicValues.fromAny(true) -> DynamicValues.fromAny("yes"),
        double(2.5)                 -> DynamicValue.Null
      )
    )
    assertEquals(PythonText.json(byNumber), """{"1": "one", "true": "yes", "2.5": null}""")
    assertEquals(PythonText.json(DynamicValue.Map(Chunk.empty)), "{}")
  }

  test("a float key that is not finite has the JSON text of Python, as a float value has") {
    val byDouble = DynamicValue.Map(
      Chunk(
        double(Double.NaN)              -> DynamicValues.fromAny(1),
        double(Double.PositiveInfinity) -> DynamicValues.fromAny(2),
        double(Double.NegativeInfinity) -> DynamicValues.fromAny(3)
      )
    )
    assertEquals(PythonText.json(byDouble), """{"NaN": 1, "Infinity": 2, "-Infinity": 3}""")
    val byFloat = DynamicValue.Map(
      Chunk(
        DynamicValue.Primitive(PrimitiveValue.Float(Float.NaN)) -> DynamicValues.fromAny(1),
        DynamicValue.Primitive(PrimitiveValue.Float(0.5f))      -> DynamicValues.fromAny(2)
      )
    )
    assertEquals(PythonText.json(byFloat), """{"NaN": 1, "0.5": 2}""")
  }

  test("a primitive that JSON does not have is the text that an adapter shows, and the unit value is null") {
    assertEquals(PythonText.json(DynamicValue.Primitive(PrimitiveValue.Char('x'))), "\"x\"")
    val uuid =
      DynamicValue.Primitive(PrimitiveValue.UUID(java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000")))
    assertEquals(PythonText.json(uuid), "\"" + DynamicValues.renderText(uuid) + "\"")
    assertEquals(PythonText.json(DynamicValue.Primitive(PrimitiveValue.Unit)), "null")
  }

  test("str of a scalar is the Python text, and an Option value is its element or None") {
    assertEquals(PythonText.str(DynamicValue.Primitive(PrimitiveValue.Boolean(true))), "True")
    assertEquals(PythonText.str(DynamicValue.Null), "None")
    assertEquals(PythonText.str(DynamicValue.Variant("None", DynamicValue.Record(Chunk.empty))), "None")
    assertEquals(
      PythonText.str(DynamicValue.Variant("Some", DynamicValues.record("value" := 3.0))),
      "3.0"
    )
    assertEquals(PythonText.str(DynamicValue.Variant("red", DynamicValue.Record(Chunk.empty))), "red")
  }

  test("a JSON text keeps the float form of its numbers") {
    val parsed = PythonText.parseJson("""{"a": 1.0, "b": 2, "c": 1e-05, "d": [true, null, "é"]}""")
    assertEquals(parsed.map(PythonText.json(_)), Right("""{"a": 1.0, "b": 2, "c": 1e-05, "d": [true, null, "é"]}"""))
    assert(PythonText.parseJson("not json").isLeft)
  }

  test("a JSON integer keeps its exact value, also above 2^53 and above the range of a Long") {
    def number(text: String): Either[DspyError, DynamicValue] = PythonText.parseJson(text)
    assertEquals(number("9007199254740993"), Right(DynamicValue.Primitive(PrimitiveValue.Long(9007199254740993L))))
    assertEquals(number("3"), Right(DynamicValue.Primitive(PrimitiveValue.Int(3))))
    assertEquals(number("1.0"), Right(DynamicValue.Primitive(PrimitiveValue.Double(1.0))))
    assertEquals(
      number("123456789012345678901234567890"),
      Right(DynamicValue.Primitive(PrimitiveValue.BigInt(BigInt("123456789012345678901234567890"))))
    )
  }

  test("a JSON object with a key two times keeps the last value at the position of the first key") {
    assertEquals(
      PythonText.parseJson("""{"a": 1, "b": 2, "a": 3}""").map(PythonText.json(_)),
      Right("""{"a": 3, "b": 2}""")
    )
  }

  test("a JSON text that is nested very deep gives a value, not a StackOverflowError") {
    val depth  = 100000
    val parsed = PythonText.parseJson("[" * depth + "]" * depth)
    assert(parsed.isRight, parsed.left.toString)
  }

  test("a JSON string escapes a control character, and the ASCII form escapes each character after ~") {
    val text = DynamicValues.string("é\u0001 😀")
    assertEquals(PythonText.json(text), "\"é\\u0001 😀\"")
    assertEquals(PythonText.jsonAscii(text), "\"\\u00e9\\u0001\\u2028\\ud83d\\ude00\"")
  }

  test("split follows Python str.split with no separator") {
    assertEquals(
      PythonText.split(" a  b\t\nc\u0085d\u3000e\u00a0f\u200bg "),
      Vector("a", "b", "c", "d", "e", "f\u200bg")
    )
    assertEquals(PythonText.split(""), Vector.empty)
    assertEquals(PythonText.split("   "), Vector.empty)
    assertEquals(PythonText.split("one"), Vector("one"))
  }

  test("round follows Python: the exact value, a half to the even digit, and no change when not finite") {
    assertEquals(PythonText.round(100.0 * 2.0 / 3, 2), 66.67)
    assertEquals(PythonText.round(0.125, 2), 0.12)
    assertEquals(PythonText.round(2.675, 2), 2.67)
    assert(PythonText.round(Double.NaN, 2).isNaN)
    assertEquals(PythonText.round(Double.PositiveInfinity, 2), Double.PositiveInfinity)
    assertEquals(PythonText.round(Double.NegativeInfinity, 2), Double.NegativeInfinity)
  }

  test("sum follows Python 3.12: a compensated sum, for each order of the values") {
    // The expected values come from the venv Python: `sum([0.1] * 10)` and the others.
    assertEquals(PythonText.sum(Vector.fill(10)(0.1)), 1.0)
    assertEquals(PythonText.sum(Vector(0.3, 0.2, 0.1)), 0.6)
    assertEquals(PythonText.sum(Vector(0.1, 0.2, 0.3)), 0.6)
    assertEquals(PythonText.sum(Vector(1e100, 1.0, -1e100)), 1.0)
  }

  test("the sum of no value is zero") {
    assertEquals(PythonText.sum(Vector.empty), 0.0)
  }

  test("sum follows Python for a value that is not finite: no compensation goes into the result") {
    // The venv Python gives `sum([inf, 1.0]) == inf`, and NaN for `sum([inf, -inf])` and `sum([nan, 1.0])`.
    assertEquals(PythonText.sum(Vector(Double.PositiveInfinity, 1.0)), Double.PositiveInfinity)
    assert(PythonText.sum(Vector(Double.PositiveInfinity, Double.NegativeInfinity)).isNaN)
    assert(PythonText.sum(Vector(Double.NaN, 1.0)).isNaN)
  }

  test("dedent, splitLines, and strip follow Python") {
    assertEquals(PythonText.dedent("  a\n    b\n  \n  c"), "a\n  b\n\nc")
    assertEquals(PythonText.dedent("\ta\n  b"), "\ta\n  b")
    // Only `\n` ends a line, as in Python: the margin after `\r` stays.
    assertEquals(PythonText.dedent("  a\r  b\n  c"), "a\r  b\nc")
    assertEquals(PythonText.splitLines("a\r\nb\rc\u000bd\u001ce f\n"), Vector("a", "b", "c", "d", "e", "f"))
    assertEquals(PythonText.strip("   x　\u0085 "), "x")
  }
