package dspy4s.algebra

/** A category whose objects may be constrained by `P`.
  *
  * The constraint is required only where an object-specific capability must be synthesized. Morphisms carry their own
  * evidence, so composition itself remains unconstrained.
  */
trait Category[P[_], Hom[_, _]]:
  /** The category unit at a `P`-equipped object. */
  def id[A: P]: Hom[A, A]

  extension [A, B](f: Hom[A, B])
    /** Diagrammatic composition: run `f`, then thread its output into `g`. */
    infix def >>>[C](g: Hom[B, C]): Hom[A, C]

  @Law("left unit")
  def identityLeft[A: P, B](f: Hom[A, B]): IsEq[Hom[A, B]] = (id[A] >>> f) <-> f

  @Law("right unit")
  def identityRight[A, B: P](f: Hom[A, B]): IsEq[Hom[A, B]] = (f >>> id[B]) <-> f

  @Law("associativity")
  def associativity[A, B, C, D](f: Hom[A, B], g: Hom[B, C], h: Hom[C, D]): IsEq[Hom[A, D]] =
    ((f >>> g) >>> h) <-> (f >>> (g >>> h))

/** The trivial object constraint for categories whose morphisms ignore their object indices. */
type AnyObject[A] = DummyImplicit

/** Scala types and total functions as a category. */
given functionCategory: Category[AnyObject, Function1] with
  def id[A: AnyObject]: A => A = identity

  extension [A, B](f: A => B)
    infix def >>>[C](g: B => C): A => C = f.andThen(g)

/** The delooping B(M) of a monoid M: a one-object category whose morphisms are elements of M. */
type Delooped[M] = [A, B] =>> M

/** Build the one-object category induced by a monoid.
  *
  * This is a plain `def`, rather than a `given`, so it does not compete with more specific category instances.
  */
def delooping[M](using M: Monoid[M]): Category[AnyObject, Delooped[M]] =
  new Category[AnyObject, Delooped[M]]:
    def id[A: AnyObject]: M                          = M.empty
    extension [A, B](f: M) infix def >>>[C](g: M): M = f.combine(g)
