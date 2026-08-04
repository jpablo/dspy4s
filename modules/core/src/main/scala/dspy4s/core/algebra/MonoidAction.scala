package dspy4s.core.algebra

/** A left action of the monoid `M` on values of `A`.
  *
  * With this library's diagrammatic convention `first >>> second`, the action is equivalently a functor from the
  * '''opposite''' delooping `B(M)ᵒᵖ` to endomorphisms of `A`: a combined left action applies `second` to the value and
  * then `first`. [[functor]] makes that variance explicit instead of silently reversing composition.
  */
trait MonoidAction[M, A](using monoid: Monoid[M]):
  def act(m: M, value: A): A

  /** This left action as a functor from `B(M)ᵒᵖ`, with every source object mapped to `A`. */
  final lazy val functor
      : Functor[[X] =>> A, AnyObject, Opposite[Delooped[M]], AnyObject, Function1] =
    val source = opposite(delooping[M])
    new Functor[[X] =>> A, AnyObject, Opposite[Delooped[M]], AnyObject, Function1](using
      source,
      functionCategory
    ):
      def mapObject[X](using AnyObject[X]): AnyObject[A] = summon
      def map[X, Y](m: M): A => A                        = value => act(m, value)

  @Law("identity acts trivially")
  def identity(value: A): IsEq[A] =
    act(monoid.empty, value) <-> value

  @Law("combined actions compose")
  def compatibility(first: M, second: M, value: A): IsEq[A] =
    act(first.combine(second), value) <-> act(first, act(second, value))

object MonoidAction:
  def apply[M, A](using action: MonoidAction[M, A]): MonoidAction[M, A] = action
