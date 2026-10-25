package dspy4s.core

import dspy4s.algebra.Endofunctor
import dspy4s.core.collections.SizedVector
import dspy4s.core.collections.SizedVector.*
import dspy4s.core.collections.SizedVector.given
import munit.FunSuite

final class SizedVectorSuite extends FunSuite:

  test("the functor map keeps the length and concatSized adds the lengths") {
    val left: SizedVector[Int, 1]   = SizedVector.assumeSize(Vector(1))
    val right: SizedVector[Int, 2]  = SizedVector.assumeSize(Vector(2, 3))
    val mapped: SizedVector[Int, 1] = summon[Endofunctor[[A] =>> SizedVector[A, 1]]].map((n: Int) => n + 10)(left)
    val result: SizedVector[Int, 3] = mapped.concatSized(right)

    assertEquals(result.unsized, Vector(11, 2, 3))
  }

  test("the map is a lawful functor at every fixed length") {
    val F       = summon[Endofunctor[[A] =>> SizedVector[A, 2]]]
    val values  = SizedVector.assumeSize[Int, 2](Vector(1, 2))
    val id      = F.identities[Int]
    val compose = F.composition((n: Int) => n.toString, (s: String) => s.length)

    assertEquals(id.lhs(values), id.rhs(values))
    assertEquals(compose.lhs(values), compose.rhs(values))
  }

  test("empty carries length zero") {
    val result: SizedVector[String, 0] = SizedVector.empty

    assertEquals(result.unsized, Vector.empty)
  }

  test("different singleton lengths are not interchangeable") {
    val errors = compileErrors("""
      import dspy4s.core.collections.SizedVector

      val zero: SizedVector[Int, 0] = SizedVector.empty
      val one: SizedVector[Int, 1]  = zero
    """)

    assert(errors.nonEmpty, "expected a statically known length mismatch to fail compilation")
  }
