package dspy4s.programs

import dspy4s.core.contracts.{ContextWindowExceededError, DspyError, RuntimeError}
import dspy4s.programs.runtime.TrajectoryTruncation.truncateOnOverflow

/** Laws for the shared overflow loop. The migrated ReAct/CodeAct truncation paths are covered by their own
  * suites; this pins drop-oldest order, exhaustion, the empty-view guard, and the non-overflow short-circuit. */
class TruncateOnOverflowSuite extends munit.FunSuite:

  private def cwe: DspyError = ContextWindowExceededError()

  test("no overflow: runs once, returns the result with the original view") {
    var calls = 0
    val (result, used) = truncateOnOverflow(Vector(1, 2, 3), maxAttempts = 3)(_.mkString(",")) { _ =>
      calls += 1
      Right("ok")
    }
    assertEquals(result, Right("ok"): Either[DspyError, String])
    assertEquals(used, Vector(1, 2, 3))
    assertEquals(calls, 1)
  }

  test("overflow then success: drops the oldest entry and reruns on the truncated view") {
    val (result, used) = truncateOnOverflow(Vector(1, 2, 3), maxAttempts = 3)(_.mkString(",")) { s =>
      if s == "1,2,3" then Left(cwe) else Right(s)
    }
    assertEquals(result, Right("2,3"): Either[DspyError, String])
    assertEquals(used, Vector(2, 3))
  }

  test("persistent overflow: exhausts maxAttempts, returns the last Left with the truncated view") {
    var calls = 0
    val (result, used) = truncateOnOverflow(Vector(1, 2, 3), maxAttempts = 2)(_.mkString(",")) { _ =>
      calls += 1
      Left(cwe)
    }
    assert(result.isLeft)
    assertEquals(calls, 2)          // initial attempt + one retry
    assertEquals(used, Vector(2, 3)) // dropped the oldest exactly once
  }

  test("empty view overflow: stops immediately, no infinite loop") {
    var calls = 0
    val (result, used) = truncateOnOverflow(Vector.empty[Int], maxAttempts = 5)(_.mkString(",")) { _ =>
      calls += 1
      Left(cwe)
    }
    assert(result.isLeft)
    assertEquals(calls, 1)
    assertEquals(used, Vector.empty[Int])
  }

  test("a non-overflow error is returned immediately, without retrying") {
    var calls = 0
    val (result, _) = truncateOnOverflow(Vector(1, 2, 3), maxAttempts = 3)(_.mkString(",")) { _ =>
      calls += 1
      Left(RuntimeError("c", "boom"))
    }
    assert(result.isLeft)
    assertEquals(calls, 1)
  }
