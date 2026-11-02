package dspy4s.adapters.internal

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.TypeRef
import munit.FunSuite

/** Upstream `format_field_value` serializes a value with pydantic in JSON mode (`serialize_for_json` in
  * `dspy/adapters/utils.py`), and that mode writes NaN and an infinite number as `None`. Thus the JSON text has `null`.
  */
class PromptTextNonFiniteSuite extends FunSuite:

  private val field = FieldSpec("values", TypeRef("list[Any]"))

  test("a number that is not finite in a list value is null") {
    val value = DynamicValues.sequence(
      Vector(1.5, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).map(DynamicValues.fromAny)
    )
    assertEquals(PromptText.fieldValue(field, value), "[1.5, null, null, null]")
  }

  test("a number that is not finite in a nested record is null") {
    val value = DynamicValues.sequence(
      Vector(DynamicValues.record("score" -> DynamicValues.fromAny(Double.NaN), "name" -> DynamicValues.fromAny("a")))
    )
    assertEquals(PromptText.fieldValue(field, value), """[{"score": null, "name": "a"}]""")
  }

  test("a bare number that is not finite keeps its Python text") {
    // The venv Python: `str(TypeAdapter(float).dump_python(float('nan'), mode='json'))` is `nan`.
    val number = FieldSpec("value", TypeRef.double)
    assertEquals(PromptText.fieldValue(number, DynamicValues.fromAny(Double.NaN)), "nan")
    assertEquals(PromptText.fieldValue(number, DynamicValues.fromAny(Double.PositiveInfinity)), "inf")
    assertEquals(PromptText.fieldValue(number, DynamicValues.fromAny(Double.NegativeInfinity)), "-inf")
  }
