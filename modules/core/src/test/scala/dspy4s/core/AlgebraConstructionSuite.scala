package dspy4s.core

import dspy4s.core.algebra.{IsEq, Kleisli, ScalaMonad, kleisliCategory}
import munit.FunSuite

/** Executable checks for generic algebra constructors rather than any domain-specific carrier. */
class AlgebraConstructionSuite extends FunSuite:

  private type ErrorOr[A]         = Either[String, A]
  private type ErrorKleisli[A, B] = Kleisli[ErrorOr, A, B]

  private val K = kleisliCategory[ErrorOr]

  private def assertKleisliLaw[A, B](
      law    : IsEq[ErrorKleisli[A, B]],
      samples: Vector[A]
  ): Unit =
    samples.foreach(value => assertEquals(law.lhs(value), law.rhs(value)))

  test("Either supplies a lawful ScalaMonad and preserves the first error") {
    val M                              = ScalaMonad[ErrorOr]
    def f(value: Int): ErrorOr[String] = if value >= 0 then Right(s"v$value") else Left("negative")
    def g(value: String): ErrorOr[Int] = if value.nonEmpty then Right(value.length) else Left("empty")

    assertEquals(M.bindIdentityLeft(2, f).lhs, M.bindIdentityLeft(2, f).rhs)
    assertEquals(M.bindIdentityRight[Int](Right(2)).lhs, Right(2))
    assertEquals(M.bindAssociativity(Right(2), f, g).lhs, M.bindAssociativity(Right(2), f, g).rhs)
    assertEquals(M.flatMap[Int, String](Left("first"))(f), Left("first"))
  }

  test("the generic Kleisli category executes the Category laws extensionally") {
    val f: ErrorKleisli[Int, String] = value =>
      if value >= 0 then Right(s"v$value") else Left("negative")
    val g: ErrorKleisli[String, Int] = value => Right(value.length)
    val h: ErrorKleisli[Int, Int]    = value => if value < 3 then Right(value * 2) else Left("too long")

    assertKleisliLaw(K.identityLeft(f), Vector(-1, 0, 3))
    assertKleisliLaw(K.identityRight(f), Vector(-1, 0, 3))
    assertKleisliLaw(K.associativity(f, g, h), Vector(-1, 0, 3, 100))
  }
