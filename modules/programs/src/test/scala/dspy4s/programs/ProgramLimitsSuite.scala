package dspy4s.programs

import dspy4s.programs.optimization.{OptimizableId, OptimizableOrdinal}
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
    assertEquals(FailureCount.either(0).map(_.toInt), Right(0))
    assert(FailureCount.either(-1).isLeft)
  }

  test("negative failure-count literals are rejected") {
    assert(compileErrors("dspy4s.programs.FailureCount(-1)").nonEmpty)
  }

  test("optimizable ordinals reject negative values before constructing an id") {
    assert(compileErrors("dspy4s.programs.optimization.OptimizableId(-1)").nonEmpty)
    assertEquals(OptimizableOrdinal.either(2).map(OptimizableId.fromOrdinal), Right(OptimizableId(2)))
    assert(OptimizableOrdinal.either(-1).isLeft)
    assert(OptimizableId.parse("optimizable--1").isLeft)
  }
