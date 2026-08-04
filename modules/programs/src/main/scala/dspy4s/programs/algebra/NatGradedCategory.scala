package dspy4s.programs.algebra

import dspy4s.core.algebra.{IsEq, Law, <->}

import scala.compiletime.ops.int.+

/** A category whose morphisms carry a natural-number grade.
  *
  * Identity has grade zero and composition adds grades. `Erased[A, B]` is the same morphism after forgetting its grade;
  * the ordinary category laws are stated there because Scala does not normalize symbolic arithmetic expressions such as
  * `(N + M) + K` and `N + (M + K)` to the same type.
  */
trait NatGradedCategory[P[_], Hom[_, _, _ <: Int], Erased[_, _]]:

  /** Forget only the grade, retaining the morphism's domain, codomain, and behavior. */
  def forget[A, B, N <: Int](f: Hom[A, B, N]): Erased[A, B]

  /** The identity morphism has no graded resources. */
  def id[A: P]: Hom[A, A, 0]

  /** Compose two morphisms and add their grades. */
  def compose[A, B, C, N <: Int, M <: Int](f: Hom[A, B, N], g: Hom[B, C, M]): Hom[A, C, N + M]

  extension [A, B, N <: Int](f: Hom[A, B, N])
    /** Diagrammatic composition: run `f`, then thread its output into `g`. */
    infix final def >>>[C, M <: Int](g: Hom[B, C, M]): Hom[A, C, N + M] = compose(f, g)

  @Law("left unit after forgetting the grade")
  def identityLeft[A: P, B, N <: Int](f: Hom[A, B, N]): IsEq[Erased[A, B]] =
    forget(compose(id[A], f)) <-> forget(f)

  @Law("right unit after forgetting the grade")
  def identityRight[A, B: P, N <: Int](f: Hom[A, B, N]): IsEq[Erased[A, B]] =
    forget(compose(f, id[B])) <-> forget(f)

  @Law("associativity after forgetting the grade")
  def associativity[A, B, C, D, N <: Int, M <: Int, K <: Int](
      f: Hom[A, B, N],
      g: Hom[B, C, M],
      h: Hom[C, D, K]
  ): IsEq[Erased[A, D]] =
    forget(compose(compose(f, g), h)) <-> forget(compose(f, compose(g, h)))
