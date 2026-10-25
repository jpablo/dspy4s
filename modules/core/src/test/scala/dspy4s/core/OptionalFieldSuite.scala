package dspy4s.core

import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import munit.FunSuite

/** The optional flag of a field (decision D5, PORT_GAPS G-29; upstream `dspy/adapters/utils.py`
  * `annotation_allows_none`).
  */
class OptionalFieldSuite extends FunSuite:

  private def outputs(dsl: String): Vector[FieldSpec] =
    SignatureLayout.parse(dsl).fold(error => fail(error.message), _.outputFields)

  test("a field is not optional by default") {
    assertEquals(FieldSpec("answer").optional, false)
    assertEquals(outputs("question -> answer").map(_.optional), Vector(false))
  }

  test("the string syntax Optional[T] and T | None gives an optional field of type T") {
    val fields = outputs("question -> note: Optional[str], count: Optional[int], score: float | None, answer: str")
    assertEquals(fields.map(_.name), Vector("note", "count", "score", "answer"))
    assertEquals(fields.map(_.optional), Vector(true, true, true, false))
    assertEquals(fields.map(_.typeRef), Vector(TypeRef.string, TypeRef.int, TypeRef.double, TypeRef.string))
  }
