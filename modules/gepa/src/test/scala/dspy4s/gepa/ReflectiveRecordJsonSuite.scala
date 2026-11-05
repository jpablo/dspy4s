package dspy4s.gepa

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** The text of the records in a prompt of the default proposer. An adapter writes it for the value of the input field
  * `examples_with_feedback` ([[ReflectionFixtures.examplesText]]). `PythonTextSuite` of core has the forms of each
  * value, and `InstructionProposerSuite` has the forms of a record.
  */
class ReflectiveRecordJsonSuite extends FunSuite:

  /** The JSON text of `value` alone: the text of the generated outputs of one record. */
  private def json(value: DynamicValue): String =
    ReflectionFixtures
      .examplesText(Vector(ReflectiveRecord(DynamicValue.Record.empty, value, "")))
      .stripPrefix("""[{"Inputs": {}, "Generated Outputs": """)
      .stripSuffix(""", "Feedback": ""}]""")

  private def double(value: Double): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Double(value))

  test("a record has the keys of gepa in order, and its values have the text of Python json.dumps") {
    val outputs = DynamicValues.record(
      "ratio" := 1e-5,
      "mood"  -> DynamicValue.Variant("Tense", DynamicValue.Record.empty),
      "hint"  -> DynamicValue.Variant("None", DynamicValue.Record.empty),
      "table" -> DynamicValue.Map(Chunk(DynamicValues.fromAny(1) -> DynamicValues.string("é\n")))
    )
    assertEquals(
      ReflectionFixtures.examplesText(Vector(ReflectiveRecord(DynamicValues.record("q" := "a"), outputs, "ok"))),
      """[{"Inputs": {"q": "a"}, """ +
        """"Generated Outputs": {"ratio": 1e-05, "mood": "Tense", "hint": null, "table": {"1": "é\n"}}, """ +
        """"Feedback": "ok"}]"""
    )
  }

  test("a Double that is not finite has the text of Python") {
    assertEquals(json(double(Double.NaN)), "null")
    assertEquals(json(double(Double.PositiveInfinity)), "null")
    assertEquals(json(double(Double.NegativeInfinity)), "null")
  }
