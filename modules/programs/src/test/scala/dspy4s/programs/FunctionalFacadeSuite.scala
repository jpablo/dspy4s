package dspy4s.programs

import munit.FunSuite
import zio.{Runtime, Unsafe}

final class FunctionalFacadeSuite extends FunSuite:

  test("the primary programs package exposes the functional syntax and runner") {
    val program = Program.lift[Int, Int](_ + 1) >>> Program.lift[Int, String](_.toString)
    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(ProgramRunner.run(program, 1)).getOrThrowFiberFailure()
    }

    assertEquals(result.output, "2")
    assertEquals(ProgramGraph.from(program).nodes.map(_.kind), Vector("and_then", "lift", "lift"))
  }

  test("the old generic runner has an explicit legacy name") {
    val runner = summon[LegacyProgramRunner[DynamicPredict]]
    assert(runner ne null)
  }
