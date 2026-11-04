package dspy4s.programs.algebra

import dspy4s.algebra.{AnyObject, Category, Delooped, Functor, Id, NatGradedCategory, delooping}
import dspy4s.core.collections.SizedVector
import dspy4s.core.collections.SizedVector.*
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.optimization.OptimizableView

import scala.compiletime.ops.int.+

/** Morphisms in the one-object category induced by the free monoid of optimizer views. */
type ViewsHom = Delooped[Vector[OptimizableView]]

/** The optimizer-view monoid delooped into a category. This is the target of [[InspectFunctor]]. */
given viewsDeloop: Category[AnyObject, ViewsHom] = delooping[Vector[OptimizableView]]

/** Morphisms in the one-object category induced by the free monoid of optimizable parameters. The projection that
  * `OptimizableStructure` induces has this codomain, so composition of parameters is a monoid operation.
  */
type ParamsHom = Delooped[Vector[OptimizableParameters]]

/** The parameter monoid delooped into a category. This is the target of [[ReadFunctor]]. */
given paramsDeloop: Category[AnyObject, ParamsHom] = delooping[Vector[OptimizableParameters]]

/** Fixed-grade parameter morphisms: the grade is retained as the vector's statically tracked length. */
type SizedParamsHom[A, B, N <: Int] = SizedVector[OptimizableParameters, N]

/** Optimizable parameter vectors form a naturally graded one-object category under sized concatenation. */
given sizedParamsCategory: NatGradedCategory[AnyObject, SizedParamsHom] with
  def id[A: AnyObject]: SizedParamsHom[A, A, 0] = SizedVector.empty

  def compose[A, B, C, N <: Int, M <: Int](
      f: SizedParamsHom[A, B, N],
      g: SizedParamsHom[B, C, M]
  ): SizedParamsHom[A, C, N + M] =
    f.concatSized(g)

/** Forgets optimizer metadata while retaining the ordered writable parameters. */
object ForgetMetadataFunctor
    extends Functor[Id, AnyObject, ViewsHom, AnyObject, ParamsHom](using
      viewsDeloop,
      paramsDeloop
    ):
  def mapObject[A](using AnyObject[A]): AnyObject[A]    = summon
  def map[A, B](views: ViewsHom[A, B]): ParamsHom[A, B] =
    views.map(_.parameters)
