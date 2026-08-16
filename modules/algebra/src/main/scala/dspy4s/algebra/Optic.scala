package dspy4s.algebra

/** A value-level existential optic.
  *
  * The carrier `F` describes the shape of the focus. The hidden `X` retains the context that is required to rebuild
  * `T` after an `A` focus is replaced with a `B` focus.
  *
  * This encoding and its carrier composition follow the design of cats-eo's `Optic` and `AssociativeFunctor`, adapted
  * here as a small dependency-free kernel. cats-eo is licensed under Apache-2.0.
  */
trait Optic[S, T, A, B, F[_, _]]:
  self =>

  /** Hidden reconstruction context. */
  type X

  /** Open the source into its reconstruction context and focus. */
  def to(source: S): F[X, A]

  /** Rebuild the target from the retained context and a replacement focus. */
  def from(focus: F[X, B]): T

  /** Compose two optics that use the same carrier. */
  def andThen[C, D](inner: Optic[A, B, C, D, F])(using
      composition: AssociativeFunctor[F, self.X, inner.X]
  ): Optic[S, T, C, D, F] =
    new Optic[S, T, C, D, F]:
      type X = composition.Z

      def to(source: S): F[X, C] =
        composition.composeTo(source, self, inner)

      def from(focus: F[X, D]): T =
        composition.composeFrom(focus, inner, self)

/** Map the focus while the carrier retains its reconstruction context. */
trait FocusFunctor[F[_, _]]:
  def map[X, A, B](focus: F[X, A])(f: A => B): F[X, B]

object FocusFunctor:
  given tuple2FocusFunctor: FocusFunctor[Tuple2] with
    def map[X, A, B](focus: (X, A))(f: A => B): (X, B) = focus._1 -> f(focus._2)

/** Same-carrier optic composition.
  *
  * The instance combines the hidden contexts of the outer and inner optics. Each carrier defines how focus extraction
  * and target reconstruction associate.
  */
trait AssociativeFunctor[F[_, _], XOuter, XInner]:
  type Z

  def composeTo[S, T, A, B, C, D](
      source: S,
      outer: Optic[S, T, A, B, F] { type X = XOuter },
      inner: Optic[A, B, C, D, F] { type X = XInner }
  ): F[Z, C]

  def composeFrom[S, T, A, B, C, D](
      focus: F[Z, D],
      inner: Optic[A, B, C, D, F] { type X = XInner },
      outer: Optic[S, T, A, B, F] { type X = XOuter }
  ): T

object AssociativeFunctor:
  /** Product association for total, single-focus optics such as [[Lens]]. */
  given tuple2AssociativeFunctor[XOuter, XInner]: AssociativeFunctor[Tuple2, XOuter, XInner] with
    type Z = (XOuter, XInner)

    def composeTo[S, T, A, B, C, D](
        source: S,
        outer: Optic[S, T, A, B, Tuple2] { type X = XOuter },
        inner: Optic[A, B, C, D, Tuple2] { type X = XInner }
    ): (Z, C) =
      val (outerContext, value) = outer.to(source)
      val (innerContext, focus) = inner.to(value)
      (outerContext -> innerContext) -> focus

    def composeFrom[S, T, A, B, C, D](
        focus: (Z, D),
        inner: Optic[A, B, C, D, Tuple2] { type X = XInner },
        outer: Optic[S, T, A, B, Tuple2] { type X = XOuter }
    ): T =
      val ((outerContext, innerContext), replacement) = focus
      outer.from(outerContext -> inner.from(innerContext -> replacement))

/** Re-express an optic over a different carrier. One bridge replaces all pair-specific conversion overloads. */
trait Composer[F[_, _], G[_, _]]:
  def to[S, T, A, B](optic: Optic[S, T, A, B, F]): Optic[S, T, A, B, G]

object Optic:
  extension [S, T, A, B, F[_, _]](optic: Optic[S, T, A, B, F])
    /** Apply a focus transformation when the carrier supports focus mapping. */
    def modify(source: S)(f: A => B)(using functor: FocusFunctor[F]): T =
      optic.from(functor.map(optic.to(source))(f))

    /** Move this optic to another carrier through one declared bridge. */
    def morph[G[_, _]](using composer: Composer[F, G]): Optic[S, T, A, B, G] =
      composer.to(optic)
