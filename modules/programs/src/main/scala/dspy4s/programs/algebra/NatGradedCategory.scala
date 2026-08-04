package dspy4s.programs.algebra

import dspy4s.core.algebra.{IsEq, Law, <->}

import scala.compiletime.ops.int.+

/** A graded morphism whose grade is existentially hidden.
  *
  * Scala 3 cannot apply an abstract higher-kinded `Hom` to a wildcard argument, so this path-dependent package is the
  * canonical encoding of `Hom[A, B, ?]`. It retains the original morphism without interpreting or collapsing it.
  */
sealed trait AnyGrade[Hom[_, _, _ <: Int], A, B]:
  type Grade <: Int
  val morphism: Hom[A, B, Grade]

object AnyGrade:
  def apply[Hom[_, _, _ <: Int], A, B, N <: Int](f: Hom[A, B, N]): AnyGrade[Hom, A, B] { type Grade = N } =
    new AnyGrade[Hom, A, B]:
      type Grade = N
      val morphism: Hom[A, B, N] = f

/** A category whose morphisms carry a natural-number grade.
  *
  * Identity has grade zero and composition adds grades. [[AnyGrade]] is the same morphism after existentially hiding
  * only its grade; the ordinary category laws are stated there because Scala does not normalize symbolic arithmetic
  * expressions such as `(N + M) + K` and `N + (M + K)` to the same type.
  */
trait NatGradedCategory[P[_], Hom[_, _, _ <: Int]]:

  /** Hide only the grade. This final widening cannot discard or reinterpret the morphism. */
  final def forgetGrade[A, B, N <: Int](f: Hom[A, B, N]): AnyGrade[Hom, A, B] = AnyGrade(f)

  /** The identity morphism has no graded resources. */
  def id[A: P]: Hom[A, A, 0]

  /** Compose two morphisms and add their grades. */
  def compose[A, B, C, N <: Int, M <: Int](f: Hom[A, B, N], g: Hom[B, C, M]): Hom[A, C, N + M]

  extension [A, B, N <: Int](f: Hom[A, B, N])
    /** Diagrammatic composition: run `f`, then thread its output into `g`. */
    infix final def >>>[C, M <: Int](g: Hom[B, C, M]): Hom[A, C, N + M] = compose(f, g)

  @Law("left unit after forgetting the grade")
  def identityLeft[A: P, B, N <: Int](f: Hom[A, B, N]): IsEq[AnyGrade[Hom, A, B]] =
    forgetGrade(compose(id[A], f)) <-> forgetGrade(f)

  @Law("right unit after forgetting the grade")
  def identityRight[A, B: P, N <: Int](f: Hom[A, B, N]): IsEq[AnyGrade[Hom, A, B]] =
    forgetGrade(compose(f, id[B])) <-> forgetGrade(f)

  @Law("associativity after forgetting the grade")
  def associativity[A, B, C, D, N <: Int, M <: Int, K <: Int](
      f: Hom[A, B, N],
      g: Hom[B, C, M],
      h: Hom[C, D, K]
  ): IsEq[AnyGrade[Hom, A, D]] =
    forgetGrade(compose(compose(f, g), h)) <-> forgetGrade(compose(f, compose(g, h)))
