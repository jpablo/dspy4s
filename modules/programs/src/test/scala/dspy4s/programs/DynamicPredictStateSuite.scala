package dspy4s.programs

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.:=
import dspy4s.core.signatures.SignatureDsl
import munit.FunSuite
import zio.blocks.schema.DynamicValue

class DynamicPredictStateSuite extends FunSuite:

  private val layout =
    SignatureDsl.parse("question -> answer").toOption.get.withInstructions("Be terse.")

  test("DynamicPredict dumpState and fromState roundtrip (layout + demos + config)") {
    val demos = Vector(
      Example("question" := "q1", "answer" := "a1").withInputs(Set("question")),
      Example("question" := "q2", "answer" := "a2").withInputs(Set("question")).withAugmented(true)
    )
    val config  = DynamicValues.record("temperature" := 0.7)
    val predict = DynamicPredict(layout = layout, demos = demos, config = config)

    val rebuilt = DynamicPredict.fromState(predict.dumpState)
    assert(rebuilt.isRight, s"expected Right, got $rebuilt")
    val got = rebuilt.toOption.get

    assert(got.layout.equalsByStructure(predict.layout))
    assertEquals(got.demos, demos)
    assertEquals(got.config, config)
  }

  test("DynamicPredict fromState defaults demos/config when absent") {
    val predict = DynamicPredict(layout = layout)
    val rebuilt = DynamicPredict.fromState(predict.dumpState)
    assert(rebuilt.isRight)
    val got = rebuilt.toOption.get
    assertEquals(got.demos, Vector.empty[Example])
    assertEquals(got.config, DynamicValue.Record.empty)
  }

  test("DynamicPredict fromState fails when 'signature' is missing") {
    val state   = DynamicValues.record("demos" := List.empty[String])
    val rebuilt = DynamicPredict.fromState(state)
    assert(rebuilt.isLeft)
    assert(rebuilt.left.toOption.get.isInstanceOf[ValidationError])
  }
