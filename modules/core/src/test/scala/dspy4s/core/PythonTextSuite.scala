package dspy4s.core

import dspy4s.core.contracts.{:=, DspyError, DynamicValues, PythonRepr, PythonText}
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** The Python text forms that the prompts use (decision D3). The expected texts come from CPython 3.12: `repr` of a
  * float, `json.dumps(..., ensure_ascii=False)`, `textwrap.dedent`, `str.splitlines`, and `str.strip`.
  */
class PythonTextSuite extends FunSuite:

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

  test("dedent, splitLines, and strip follow Python") {
    assertEquals(PythonText.dedent("  a\n    b\n  \n  c"), "a\n  b\n\nc")
    assertEquals(PythonText.dedent("\ta\n  b"), "\ta\n  b")
    // Only `\n` ends a line, as in Python: the margin after `\r` stays.
    assertEquals(PythonText.dedent("  a\r  b\n  c"), "a\r  b\nc")
    assertEquals(PythonText.splitLines("a\r\nb\rc\u000bd\u001ce f\n"), Vector("a", "b", "c", "d", "e", "f"))
    assertEquals(PythonText.strip("   x　\u0085 "), "x")
  }
