package dspy4s.core.collections

import dspy4s.algebra.{AnyObject, Endofunctor, functionCategory}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.FixedLength

import scala.compiletime.ops.int.+

/** An immutable [[Vector]] whose length is tracked by the singleton `Int` type `N`.
  *
  * Framework code makes a sized vector with [[SizedVector.empty]], with `concatSized`, or with the internal
  * `assumeSize` when it knows the length. Operations exposed here preserve the length invariant in their result types.
  * Ordinary `Vector` operations remain available because Iron refinements are subtypes of their underlying values, but
  * operations whose result length is known should use this API so that information is not erased. At every fixed `N`,
  * mapping is exposed as a lawful [[dspy4s.algebra.Endofunctor]] and therefore cannot change the tracked length.
  */
type SizedVector[A, N <: Int] = Vector[A] :| FixedLength[N]

object SizedVector:

  given functor[N <: Int]: Endofunctor[[A] =>> SizedVector[A, N]] with
    def mapObject[A](using AnyObject[A]): AnyObject[SizedVector[A, N]] = summon
    def map[A, B](f: A => B): SizedVector[A, N] => SizedVector[B, N]   = values => assumeSize(values.map(f))

  /** The uniquely sized empty vector. */
  def empty[A]: SizedVector[A, 0] =
    assumeSize(Vector.empty)

  /** Internal introduction rule used only when the caller has established the length algebraically. */
  private[dspy4s] def assumeSize[A, N <: Int](values: Vector[A]): SizedVector[A, N] =
    values.assume[FixedLength[N]]

  extension [A, N <: Int](values: SizedVector[A, N])

    /** Forget the statically tracked length. */
    def unsized: Vector[A] = values

    /** Concatenate two sized vectors; their lengths add at the type level. */
    def concatSized[M <: Int, B >: A](other: SizedVector[B, M]): SizedVector[B, N + M] =
      assumeSize(values ++ other)
