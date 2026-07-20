package dspy4s.programs

import dspy4s.core.contracts.{:=, DynamicValues}
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite

class ProgramCallSuite extends FunSuite:

  private val controls = ProgramCall(
    input = 21,
    config = DynamicValues.record("temperature" := 0.4),
    traceEnabled = false,
    rolloutId = Some(7)
  )

  test("mapInput satisfies the identity law") {
    assertEquals(controls.mapInput(identity), controls)
  }

  test("mapInput satisfies the composition law") {
    val f: Int => String     = value => s"value=$value"
    val g: String => Boolean = _.nonEmpty

    assertEquals(controls.mapInput(f).mapInput(g), controls.mapInput(g compose f))
  }

  test("mapInput changes only the input carrier") {
    val mapped = controls.mapInput(_ * 2)

    assertEquals(mapped.input, 42)
    assertEquals(mapped.config, controls.config)
    assertEquals(mapped.traceEnabled, controls.traceEnabled)
    assertEquals(mapped.rolloutId, controls.rolloutId)
  }
