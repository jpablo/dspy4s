package dspy4s.core

import dspy4s.core.contracts.{FieldRole, FieldSpec, SignatureLayout}
import dspy4s.core.contracts.SignatureOps.*

/** Laws for the value-level signature algebra (`prependOutput` / `appendInput` / `replaceOutputs`). The
  * composite suites (ChainOfThought / ReAct / CodeAct / MultiChainComparison) cover the migrated call
  * sites; this pins the primitive itself. */
class SignatureOpsSuite extends munit.FunSuite:

  private def layout(fields: FieldSpec*): SignatureLayout =
    SignatureLayout.create("test", fields.toVector).fold(e => fail(e.message), identity)

  private def in(name: String): FieldSpec  = FieldSpec(name, FieldRole.Input)
  private def out(name: String): FieldSpec = FieldSpec(name, FieldRole.Output)

  test("prependOutput inserts at the output-cohort head; inputs unchanged") {
    val r = layout(in("question"), out("answer")).prependOutput(out("reasoning"))
    assertEquals(r.inputFields.map(_.name), Vector("question"))
    assertEquals(r.outputFields.map(_.name), Vector("reasoning", "answer"))
  }

  test("prependOutput is idempotent on field name") {
    val base = layout(in("question"), out("answer"))
    val once = base.prependOutput(out("reasoning"))
    assertEquals(once.prependOutput(out("reasoning")), once)
    // an output of the same name already present leaves the layout untouched
    assertEquals(base.prependOutput(out("answer")), base)
  }

  test("appendInput appends to the input cohort; outputs unchanged") {
    val r = layout(in("question"), out("answer")).appendInput(in("trajectory"))
    assertEquals(r.inputFields.map(_.name), Vector("question", "trajectory"))
    assertEquals(r.outputFields.map(_.name), Vector("answer"))
  }

  test("appendInput is idempotent on field name") {
    val base = layout(in("question"), out("answer"))
    val once = base.appendInput(in("trajectory"))
    assertEquals(once.appendInput(in("trajectory")), once)
    assertEquals(base.appendInput(in("question")), base)
  }

  test("replaceOutputs keeps the inputs and replaces every output") {
    val r = layout(in("question"), out("answer"), out("confidence"))
      .replaceOutputs(Vector(out("code"), out("done")))
    assertEquals(r.inputFields.map(_.name), Vector("question"))
    assertEquals(r.outputFields.map(_.name), Vector("code", "done"))
  }

  test("prependOutput normalizes to inputs ++ (field +: outputs), as the prior insert(0) did") {
    val r = layout(in("q1"), in("q2"), out("a"), out("b")).prependOutput(out("reasoning"))
    assertEquals(r.fields.map(_.name), Vector("q1", "q2", "reasoning", "a", "b"))
  }
