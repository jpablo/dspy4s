package dspy4s.programs

import munit.FunSuite

class ProgramLimitsSuite extends FunSuite:

  test("agent limit literals must be strictly positive") {
    assert(compileErrors("dspy4s.programs.IterationLimit(0)").nonEmpty)
    assert(compileErrors("dspy4s.programs.LlmCallLimit(-1)").nonEmpty)
    assert(compileErrors("dspy4s.programs.OutputCharLimit(0)").nonEmpty)
  }

  test("agent limits validate values obtained at runtime") {
    assertEquals(IterationLimit.either(3).map(_.toInt), Right(3))
    assert(IterationLimit.either(0).isLeft)
    assertEquals(LlmCallLimit.either(4).map(_.toInt), Right(4))
    assert(LlmCallLimit.either(-1).isLeft)
    assertEquals(OutputCharLimit.either(100).map(_.toInt), Right(100))
    assert(OutputCharLimit.either(0).isLeft)
  }
