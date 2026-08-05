package dspy4s.algebra

import scala.compiletime.ops.int.+

/** A graded morphism whose grade is existentially hidden.
  *
  * Scala 3 cannot apply an abstract higher-kinded `Hom` to a wildcard argument, so this path-dependent package is the
  * canonical encoding of `Hom[A, B, ?]`. It retains the original morphism without interpreting or collapsing it.
  */
sealed trait AnyGrade[Hom[_, _, _ <: Int], A, B]:
  type Grade <: Int
  val morphism: Hom[A, B, Grade]

object AnyGrade:
  def apply[Hom[_, _, _ <: Int], A, B, N <: Int](f: Hom[A, B, N]): AnyGrade[Hom, A, B] { type Grade = N } =
    new AnyGrade[Hom, A, B]:
      type Grade = N
      val morphism: Hom[A, B, N] = f

/** A category whose morphisms carry a natural-number grade.
  *
  * Identity has grade zero and composition adds grades. [[AnyGrade]] is the same morphism after existentially hiding
  * only its grade; the ordinary category laws are stated there because Scala does not normalize symbolic arithmetic
  * expressions such as `(N + M) + K` and `N + (M + K)` to the same type.
  */
trait NatGradedCategory[P[_], Hom[_, _, _ <: Int]]:

  /** The identity morphism has no graded resources. */
  def id[A: P]: Hom[A, A, 0]

  /** Compose two morphisms and add their grades. */
  def compose[A, B, C, N <: Int, M <: Int](f: Hom[A, B, N], g: Hom[B, C, M]): Hom[A, C, N + M]

  extension [A, B, N <: Int](f: Hom[A, B, N])
    /** Diagrammatic composition: run `f`, then thread its output into `g`. */
    infix final def >>>[C, M <: Int](g: Hom[B, C, M]): Hom[A, C, N + M] = compose(f, g)

  /** The ordinary category obtained by existentially hiding morphism grades. */
  final lazy val underlyingCategory: Category[P, [A, B] =>> AnyGrade[Hom, A, B]] =
    new Category[P, [A, B] =>> AnyGrade[Hom, A, B]]:
      def id[A: P]: AnyGrade[Hom, A, A] = AnyGrade(NatGradedCategory.this.id[A])

      extension [A, B](f: AnyGrade[Hom, A, B])
        infix def >>>[C](g: AnyGrade[Hom, B, C]): AnyGrade[Hom, A, C] =
          AnyGrade(NatGradedCategory.this.compose(f.morphism, g.morphism))

  /** The canonical functor that hides only the grade while retaining the unchanged morphism. */
  final lazy val forgetGrade: GradedFunctor[P, Hom, P, [A, B] =>> AnyGrade[Hom, A, B]] =
    new GradedFunctor[P, Hom, P, [A, B] =>> AnyGrade[Hom, A, B]](using this, underlyingCategory):
      def map[A, B, N <: Int](f: Hom[A, B, N]): AnyGrade[Hom, A, B] = AnyGrade(f)

  @Law("left unit after forgetting the grade")
  def identityLeft[A: P, B, N <: Int](f: Hom[A, B, N]): IsEq[AnyGrade[Hom, A, B]] =
    forgetGrade.map(id[A] >>> f) <-> forgetGrade.map(f)

  @Law("right unit after forgetting the grade")
  def identityRight[A, B: P, N <: Int](f: Hom[A, B, N]): IsEq[AnyGrade[Hom, A, B]] =
    forgetGrade.map(f >>> id[B]) <-> forgetGrade.map(f)

  @Law("associativity after forgetting the grade")
  def associativity[A, B, C, D, N <: Int, M <: Int, K <: Int](
      f: Hom[A, B, N],
      g: Hom[B, C, M],
      h: Hom[C, D, K]
  ): IsEq[AnyGrade[Hom, A, D]] =
    forgetGrade.map((f >>> g) >>> h) <-> forgetGrade.map(f >>> (g >>> h))

/** An identity-on-objects functor from a naturally graded category to an ordinary category.
  *
  * Mapping hides or interprets the source grade while preserving the domain, codomain, identities, and composition.
  */
trait GradedFunctor[PS[_], Source[_, _, _ <: Int], PT[_], Target[_, _]](using
    source: NatGradedCategory[PS, Source],
    target: Category[PT, Target]
):
  def map[A, B, N <: Int](f: Source[A, B, N]): Target[A, B]

  @Law("graded functor preserves identities")
  def identities[A: {PS, PT}]: IsEq[Target[A, A]] =
    map(source.id[A]) <-> target.id[A]

  @Law("graded functor preserves composition")
  def composition[A, B, C, N <: Int, M <: Int](
      f: Source[A, B, N],
      g: Source[B, C, M]
  ): IsEq[Target[A, C]] =
    map(f >>> g) <-> (map(f) >>> map(g))

/** A functor between naturally graded categories that preserves each morphism's grade exactly.
  *
  * Unlike [[GradedFunctor]], whose target is ordinary and therefore hides or interprets the grade, this structure maps
  * `Source[A, B, N]` to `Target[F[A], F[B], N]`. The target can consequently retain static information such as a
  * parameter vector's exact length.
  */
trait GradePreservingFunctor[
    F[_],
    SourceConstraint[_],
    Source[_, _, _ <: Int],
    TargetConstraint[_],
    Target[_, _, _ <: Int]
](using
    source: NatGradedCategory[SourceConstraint, Source],
    target: NatGradedCategory[TargetConstraint, Target]
):
  final val sourceCategory: NatGradedCategory[SourceConstraint, Source] = source
  final val targetCategory: NatGradedCategory[TargetConstraint, Target] = target

  /** Evidence that valid source objects remain valid after object mapping. */
  def mapObject[A](using SourceConstraint[A]): TargetConstraint[F[A]]

  def map[A, B, N <: Int](f: Source[A, B, N]): Target[F[A], F[B], N]

  @Law("grade-preserving functor preserves identities")
  def identities[A: SourceConstraint]: IsEq[Target[F[A], F[A], 0]] =
    given TargetConstraint[F[A]] = mapObject[A]
    map(source.id[A]) <-> target.id[F[A]]

  @Law("grade-preserving functor preserves composition")
  def composition[A, B, C, N <: Int, M <: Int](
      f: Source[A, B, N],
      g: Source[B, C, M]
  ): IsEq[Target[F[A], F[C], N + M]] =
    map(source.compose(f, g)) <-> target.compose(map(f), map(g))
