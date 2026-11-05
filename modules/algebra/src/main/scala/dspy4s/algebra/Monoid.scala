package dspy4s.algebra

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
  /** A vector under concatenation is the free monoid on its elements. */
  given vector[A]: Monoid[Vector[A]] with
    def empty: Vector[A]                                                = Vector.empty
    extension (a: Vector[A]) infix def combine(b: Vector[A]): Vector[A] = a ++ b
