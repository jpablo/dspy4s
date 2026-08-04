package dspy4s.programs.algebra

import dspy4s.core.algebra.{IsEq, Law, Lens, <->}
import dspy4s.core.collections.SizedVector
import dspy4s.core.collections.SizedVector.*
import dspy4s.programs.optimization.OptimizableParameters

import scala.compiletime.ops.int.+

/** Lawful access to the complete, fixed-size parameter vector of a naturally graded morphism.
  *
  * The grade is the number of writable optimizer leaves. The lens laws govern replacement; the laws below state that
  * parameter reading is compositional. Ordered fan-out remains a separate optional structure.
  */
trait Parameterization[P[_], Hom[_, _, _ <: Int], Erased[_, _]]:
  def category: NatGradedCategory[P, Hom, Erased]

  /** Read every writable parameter in stable address order. */
  def read[A, B, N <: Int](f: Hom[A, B, N]): SizedVector[OptimizableParameters, N]

  /** Replace every writable parameter while preserving program shape and grade. */
  def replace[A, B, N <: Int](
      f: Hom[A, B, N],
      parameters: SizedVector[OptimizableParameters, N]
  ): Hom[A, B, N]

  /** Compatibility boundary for callers that have erased the vector's size. */
  def replaceUnsized[A, B, N <: Int](
      f: Hom[A, B, N],
      parameters: Vector[OptimizableParameters]
  ): Hom[A, B, N]

  final def parameterLens[A, B, N <: Int]: Lens[Hom[A, B, N], SizedVector[OptimizableParameters, N]] =
    new Lens[Hom[A, B, N], SizedVector[OptimizableParameters, N]]:
      def get(f: Hom[A, B, N]): SizedVector[OptimizableParameters, N] = read(f)
      def set(
          f: Hom[A, B, N],
          parameters: SizedVector[OptimizableParameters, N]
      ): Hom[A, B, N] = replace(f, parameters)

  extension [A, B, N <: Int](f: Hom[A, B, N])
    def sizedParams: SizedVector[OptimizableParameters, N] = read(f)
    def params: Vector[OptimizableParameters]              = read(f).unsized

    def reparamSized(parameters: SizedVector[OptimizableParameters, N]): Hom[A, B, N] =
      replace(f, parameters)

    def reparam(parameters: Vector[OptimizableParameters]): Hom[A, B, N] =
      replaceUnsized(f, parameters)

  @Law("the identity is parameter-free")
  def paramsId[A: P]: IsEq[SizedVector[OptimizableParameters, 0]] =
    read(category.id[A]) <-> SizedVector.empty

  @Law("composition concatenates parameters")
  def paramsCompose[A, B, C, N <: Int, M <: Int](
      f: Hom[A, B, N],
      g: Hom[B, C, M]
  ): IsEq[SizedVector[OptimizableParameters, N + M]] =
    read(category.compose(f, g)) <-> read(f).concatSized(read(g))

  @Law("ordered fan-out concatenates parameters")
  def paramsFanout[I, A, B, N <: Int, M <: Int](
      f: Hom[I, A, N],
      g: Hom[I, B, M]
  )(using fanout: OrderedFanout[Hom]): IsEq[SizedVector[OptimizableParameters, N + M]] =
    read(fanout.fanout(f, g)) <-> read(f).concatSized(read(g))
