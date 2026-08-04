package dspy4s.programs.algebra

import dspy4s.core.algebra.{AnyObject, IsEq, Isomorphism, Law, <->}

/** A monoidal category whose object tensor is Scala's tuple and whose unit is `Unit`.
  *
  * Tensor is a bifunctor; associators and unitors are natural isomorphisms; the pentagon and triangle equations provide
  * coherence. This is intentionally stronger than [[OrderedTensorOps]], whose effectful tensor need not satisfy
  * interchange.
  */
trait MonoidalCategory[Hom[_, _]] extends TensorOps[Hom]:

  def associator[A, B, C]
      : Isomorphism[AnyObject, Hom, ((A, B), C), (A, (B, C))]

  def leftUnitor[A]: Isomorphism[AnyObject, Hom, (Unit, A), A]
  def rightUnitor[A]: Isomorphism[AnyObject, Hom, (A, Unit), A]

  @Law("tensor is a bifunctor (interchange)")
  def tensorInterchange[A, B, C, D, E, F2](
      f1: Hom[A, C],
      g1: Hom[B, D],
      f2: Hom[C, E],
      g2: Hom[D, F2]
  ): IsEq[Hom[(A, B), (E, F2)]] =
    (tensor(f1, g1) >>> tensor(f2, g2)) <-> tensor(f1 >>> f2, g1 >>> g2)

  @Law("tensor preserves identities")
  def tensorIdentity[A, B]: IsEq[Hom[(A, B), (A, B)]] =
    tensor(id[A], id[B]) <-> id[(A, B)]

  @Law("the associator is natural")
  def associatorNaturality[A, B, C, D, E, F2](
      f: Hom[A, D],
      g: Hom[B, E],
      h: Hom[C, F2]
  ): IsEq[Hom[((A, B), C), (D, (E, F2))]] =
    (tensor(tensor(f, g), h) >>> associator[D, E, F2].forward) <->
      (associator[A, B, C].forward >>> tensor(f, tensor(g, h)))

  @Law("the left unitor is natural")
  def leftUnitorNaturality[A, B](f: Hom[A, B]): IsEq[Hom[(Unit, A), B]] =
    (tensor(id[Unit], f) >>> leftUnitor[B].forward) <-> (leftUnitor[A].forward >>> f)

  @Law("the right unitor is natural")
  def rightUnitorNaturality[A, B](f: Hom[A, B]): IsEq[Hom[(A, Unit), B]] =
    (tensor(f, id[Unit]) >>> rightUnitor[B].forward) <-> (rightUnitor[A].forward >>> f)

  @Law("Mac Lane pentagon")
  def pentagon[A, B, C, D]: IsEq[Hom[(((A, B), C), D), (A, (B, (C, D)))]] =
    (associator[(A, B), C, D].forward >>> associator[A, B, (C, D)].forward) <->
      (tensor(associator[A, B, C].forward, id[D]) >>>
        associator[A, (B, C), D].forward >>>
        tensor(id[A], associator[B, C, D].forward))

  @Law("monoidal triangle")
  def triangle[A, B]: IsEq[Hom[((A, Unit), B), (A, B)]] =
    (associator[A, Unit, B].forward >>> tensor(id[A], leftUnitor[B].forward)) <->
      tensor(rightUnitor[A].forward, id[B])

/** A monoidal category with a natural symmetric braiding. */
trait SymmetricMonoidalCategory[Hom[_, _]] extends MonoidalCategory[Hom]:

  def braiding[A, B]: Isomorphism[AnyObject, Hom, (A, B), (B, A)]

  @Law("the braiding is natural")
  def braidingNaturality[A, B, C, D](
      f: Hom[A, C],
      g: Hom[B, D]
  ): IsEq[Hom[(A, B), (D, C)]] =
    (tensor(f, g) >>> braiding[C, D].forward) <->
      (braiding[A, B].forward >>> tensor(g, f))

  @Law("the braiding is symmetric")
  def symmetry[A, B]: IsEq[Hom[(A, B), (B, A)]] =
    braiding[A, B].forward <-> braiding[B, A].backward

  @Law("symmetric-monoidal hexagon")
  def hexagon[A, B, C]: IsEq[Hom[((A, B), C), (B, (C, A))]] =
    (associator[A, B, C].forward >>>
      braiding[A, (B, C)].forward >>>
      associator[B, C, A].forward) <->
      (tensor(braiding[A, B].forward, id[C]) >>>
        associator[B, A, C].forward >>>
        tensor(id[B], braiding[A, C].forward))

/** A symmetric monoidal category with a coherent commutative comonoid on every object.
  *
  * Copy and discard need not be natural for arbitrary morphisms. [[MarkovCategory]] adds discard naturality;
  * [[CartesianCategory]] additionally makes copy natural.
  */
trait CopyDiscardCategory[Hom[_, _]] extends SymmetricMonoidalCategory[Hom]:

  def copy[A]: Hom[A, (A, A)]
  def discard[A]: Hom[A, Unit]

  /** Reorder `((A, A), (B, B))` into `((A, B), (A, B))`. */
  final def middleFour[A, B]: Hom[((A, A), (B, B)), ((A, B), (A, B))] =
    associator[A, A, (B, B)].forward >>>
      tensor(id[A], associator[A, B, B].backward) >>>
      tensor(id[A], tensor(braiding[A, B].forward, id[B])) >>>
      tensor(id[A], associator[B, A, B].forward) >>>
      associator[A, B, (A, B)].backward

  @Law("copy is coassociative")
  def copyCoassociativity[A]: IsEq[Hom[A, (A, (A, A))]] =
    (copy[A] >>> tensor(copy[A], id[A]) >>> associator[A, A, A].forward) <->
      (copy[A] >>> tensor(id[A], copy[A]))

  @Law("discard is a left counit for copy")
  def copyLeftCounit[A]: IsEq[Hom[A, A]] =
    (copy[A] >>> tensor(discard[A], id[A]) >>> leftUnitor[A].forward) <-> id[A]

  @Law("discard is a right counit for copy")
  def copyRightCounit[A]: IsEq[Hom[A, A]] =
    (copy[A] >>> tensor(id[A], discard[A]) >>> rightUnitor[A].forward) <-> id[A]

  @Law("copy is cocommutative")
  def copyCocommutativity[A]: IsEq[Hom[A, (A, A)]] =
    (copy[A] >>> braiding[A, A].forward) <-> copy[A]

  @Law("copy is coherent with tensor")
  def copyTensor[A, B]: IsEq[Hom[(A, B), ((A, B), (A, B))]] =
    copy[(A, B)] <-> (tensor(copy[A], copy[B]) >>> middleFour[A, B])

  @Law("discard is coherent with tensor")
  def discardTensor[A, B]: IsEq[Hom[(A, B), Unit]] =
    discard[(A, B)] <-> (tensor(discard[A], discard[B]) >>> leftUnitor[Unit].forward)

  @Law("the unit copies canonically")
  def copyUnit: IsEq[Hom[Unit, (Unit, Unit)]] =
    copy[Unit] <-> leftUnitor[Unit].backward

  @Law("the unit discards by identity")
  def discardUnit: IsEq[Hom[Unit, Unit]] =
    discard[Unit] <-> id[Unit]

  /** The equation that classifies copy-preserving (deterministic) morphisms. */
  def copyNaturality[A, B](f: Hom[A, B]): IsEq[Hom[A, (B, B)]] =
    (f >>> copy[B]) <-> (copy[A] >>> tensor(f, f))

/** A copy-discard category whose tensor unit is terminal: every morphism preserves discard. */
trait MarkovCategory[Hom[_, _]] extends CopyDiscardCategory[Hom]:

  @Law("discard is natural")
  def discardNaturality[A, B](f: Hom[A, B]): IsEq[Hom[A, Unit]] =
    (f >>> discard[B]) <-> discard[A]

/** A Markov category in which every morphism also preserves copy; equivalently, a cartesian monoidal category. */
trait CartesianCategory[Hom[_, _]] extends MarkovCategory[Hom]:

  @Law("copy is natural")
  override def copyNaturality[A, B](f: Hom[A, B]): IsEq[Hom[A, (B, B)]] =
    super.copyNaturality(f)

/** Conventional short name for a complete copy-discard category. */
type CDCategory[Hom[_, _]] = CopyDiscardCategory[Hom]
