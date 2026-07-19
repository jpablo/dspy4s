package dspy4s.core.contracts

/** Reusable abstract algebraic structures, carrying their laws ON the trait as `@Law`/[[IsEq]] statements —
  * the same shape as `dspy4s.programs.para.Cat` / `ParaCat` (and jpablo/math-with-scala's `algebra` traits):
  * the trait fixes the operations and states the equations in terms of them; concrete types provide
  * `given` instances; the law suites execute the statements under the equality honest for each carrier
  * (structural `==`, or observational/extensional equality where `==` is meaningless — e.g. a function-
  * wrapped carrier). The equations are the contract; the instances inherit them for free. */

/** A monoid: an associative binary [[combine]] with a two-sided identity [[empty]]. */
trait Monoid[M]:
  /** The identity element. */
  def empty: M

  /** The associative binary operation. */
  extension (a: M) infix def combine(b: M): M

  @Law("associativity")
  def associativity(a: M, b: M, c: M): IsEq[M] =
    a.combine(b).combine(c) <-> a.combine(b.combine(c))

  @Law("left identity")
  def identityLeft(a: M): IsEq[M] = empty.combine(a) <-> a

  @Law("right identity")
  def identityRight(a: M): IsEq[M] = a.combine(empty) <-> a

object Monoid:
  /** Summon the instance for `M`. */
  def apply[M](using m: Monoid[M]): Monoid[M] = m
