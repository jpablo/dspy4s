package dspy4s.programs.algebra

import scala.compiletime.ops.int.+

/** Ordered pairing over a shared input for naturally graded morphisms.
  *
  * Both legs receive the same input and execute left-to-right. No product, copying-naturality, concurrency, or symmetry
  * laws are claimed: effects make sharing one computation observably different from running it twice.
  */
trait OrderedFanout[Hom[_, _, _ <: Int]]:
  def fanout[I, A, B, N <: Int, M <: Int](
      f: Hom[I, A, N],
      g: Hom[I, B, M]
  ): Hom[I, (A, B), N + M]

  /** Compatibility name for [[fanout]]. The operation remains ordered, not concurrent. */
  final def parallel[I, A, B, N <: Int, M <: Int](
      f: Hom[I, A, N],
      g: Hom[I, B, M]
  ): Hom[I, (A, B), N + M] = fanout(f, g)
