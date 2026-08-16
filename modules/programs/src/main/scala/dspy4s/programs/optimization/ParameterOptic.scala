package dspy4s.programs.optimization

import dspy4s.algebra.{AssociativeFunctor, Composer, FocusFunctor, Lens, Optic}

import scala.compiletime.ops.int.+

/** One named, read-only optimizer focus. Replacement accepts only [[OptimizableParameters]], so metadata and names
  * remain outside the writable surface.
  */
private[dspy4s] final case class NamedOptimizableView(displayName: String, view: OptimizableView)

/** A reconstruction context paired with an ordered vector of optic foci.
  *
  * This is the dspy4s-specific `MultiFocus[Vector]` carrier. It uses `Vector`, instead of the broader cats-eo carrier
  * hierarchy, because optimizer order and fixed program arity are the only required semantics.
  */
private[dspy4s] final case class ParameterCarrier[X, A](context: X, values: Vector[A])

private[dspy4s] object ParameterCarrier:
  given parameterFocusFunctor: FocusFunctor[ParameterCarrier] with
    def map[X, A, B](focus: ParameterCarrier[X, A])(f: A => B): ParameterCarrier[X, B] =
      ParameterCarrier(focus.context, focus.values.map(f))

  /** Flatten nested focus vectors during composition and retain the size and context of each inner vector for the
    * reverse rebuild.
    */
  given parameterAssociativeFunctor[XOuter, XInner]
      : AssociativeFunctor[ParameterCarrier, XOuter, XInner] with
    type Z = (XOuter, Vector[(XInner, Int)])

    def composeTo[Source, Target, A, B, C, D](
        source: Source,
        outer: Optic[Source, Target, A, B, ParameterCarrier] { type X = XOuter },
        inner: Optic[A, B, C, D, ParameterCarrier] { type X = XInner }
    ): ParameterCarrier[Z, C] =
      val openedOuter = outer.to(source)
      val openedInner = openedOuter.values.map(inner.to)
      val contexts    = openedInner.map(opened => opened.context -> opened.values.size)
      ParameterCarrier(openedOuter.context -> contexts, openedInner.flatMap(_.values))

    def composeFrom[Source, Target, A, B, C, D](
        focus: ParameterCarrier[Z, D],
        inner: Optic[A, B, C, D, ParameterCarrier] { type X = XInner },
        outer: Optic[Source, Target, A, B, ParameterCarrier] { type X = XOuter }
    ): Target =
      val (outerContext, innerContexts) = focus.context
      val rebuilt                      = Vector.newBuilder[B]
      rebuilt.sizeHint(innerContexts.size)
      var cursor = 0

      innerContexts.foreach { case (innerContext, size) =>
        val end = cursor + size
        require(end <= focus.values.size, "Parameter optic received fewer replacement foci than its context requires")
        rebuilt.addOne(inner.from(ParameterCarrier(innerContext, focus.values.slice(cursor, end))))
        cursor = end
      }

      require(
        cursor == focus.values.size,
        s"Parameter optic context consumes $cursor replacement foci, but received ${focus.values.size}"
      )
      outer.from(ParameterCarrier(outerContext, rebuilt.result()))

  /** A total single focus is the one-element case of the parameter carrier. This bridge lets a structural Lens compose
    * with a multi-focus parameter optic.
    */
  given tuple2ToParameterCarrier: Composer[Tuple2, ParameterCarrier] with
    def to[Source, Target, A, B](
        optic: Optic[Source, Target, A, B, Tuple2]
    ): Optic[Source, Target, A, B, ParameterCarrier] =
      new Optic[Source, Target, A, B, ParameterCarrier]:
        type X = optic.X

        def to(source: Source): ParameterCarrier[X, A] =
          val (context, value) = optic.to(source)
          ParameterCarrier(context, Vector(value))

        def from(focus: ParameterCarrier[X, B]): Target =
          require(focus.values.size == 1, s"A single-focus optic expects 1 replacement, got ${focus.values.size}")
          optic.from(focus.context -> focus.values.head)

/** A statically graded optic over every optimizer focus in `P`.
  *
  * The carrier holds the runtime vector. `N` retains its exact size at compile time through the existing
  * [[OptimizableStructure]] API.
  */
private[dspy4s] trait ParameterOptic[P, N <: Int]
    extends Optic[P, P, NamedOptimizableView, OptimizableParameters, ParameterCarrier]:
  def arity(program: P): Int

private[dspy4s] object ParameterOptic:

  def apply[P, N <: Int, Context](
      open : P => ParameterCarrier[Context, NamedOptimizableView],
      close: ParameterCarrier[Context, OptimizableParameters] => P
  ): ParameterOptic[P, N] =
    withArity(program => open(program).values.size, open, close)

  private def withArity[P, N <: Int, Context](
      measure: P => Int,
      open : P => ParameterCarrier[Context, NamedOptimizableView],
      close: ParameterCarrier[Context, OptimizableParameters] => P
  ): ParameterOptic[P, N] =
    new ParameterOptic[P, N]:
      type X = Context
      def arity(program: P): Int = measure(program)
      def to(source: P): ParameterCarrier[X, NamedOptimizableView] = open(source)
      def from(focus: ParameterCarrier[X, OptimizableParameters]): P = close(focus)

  /** Treat an existing structure as an optic. This adapter permits incremental migration of third-party instances. */
  def fromStructure[P, N <: Int](structure: OptimizableStructure.WithArity[P, N]): ParameterOptic[P, N] =
    withArity[P, N, P](
      structure.arity,
      program =>
        val views = structure.inspect(program)
        val named = structure.inspectNamed(program)
        require(
          named.size == views.size,
          s"OptimizableStructure.inspectNamed returned ${named.size} entries but inspect returned ${views.size}"
        )
        require(
          named.map(_._2) == views,
          "OptimizableStructure.inspectNamed must preserve the views and order returned by inspect"
        )
        ParameterCarrier(program, named.map { case (name, view) => NamedOptimizableView(name, view) }),
      focus => structure.replace(focus.context, focus.values)
    )

  /** Convert an optic back to the stable public [[OptimizableStructure]] API. */
  def toStructure[P, N <: Int](label: String, optic: ParameterOptic[P, N]): OptimizableStructure.Of[P, N] =
    new OptimizableStructure.Of[P, N]:
      def arity(program: P): Int = optic.arity(program)

      def inspect(program: P): Vector[OptimizableView] =
        optic.to(program).values.map(_.view)

      override def inspectNamed(program: P): Vector[(String, OptimizableView)] =
        optic.to(program).values.map(focus => focus.displayName -> focus.view)

      def replace(program: P, updates: Vector[OptimizableParameters]): P =
        val opened = optic.to(program)
        require(
          updates.size == opened.values.size,
          s"$label expects ${opened.values.size} updates, got ${updates.size}"
        )
        optic.from(ParameterCarrier(opened.context, updates))

  /** Empty focus set for a deliberately non-learnable value. */
  def empty[P]: ParameterOptic[P, 0] =
    withArity[P, 0, P](
      _ => 0,
      program => ParameterCarrier(program, Vector.empty),
      focus =>
        require(focus.values.isEmpty, s"Parameter-free program expects 0 updates, got ${focus.values.size}")
        focus.context
    )

  /** One optimizer leaf with a caller-defined read and write boundary. */
  def leaf[P](
      name   : String,
      inspect: P => OptimizableView,
      replace: (P, OptimizableParameters) => P
  ): ParameterOptic[P, 1] =
    withArity[P, 1, P](
      _ => 1,
      program => ParameterCarrier(program, Vector(NamedOptimizableView(name, inspect(program)))),
      focus =>
        require(focus.values.size == 1, s"Optimizer leaf expects 1 update, got ${focus.values.size}")
        replace(focus.context, focus.values.head)
    )

  /** Compose a structural Lens with an inner multi-focus parameter optic. The Lens carrier crosses into
    * [[ParameterCarrier]] through its single declared [[Composer]] bridge.
    */
  def through[Whole, Part, N <: Int](lens: Lens[Whole, Part], inner: ParameterOptic[Part, N]): ParameterOptic[Whole, N] =
    val outer    = summon[Composer[Tuple2, ParameterCarrier]].to(lens)
    val composed = outer.andThen(inner)
    withArity[Whole, N, composed.X](whole => inner.arity(lens.get(whole)), composed.to, composed.from)

  /** Public-API adapter for a transparent wrapper that has one structural child. */
  def throughStructure[Whole, Part, N <: Int](
      label       : String,
      select      : Whole => Part,
      replaceInner: (Whole, Part) => Whole,
      inner       : OptimizableStructure.WithArity[Part, N]
  ): OptimizableStructure.Of[Whole, N] =
    val lens = new Lens[Whole, Part]:
      def get(whole: Whole): Part                       = select(whole)
      def set(whole: Whole, replacement: Part): Whole   = replaceInner(whole, replacement)

    toStructure(label, through(lens, fromStructure(inner)))

  /** Combine two heterogeneous parameter optics over the corresponding pair. */
  def pair[A, B, NA <: Int, NB <: Int](
      left       : ParameterOptic[A, NA],
      right      : ParameterOptic[B, NB],
      leftPrefix : Option[String],
      rightPrefix: Option[String]
  ): ParameterOptic[(A, B), NA + NB] =
    type Context = (left.X, right.X, Int)

    def prefix(name: String, withPrefix: Option[String]): String =
      withPrefix.fold(name)(label => if name == "self" then label else s"$label.$name")

    withArity[(A, B), NA + NB, Context](
      value => left.arity(value._1) + right.arity(value._2),
      value =>
        val openedLeft  = left.to(value._1)
        val openedRight = right.to(value._2)
        ParameterCarrier(
          (openedLeft.context, openedRight.context, openedLeft.values.size),
          openedLeft.values.map(focus => focus.copy(displayName = prefix(focus.displayName, leftPrefix))) ++
            openedRight.values.map(focus => focus.copy(displayName = prefix(focus.displayName, rightPrefix)))
        ),
      focus =>
        val (leftContext, rightContext, splitAt) = focus.context
        require(splitAt <= focus.values.size, s"Parameter pair split $splitAt exceeds ${focus.values.size} updates")
        val (leftValues, rightValues) = focus.values.splitAt(splitAt)
        left.from(ParameterCarrier(leftContext, leftValues)) ->
          right.from(ParameterCarrier(rightContext, rightValues))
    )

  /** Focus two children of a larger value, combine their parameter optics, and rebuild the larger value. */
  def pairThrough[Whole, A, B, NA <: Int, NB <: Int](
      lens       : Lens[Whole, (A, B)],
      left       : ParameterOptic[A, NA],
      right      : ParameterOptic[B, NB],
      leftPrefix : Option[String],
      rightPrefix: Option[String]
  ): ParameterOptic[Whole, NA + NB] =
    through(lens, pair(left, right, leftPrefix, rightPrefix))

  /** Public-API adapter for a wrapper that has two structural children. */
  def pairStructure[Whole, A, B, NA <: Int, NB <: Int](
      label       : String,
      getLeft     : Whole => A,
      getRight    : Whole => B,
      replacePair : (Whole, A, B) => Whole,
      left        : OptimizableStructure.WithArity[A, NA],
      right       : OptimizableStructure.WithArity[B, NB],
      leftPrefix  : Option[String],
      rightPrefix : Option[String]
  ): OptimizableStructure.Of[Whole, NA + NB] =
    val lens = new Lens[Whole, (A, B)]:
      def get(whole: Whole): (A, B) = getLeft(whole) -> getRight(whole)
      def set(whole: Whole, replacement: (A, B)): Whole =
        replacePair(whole, replacement._1, replacement._2)

    toStructure(
      label,
      pairThrough(lens, fromStructure(left), fromStructure(right), leftPrefix, rightPrefix)
    )
