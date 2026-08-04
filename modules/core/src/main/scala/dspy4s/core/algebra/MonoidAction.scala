package dspy4s.core.algebra

/** A left action of the monoid `M` on values of `A`. Equivalently, a functor from the delooping `B(M)` to
  * endomorphisms of `A`.
  */
trait MonoidAction[M, A](using monoid: Monoid[M]):
  def act(m: M, value: A): A

  @Law("identity acts trivially")
  def identity(value: A): IsEq[A] =
    act(monoid.empty, value) <-> value

  @Law("combined actions compose")
  def compatibility(first: M, second: M, value: A): IsEq[A] =
    act(first.combine(second), value) <-> act(first, act(second, value))

object MonoidAction:
  def apply[M, A](using action: MonoidAction[M, A]): MonoidAction[M, A] = action
