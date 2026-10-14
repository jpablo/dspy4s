package dspy4s.core

import dspy4s.core.contracts.TypeRef
import dspy4s.core.signatures.SignatureDsl
import munit.FunSuite

/** The type syntax of a string signature: commas inside brackets, `Union[T, None]`, and `dict[...]` (upstream reads the
  * annotations as Python types).
  */
class SignatureTypeSyntaxSuite extends FunSuite:

  private def outputs(dsl: String): Vector[(String, TypeRef, Boolean)] =
    SignatureDsl.parse(dsl).fold(
      error => fail(s"parse failed: $error"),
      _.outputFields.map(f => (f.name, f.typeRef, f.optional))
    )

  test("a comma inside brackets does not split the fields") {
    assertEquals(
      outputs("question -> scores: dict[str, int], note: Optional[dict[str, int]], tags: list[str]"),
      Vector(("scores", TypeRef.json, false), ("note", TypeRef.json, true), ("tags", TypeRef("list[str]"), false))
    )
  }

  test("Union with None is optional in each order") {
    assertEquals(
      outputs("question -> count: Union[int, None], label: Union[None, str]"),
      Vector(("count", TypeRef.int, true), ("label", TypeRef.string, true))
    )
  }

  test("a dict token with type arguments is the json type") {
    assertEquals(TypeRef.fromToken("dict[str, int]"), TypeRef.json)
    assertEquals(TypeRef.fromToken("Dict[str, Any]"), TypeRef.json)
  }

  test("a signature with two inputs and simple types still parses as before") {
    assertEquals(
      SignatureDsl.parse("question, context -> answer: int").map(l =>
        (l.inputFields.map(_.name), l.outputFields.map(_.typeRef))
      ),
      Right((Vector("question", "context"), Vector(TypeRef.int)))
    )
  }
