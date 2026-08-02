package dspy4s.core.collections

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.FixedLength

import scala.compiletime.ops.int.+

/** An immutable [[Vector]] whose length is tracked by the singleton `Int` type `N`.
  *
  * Runtime vectors cross the boundary through [[SizedVector.fromVector]], which checks their length once. Operations
  * exposed here preserve the length invariant in their result types. Ordinary `Vector` operations remain available
  * because Iron refinements are subtypes of their underlying values, but operations whose result length is known should
  * use this API so that information is not erased.
  */
type SizedVector[A, N <: Int] = Vector[A] :| FixedLength[N]

object SizedVector:

  /** Describes a failed attempt to establish a statically tracked vector length. */
  final case class SizeMismatch(expected: Int, actual: Int) derives CanEqual:
    override def toString: String = s"Expected $expected elements, found $actual"

  /** The uniquely sized empty vector. */
  def empty[A]: SizedVector[A, 0] =
    assumeSize(Vector.empty)

  /** A one-element sized vector. */
  def one[A](value: A): SizedVector[A, 1] =
    assumeSize(Vector(value))

  /** Check a runtime vector against the singleton length `N`. */
  def fromVector[A, N <: Int](values: Vector[A])(using expected: ValueOf[N]): Either[SizeMismatch, SizedVector[A, N]] =
    if values.size == expected.value then Right(assumeSize(values))
    else Left(SizeMismatch(expected.value, values.size))

  /** Internal introduction rule used only when the caller has established the length algebraically. */
  private[dspy4s] def assumeSize[A, N <: Int](values: Vector[A]): SizedVector[A, N] =
    values.assume[FixedLength[N]]

  extension [A, N <: Int](values: SizedVector[A, N])

    /** Forget the statically tracked length. */
    def unsized: Vector[A] = values

    /** Map without changing the tracked length. */
    def mapSized[B](f: A => B): SizedVector[B, N] =
      assumeSize(values.map(f))

    /** Concatenate two sized vectors; their lengths add at the type level. */
    def concatSized[M <: Int, B >: A](other: SizedVector[B, M]): SizedVector[B, N + M] =
      assumeSize(values ++ other)
