package dspy4s.algebra

/** A natural transformation between two functors with the same source and target categories.
  *
  * Each valid source object `A` receives a target-category morphism `F[A] -> G[A]`. [[naturality]] states that these
  * components commute with every source morphism.
  */
trait NaturalTransformation[
    F[_],
    G[_],
    SourceConstraint[_],
    Source[_, _],
    TargetConstraint[_],
    Target[_, _]
]:
  self =>

  val source: Functor[F, SourceConstraint, Source, TargetConstraint, Target]
  val target: Functor[G, SourceConstraint, Source, TargetConstraint, Target]

  def component[A](using SourceConstraint[A]): Target[F[A], G[A]]

  /** Vertical composition: run this transformation and then `next`. */
  final def andThen[H[_]](
      next: NaturalTransformation[G, H, SourceConstraint, Source, TargetConstraint, Target]
  ): NaturalTransformation[F, H, SourceConstraint, Source, TargetConstraint, Target] =
    given Category[TargetConstraint, Target] = source.targetCategory
    NaturalTransformation(source, next.target)([A] =>
      (evidence: SourceConstraint[A]) ?=> self.component[A] >>> next.component[A]
    )

  @Law("naturality")
  def naturality[A: SourceConstraint, B: SourceConstraint](f: Source[A, B]): IsEq[Target[F[A], G[B]]] =
    given Category[TargetConstraint, Target] = source.targetCategory
    (source.map(f) >>> component[B]) <-> (component[A] >>> target.map(f))

object NaturalTransformation:

  /** Construct a natural-transformation candidate from its functors and polymorphic component family. */
  def apply[
      F[_],
      G[_],
      SourceConstraint[_],
      Source[_, _],
      TargetConstraint[_],
      Target[_, _]
  ](
      sourceFunctor: Functor[F, SourceConstraint, Source, TargetConstraint, Target],
      targetFunctor: Functor[G, SourceConstraint, Source, TargetConstraint, Target]
  )(
      components: [A] => SourceConstraint[A] ?=> Target[F[A], G[A]]
  ): NaturalTransformation[F, G, SourceConstraint, Source, TargetConstraint, Target] =
    new NaturalTransformation[F, G, SourceConstraint, Source, TargetConstraint, Target]:
      val source: Functor[F, SourceConstraint, Source, TargetConstraint, Target] = sourceFunctor
      val target: Functor[G, SourceConstraint, Source, TargetConstraint, Target] = targetFunctor

      def component[A](using SourceConstraint[A]): Target[F[A], G[A]] = components[A]

  /** The identity natural transformation on a functor. */
  def identity[
      F[_],
      SourceConstraint[_],
      Source[_, _],
      TargetConstraint[_],
      Target[_, _]
  ](
      functor: Functor[F, SourceConstraint, Source, TargetConstraint, Target]
  ): NaturalTransformation[F, F, SourceConstraint, Source, TargetConstraint, Target] =
    NaturalTransformation(functor, functor)([A] =>
      (evidence: SourceConstraint[A]) ?=>
        given TargetConstraint[F[A]] = functor.mapObject[A]
        functor.targetCategory.id[F[A]]
    )

  /** Whisker a transformation on the left by a functor applied to each component. */
  def leftWhisker[
      K[_],
      F[_],
      G[_],
      SourceConstraint[_],
      Source[_, _],
      MiddleConstraint[_],
      Middle[_, _],
      TargetConstraint[_],
      Target[_, _]
  ](
      outer         : Functor[K, MiddleConstraint, Middle, TargetConstraint, Target],
      transformation: NaturalTransformation[F, G, SourceConstraint, Source, MiddleConstraint, Middle]
  ): NaturalTransformation[
    [A] =>> K[F[A]],
    [A] =>> K[G[A]],
    SourceConstraint,
    Source,
    TargetConstraint,
    Target
  ] =
    NaturalTransformation(
      Functor.andThen(transformation.source, outer),
      Functor.andThen(transformation.target, outer)
    )([A] => (evidence: SourceConstraint[A]) ?=> outer.map(transformation.component[A]))

  /** Whisker a transformation on the right by precomposing both of its functors. */
  def rightWhisker[
      H[_],
      F[_],
      G[_],
      InputConstraint[_],
      Input[_, _],
      SourceConstraint[_],
      Source[_, _],
      TargetConstraint[_],
      Target[_, _]
  ](
      transformation: NaturalTransformation[F, G, SourceConstraint, Source, TargetConstraint, Target],
      inner         : Functor[H, InputConstraint, Input, SourceConstraint, Source]
  ): NaturalTransformation[
    [A] =>> F[H[A]],
    [A] =>> G[H[A]],
    InputConstraint,
    Input,
    TargetConstraint,
    Target
  ] =
    NaturalTransformation(
      Functor.andThen(inner, transformation.source),
      Functor.andThen(inner, transformation.target)
    )([A] =>
      (evidence: InputConstraint[A]) ?=>
        given SourceConstraint[H[A]] = inner.mapObject[A]
        transformation.component[H[A]]
    )
