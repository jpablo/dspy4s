package dspy4s.core

import dspy4s.core.contracts.{FieldSpec, SignatureLayout}
import dspy4s.core.contracts.SignatureOps.*

/** Laws for the value-level signature algebra (`prependOutput` / `appendInput` / `replaceOutputs`). The composite
  * suites (ChainOfThought / ReAct / CodeAct / MultiChainComparison) cover the migrated call sites; this pins the
  * primitive itself.
  */
class SignatureOpsSuite extends munit.FunSuite:

  private def layout(inputs: Vector[FieldSpec], outputs: Vector[FieldSpec]): SignatureLayout =
    SignatureLayout.create("test", inputs, outputs).fold(e => fail(e.message), identity)

  private def field(name: String): FieldSpec = FieldSpec(name)

  test("prependOutput inserts at the output-cohort head; inputs unchanged") {
    val r = layout(Vector(field("question")), Vector(field("answer"))).prependOutput(field("reasoning"))
    assertEquals(r.inputFields.map(_.name), Vector("question"))
    assertEquals(r.outputFields.map(_.name), Vector("reasoning", "answer"))
  }

  test("prependOutput is idempotent on field name") {
    val base = layout(Vector(field("question")), Vector(field("answer")))
    val once = base.prependOutput(field("reasoning"))
    assertEquals(once.prependOutput(field("reasoning")), once)
    // an output of the same name already present leaves the layout untouched
    assertEquals(base.prependOutput(field("answer")), base)
  }

  test("appendInput appends to the input cohort; outputs unchanged") {
    val r = layout(Vector(field("question")), Vector(field("answer"))).appendInput(field("trajectory"))
    assertEquals(r.inputFields.map(_.name), Vector("question", "trajectory"))
    assertEquals(r.outputFields.map(_.name), Vector("answer"))
  }

  test("appendInput is idempotent on field name") {
    val base = layout(Vector(field("question")), Vector(field("answer")))
    val once = base.appendInput(field("trajectory"))
    assertEquals(once.appendInput(field("trajectory")), once)
    assertEquals(base.appendInput(field("question")), base)
  }

  test("replaceOutputs keeps the inputs and replaces every output") {
    val r = layout(Vector(field("question")), Vector(field("answer"), field("confidence")))
      .replaceOutputs(Vector(field("code"), field("done")))
    assertEquals(r.inputFields.map(_.name), Vector("question"))
    assertEquals(r.outputFields.map(_.name), Vector("code", "done"))
  }

  test("prependOutput normalizes to inputs ++ (field +: outputs), as the prior insert(0) did") {
    val r = layout(Vector(field("q1"), field("q2")), Vector(field("a"), field("b")))
      .prependOutput(field("reasoning"))
    assertEquals(r.fields.map(_.name), Vector("q1", "q2", "reasoning", "a", "b"))
  }

  // Uniqueness moved off the (now-private) constructor's `require` to the `create` boundary; confirm the
  // validation is preserved (the structural guarantee is that mutators dedup instead, exercised by the
  // idempotence laws in SignatureOpsLawSuite).
  test("create rejects duplicate field names at the boundary") {
    assert(SignatureLayout.create("test", Vector(field("q")), Vector(field("q"))).isLeft)
  }
