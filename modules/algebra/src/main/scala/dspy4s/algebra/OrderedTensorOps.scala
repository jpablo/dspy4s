package dspy4s.algebra

/** A category equipped with independent-input pairing, with no functoriality or coherence laws assumed. */
trait TensorOps[Hom[_, _]] extends Category[AnyObject, Hom]:
  /** Run `f` on the first input, then `g` on the second, and pair their outputs. */
  def tensor[A, B, C, D](f: Hom[A, C], g: Hom[B, D]): Hom[(A, B), (C, D)]

/** A category with ordered independent-input execution and structural swap/copy/discard operations.
  *
  * No bifunctoriality, symmetry, or discard-naturality laws are asserted: fail-fast errors and ordinary runtime effects
  * make execution order observable. A commutative denotational carrier may implement the stronger
  * [[CopyDiscardCategory]].
  */
trait OrderedTensorOps[Hom[_, _]] extends TensorOps[Hom]:
  def swap[A, B]: Hom[(A, B), (B, A)]
  def copy[A]: Hom[A, (A, A)]
  def discard[A]: Hom[A, Unit]
