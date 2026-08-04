package dspy4s.core.algebra

/** A pair of mutually inverse morphisms between `A` and `B`. */
trait Isomorphism[P[_], Hom[_, _], A: P, B: P](using category: Category[P, Hom]):
  def forward: Hom[A, B]
  def backward: Hom[B, A]

  @Law("forward followed by backward is identity")
  def forwardBackward: IsEq[Hom[A, A]] =
    category.>>>(forward)(backward) <-> category.id[A]

  @Law("backward followed by forward is identity")
  def backwardForward: IsEq[Hom[B, B]] =
    category.>>>(backward)(forward) <-> category.id[B]

object Isomorphism:
  def apply[P[_], Hom[_, _], A: P, B: P](
      forwardMorphism: Hom[A, B],
      backwardMorphism: Hom[B, A]
  )(using Category[P, Hom]): Isomorphism[P, Hom, A, B] =
    new Isomorphism[P, Hom, A, B]:
      val forward: Hom[A, B]  = forwardMorphism
      val backward: Hom[B, A] = backwardMorphism
