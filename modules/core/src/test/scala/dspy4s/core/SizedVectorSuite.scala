package dspy4s.core

import dspy4s.core.collections.SizedVector
import dspy4s.core.collections.SizedVector.*
import munit.FunSuite

final class SizedVectorSuite extends FunSuite:

  test("fromVector establishes the requested singleton length") {
    val result = SizedVector.fromVector[String, 2](Vector("a", "b"))

    assertEquals(result.map(_.unsized), Right(Vector("a", "b")))
  }

  test("fromVector rejects a vector with a different runtime length") {
    val result = SizedVector.fromVector[String, 2](Vector("a"))

    assertEquals(result, Left(SizedVector.SizeMismatch(expected = 2, actual = 1)))
  }

  test("mapSized preserves length and concat adds lengths") {
    val left: SizedVector[Int, 1]   = SizedVector.one(1)
    val right: SizedVector[Int, 2]  = SizedVector.fromVector[Int, 2](Vector(2, 3)).toOption.get
    val mapped: SizedVector[Int, 1] = left.mapSized(_ + 10)
    val result: SizedVector[Int, 3] = mapped.concatSized(right)

    assertEquals(result.unsized, Vector(11, 2, 3))
  }

  test("empty carries length zero") {
    val result: SizedVector[String, 0] = SizedVector.empty

    assertEquals(result.unsized, Vector.empty)
  }

  test("different singleton lengths are not interchangeable") {
    val errors = compileErrors("""
      import dspy4s.core.collections.SizedVector

      val one: SizedVector[Int, 1] = SizedVector.one(1)
      val two: SizedVector[Int, 2] = one
    """)

    assert(errors.nonEmpty, "expected a statically known length mismatch to fail compilation")
  }
