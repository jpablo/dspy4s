package dspy4s.core.algebra

/** The identity object mapping. */
type Id[A] = A

/** A functor between two `Hom`-indexed categories with object mapping `F`. */
trait Functor[
    F[_],
    SourceConstraint[_],
    Source[_, _],
    TargetConstraint[_],
    Target[_, _]
](using
    source: Category[SourceConstraint, Source],
    target: Category[TargetConstraint, Target]
):
  final val sourceCategory: Category[SourceConstraint, Source] = source
  final val targetCategory: Category[TargetConstraint, Target] = target

  /** Evidence that the object mapping sends valid source objects to valid target objects. */
  def mapObject[A](using SourceConstraint[A]): TargetConstraint[F[A]]

  def map[A, B](f: Source[A, B]): Target[F[A], F[B]]

  @Law("functor preserves identities")
  def identities[A: SourceConstraint]: IsEq[Target[F[A], F[A]]] =
    given TargetConstraint[F[A]] = mapObject[A]
    map(source.id[A]) <-> target.id[F[A]]

  @Law("functor preserves composition")
  def composition[A, B, C](f: Source[A, B], g: Source[B, C]): IsEq[Target[F[A], F[C]]] =
    map(f >>> g) <-> (map(f) >>> map(g))

object Functor:
  def apply[F[_], SourceConstraint[_], Source[_, _], TargetConstraint[_], Target[_, _]](using
      functor: Functor[F, SourceConstraint, Source, TargetConstraint, Target]
  ): Functor[F, SourceConstraint, Source, TargetConstraint, Target] = functor

  /** The identity functor on a category. */
  def identity[ObjectConstraint[_], Hom[_, _]](using
      category: Category[ObjectConstraint, Hom]
  ): Functor[Id, ObjectConstraint, Hom, ObjectConstraint, Hom] =
    new Functor[Id, ObjectConstraint, Hom, ObjectConstraint, Hom](using category, category):
      def mapObject[A](using evidence: ObjectConstraint[A]): ObjectConstraint[A] = evidence
      def map[A, B](f                : Hom[A, B]): Hom[A, B]                     = f

  /** Compose the object and morphism mappings of two functors. */
  def andThen[
      F[_],
      G[_],
      SourceConstraint[_],
      Source[_, _],
      MiddleConstraint[_],
      Middle[_, _],
      TargetConstraint[_],
      Target[_, _]
  ](
      first : Functor[F, SourceConstraint, Source, MiddleConstraint, Middle],
      second: Functor[G, MiddleConstraint, Middle, TargetConstraint, Target]
  ): Functor[[A] =>> G[F[A]], SourceConstraint, Source, TargetConstraint, Target] =
    new Functor[[A] =>> G[F[A]], SourceConstraint, Source, TargetConstraint, Target](using
      first.sourceCategory,
      second.targetCategory
    ):
      def mapObject[A](using SourceConstraint[A]): TargetConstraint[G[F[A]]] =
        given MiddleConstraint[F[A]] = first.mapObject[A]
        second.mapObject[F[A]]

      def map[A, B](f: Source[A, B]): Target[G[F[A]], G[F[B]]] =
        second.map(first.map(f))

/** An endofunctor on an arbitrary category. */
type EndofunctorIn[F[_], ObjectConstraint[_], Hom[_, _]] = Functor[F, ObjectConstraint, Hom, ObjectConstraint, Hom]

/** An endofunctor on Scala types and total functions. */
type Endofunctor[F[_]] = EndofunctorIn[F, AnyObject, Function1]
