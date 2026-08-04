package dspy4s.core.algebra

/** A monad in an arbitrary category: an endofunctor equipped with a natural unit and multiplication.
  *
  * The three laws state that `F` is a monoid object in the category of endofunctors. [[ScalaMonad]] derives the usual
  * `pure`, `flatten`, and `flatMap` operations when the base category is Scala types and total functions.
  */
trait Monad[F[_], ObjectConstraint[_], Hom[_, _]](using
    val category: Category[ObjectConstraint, Hom]
):

  val endofunctor: EndofunctorIn[F, ObjectConstraint, Hom]

  /** The unit `Id => F`. */
  val unit: NaturalTransformation[Id, F, ObjectConstraint, Hom, ObjectConstraint, Hom]

  /** The multiplication `F ∘ F => F`. */
  val multiplication: NaturalTransformation[
    [A] =>> F[F[A]],
    F,
    ObjectConstraint,
    Hom,
    ObjectConstraint,
    Hom
  ]

  @Law("multiplication after the unit at F is identity")
  def unitAtF: IsEq[NaturalTransformation[F, F, ObjectConstraint, Hom, ObjectConstraint, Hom]] =
    NaturalTransformation
      .rightWhisker(unit, endofunctor)
      .andThen(multiplication) <-> NaturalTransformation.identity(endofunctor)

  @Law("multiplication after F maps the unit is identity")
  def fMapUnit: IsEq[NaturalTransformation[F, F, ObjectConstraint, Hom, ObjectConstraint, Hom]] =
    NaturalTransformation
      .leftWhisker(endofunctor, unit)
      .andThen(multiplication) <-> NaturalTransformation.identity(endofunctor)

  @Law("multiplication is associative")
  def associativity: IsEq[
    NaturalTransformation[[A] =>> F[F[F[A]]], F, ObjectConstraint, Hom, ObjectConstraint, Hom]
  ] =
    NaturalTransformation
      .leftWhisker(endofunctor, multiplication)
      .andThen(multiplication) <->
      NaturalTransformation
        .rightWhisker(multiplication, endofunctor)
        .andThen(multiplication)

object Monad:
  def apply[F[_], ObjectConstraint[_], Hom[_, _]](using
      monad: Monad[F, ObjectConstraint, Hom]
  ): Monad[F, ObjectConstraint, Hom] = monad

/** A categorical monad specialized to Scala types and total functions. */
trait ScalaMonad[F[_]] extends Monad[F, AnyObject, Function1]:

  final def pure[A](value: A): F[A] = unit.component[A](value)

  final def flatten[A](value: F[F[A]]): F[A] = multiplication.component[A](value)

  final def flatMap[A, B](value: F[A])(f: A => F[B]): F[B] =
    flatten(endofunctor.map(f)(value))

  final def map[A, B](f: A => B): F[A] => F[B] = endofunctor.map(f)

  @Law("bind left identity")
  def bindIdentityLeft[A, B](value: A, f: A => F[B]): IsEq[F[B]] =
    flatMap(pure(value))(f) <-> f(value)

  @Law("bind right identity")
  def bindIdentityRight[A](value: F[A]): IsEq[F[A]] =
    flatMap(value)(pure) <-> value

  @Law("bind associativity")
  def bindAssociativity[A, B, C](value: F[A], f: A => F[B], g: B => F[C]): IsEq[F[C]] =
    flatMap(flatMap(value)(f))(g) <-> flatMap(value)(a => flatMap(f(a))(g))

object ScalaMonad:

  def apply[F[_]](using monad: ScalaMonad[F]): ScalaMonad[F] = monad

  /** The error monad: the first `Left` short-circuits, while `Right` values compose normally. */
  given either[E]: ScalaMonad[[A] =>> Either[E, A]] =
    fromComponents(
      [A, B] => (f: A => B) => (value: Either[E, A]) => value.map(f),
      [A] => (value: A) => Right(value),
      [A] => (value: Either[E, Either[E, A]]) => value.flatten
    )

  /** Build a Scala monad from its functor action, unit components, and multiplication components. */
  def fromComponents[F[_]](
      mapComponent: [A, B] => (A => B) => F[A] => F[B],
      pureComponent: [A] => A => F[A],
      flattenComponent: [A] => F[F[A]] => F[A]
  ): ScalaMonad[F] =
    given Category[AnyObject, Function1] = functionCategory

    val functor: Endofunctor[F] = new Functor[F, AnyObject, Function1, AnyObject, Function1]:
      def mapObject[A](using AnyObject[A]): AnyObject[F[A]] = summon
      def map[A, B](f: A => B): F[A] => F[B]                = mapComponent[A, B](f)

    val identity           = Functor.identity[AnyObject, Function1]
    val unitTransformation =
      NaturalTransformation(identity, functor)([A] => (evidence: AnyObject[A]) ?=> pureComponent[A])
    val multiplicationTransformation = NaturalTransformation(Functor.andThen(functor, functor), functor)([A] =>
      (evidence: AnyObject[A]) ?=> flattenComponent[A]
    )

    new ScalaMonad[F]:
      val endofunctor: Endofunctor[F]                                                    = functor
      val unit: NaturalTransformation[Id, F, AnyObject, Function1, AnyObject, Function1] = unitTransformation
      val multiplication
          : NaturalTransformation[[A] =>> F[F[A]], F, AnyObject, Function1, AnyObject, Function1] =
        multiplicationTransformation
