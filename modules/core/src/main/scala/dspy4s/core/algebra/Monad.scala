package dspy4s.core.algebra

/** A monad on Scala types and total functions.
  *
  * `map` is derived from [[pure]] and [[flatMap]], so every instance is also an [[Endofunctor]].
  */
trait Monad[F[_]] extends Endofunctor[F]:
  protected given source: Category[AnyObject, Function1] = functionCategory
  protected given target: Category[AnyObject, Function1] = functionCategory

  def pure[A](value: A): F[A]

  def flatMap[A, B](value: F[A])(f: A => F[B]): F[B]

  final def map[A, B](f: A => B): F[A] => F[B] =
    value => flatMap(value)(a => pure(f(a)))

  @Law("left identity")
  def identityLeft[A, B](value: A, f: A => F[B]): IsEq[F[B]] =
    flatMap(pure(value))(f) <-> f(value)

  @Law("right identity")
  def identityRight[A](value: F[A]): IsEq[F[A]] =
    flatMap(value)(pure) <-> value

  @Law("associativity")
  def associativity[A, B, C](value: F[A], f: A => F[B], g: B => F[C]): IsEq[F[C]] =
    flatMap(flatMap(value)(f))(g) <-> flatMap(value)(a => flatMap(f(a))(g))

object Monad:
  def apply[F[_]](using monad: Monad[F]): Monad[F] = monad
