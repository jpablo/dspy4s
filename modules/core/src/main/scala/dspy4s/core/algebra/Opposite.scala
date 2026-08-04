package dspy4s.core.algebra

/** The opposite of `Hom`: every morphism has its direction reversed. */
type Opposite[Hom[_, _]] = [A, B] =>> Hom[B, A]

/** Reverse every morphism in a category while retaining the same objects.
  *
  * This is an explicit constructor rather than a `given`: automatically deriving opposite categories can compete with
  * an existing category instance when nested type lambdas reduce to the same `Hom`.
  */
def opposite[P[_], Hom[_, _]](
    category: Category[P, Hom]
): Category[P, Opposite[Hom]] =
  new Category[P, Opposite[Hom]]:
    def id[A: P]: Opposite[Hom][A, A] = category.id[A]

    extension [A, B](f: Opposite[Hom][A, B])
      infix def >>>[C](g: Opposite[Hom][B, C]): Opposite[Hom][A, C] =
        category.>>>(g)(f)
