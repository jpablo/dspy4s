package dspy4s.programs.para

import dspy4s.core.contracts.IsEq
import dspy4s.core.contracts.Law
import dspy4s.core.contracts.Monoid
import dspy4s.core.contracts.<->

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
  def identityLeft[A: P, B](f: Hom[A, B]): IsEq[Hom[A, B]] =
    (id[A] >>> f) <-> f

  @Law("right unit")
  def identityRight[A, B: P](f: Hom[A, B]): IsEq[Hom[A, B]] =
    (f >>> id[B]) <-> f

  @Law("associativity")
  def associativity[A, B, C, D](f: Hom[A, B], g: Hom[B, C], h: Hom[C, D]): IsEq[Hom[A, D]] =
    ((f >>> g) >>> h) <-> (f >>> (g >>> h))

/** The trivial object constraint for categories whose morphisms ignore their object indices. */
type AnyObject[A] = DummyImplicit

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

/** An identity-on-objects functor between two `Hom`-indexed categories.
  *
  * The target may collapse objects, as the delooping does, so an explicit object map would be inert here.
  */
trait CategoryFunctor[PS[_], Source[_, _], PT[_], Target[_, _]](using
    source: Category[PS, Source],
    target: Category[PT, Target]
):
  def map[A, B](f: Source[A, B]): Target[A, B]

  @Law("functor preserves identities")
  def identities[A: PS: PT]: IsEq[Target[A, A]] =
    map(source.id[A]) <-> target.id[A]

  @Law("functor preserves composition")
  def composition[A, B, C](f: Source[A, B], g: Source[B, C]): IsEq[Target[A, C]] =
    map(f >>> g) <-> (map(f) >>> map(g))
