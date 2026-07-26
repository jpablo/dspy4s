package dspy4s.core

import dspy4s.core.contracts.{ErrorLimit, HistoryLimit, ThreadCount}
import munit.FunSuite

class ExecutionLimitsSuite extends FunSuite:

  test("positive execution limit literals are checked at compile time") {
    assert(compileErrors("dspy4s.core.contracts.ThreadCount(0)").nonEmpty)
    assert(compileErrors("dspy4s.core.contracts.ErrorLimit(-1)").nonEmpty)
  }

  test("execution limits validate runtime values") {
    assertEquals(ThreadCount.either(4).map(_.toInt), Right(4))
    assert(ThreadCount.either(0).isLeft)
    assertEquals(ErrorLimit.either(2).map(_.toInt), Right(2))
    assert(ErrorLimit.either(0).isLeft)
    assertEquals(HistoryLimit.either(0).map(_.toInt), Right(0))
    assert(HistoryLimit.either(-1).isLeft)
  }
