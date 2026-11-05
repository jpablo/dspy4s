package dspy4s.algebra

/** A profunctor over the category `Hom`: contravariant in its first index and covariant in its second. */
trait Profunctor[P[_], Hom[_, _], F[_, _]](using category: Category[P, Hom]):
  def dimap[A, B, C, D](value: F[A, B], before: Hom[C, A], after: Hom[B, D]): F[C, D]

  @Law("profunctor identity")
  def identity[A: P, B: P](value: F[A, B]): IsEq[F[A, B]] =
    dimap(value, category.id[A], category.id[B]) <-> value

  @Law("profunctor composition")
  def composition[A, B, C, D, E, G](
      value       : F[A, B],
      beforeFirst : Hom[C, A],
      afterFirst  : Hom[B, D],
      beforeSecond: Hom[E, C],
      afterSecond : Hom[D, G]
  ): IsEq[F[E, G]] =
    dimap(dimap(value, beforeFirst, afterFirst), beforeSecond, afterSecond) <->
      dimap(value, beforeSecond >>> beforeFirst, afterFirst >>> afterSecond)

/** A profunctor over Scala types and total functions. */
type ScalaProfunctor[F[_, _]] = Profunctor[AnyObject, Function1, F]
