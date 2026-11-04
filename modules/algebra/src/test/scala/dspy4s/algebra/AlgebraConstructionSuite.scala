package dspy4s.algebra

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
    val M                              = summon[ScalaMonad[ErrorOr]]
    def f(value: Int): ErrorOr[String] = if value >= 0 then Right(s"v$value") else Left("negative")
    def g(value: String): ErrorOr[Int] = if value.nonEmpty then Right(value.length) else Left("empty")

    assertEquals(M.bindIdentityLeft(2, f).lhs, M.bindIdentityLeft(2, f).rhs)
    assertEquals(M.bindIdentityRight[Int](Right(2)).lhs, Right(2))
    assertEquals(M.bindAssociativity(Right(2), f, g).lhs, M.bindAssociativity(Right(2), f, g).rhs)
    assertEquals(M.flatMap[Int, String](Left("first"))(f), Left("first"))
  }

  test("the bind laws of Either hold at a Left") {
    val M                              = summon[ScalaMonad[ErrorOr]]
    def f(value: Int): ErrorOr[String] = Right(s"v$value")
    def g(value: String): ErrorOr[Int] = Left(value)
    val failed: ErrorOr[Int]           = Left("first")

    assertEquals(M.bindIdentityRight(failed).lhs, M.bindIdentityRight(failed).rhs)
    assertEquals(M.bindAssociativity(failed, f, g).lhs, M.bindAssociativity(failed, f, g).rhs)
    assertEquals(M.bindAssociativity(Right(2), f, g).lhs, Left("v2"))
    assertEquals(M.bindAssociativity(Right(2), f, g).rhs, Left("v2"))
  }

  test("the functor and categorical monad laws of Either hold at each form of a value") {
    val M                                            = summon[ScalaMonad[ErrorOr]]
    val values: Vector[ErrorOr[Int]]                 = Vector(Right(2), Left("first"))
    val nested: Vector[ErrorOr[ErrorOr[Int]]]        = Vector(Right(Right(2)), Right(Left("inner")), Left("outer"))
    val deep: Vector[ErrorOr[ErrorOr[ErrorOr[Int]]]] = nested.map(value => Right(value)) :+ Left("outermost")
    val increment                                    = (value: Int) => value + 1
    val show                                         = (value: Int) => value.toString

    val identities  = M.endofunctor.identities[Int]
    val composition = M.endofunctor.composition(increment, show)
    values.foreach { value =>
      assertEquals(identities.lhs(value), identities.rhs(value))
      assertEquals(composition.lhs(value), composition.rhs(value))
      assertEquals(M.unitAtF.lhs.component[Int](value), M.unitAtF.rhs.component[Int](value))
      assertEquals(M.fMapUnit.lhs.component[Int](value), M.fMapUnit.rhs.component[Int](value))
    }

    val unitNaturality = M.unit.naturality(increment)
    assertEquals(unitNaturality.lhs(3), unitNaturality.rhs(3))

    val multiplicationNaturality = M.multiplication.naturality(increment)
    nested.foreach(value => assertEquals(multiplicationNaturality.lhs(value), multiplicationNaturality.rhs(value)))

    deep.foreach(value =>
      assertEquals(M.associativity.lhs.component[Int](value), M.associativity.rhs.component[Int](value))
    )
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
