package dspy4s.programs.algebra

import dspy4s.algebra.{AnyObject, Category, Delooped, Functor, Id, Monoid, NatGradedCategory, delooping}
import dspy4s.core.collections.SizedVector
import dspy4s.core.collections.SizedVector.*
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.optimization.OptimizableView

import scala.compiletime.ops.int.+

/** The free monoid of optimizer views under stable-order concatenation. */
given viewsMonoid: Monoid[Vector[OptimizableView]] with
  def empty: Vector[OptimizableView] = Vector.empty
  extension (a: Vector[OptimizableView])
    infix def combine(b: Vector[OptimizableView]): Vector[OptimizableView] = a ++ b

/** Morphisms in the one-object category induced by [[viewsMonoid]]. */
type ViewsHom = Delooped[Vector[OptimizableView]]

/** The optimizer-view monoid delooped into a category. This is the target of [[InspectFunctor]]. */
given viewsDeloop: Category[AnyObject, ViewsHom] = delooping[Vector[OptimizableView]]

/** The free monoid of homogeneous optimizable parameters under concatenation.
  *
  * This is the codomain of the parameter projection induced by `OptimizableStructure` and makes parameter composition
  * explicit rather than encoding it in an ad-hoc category whose composition merely happens to be `++`.
  */
given paramsMonoid: Monoid[Vector[OptimizableParameters]] with
  def empty: Vector[OptimizableParameters] = Vector.empty
  extension (a: Vector[OptimizableParameters])
    infix def combine(b: Vector[OptimizableParameters]): Vector[OptimizableParameters] = a ++ b

/** Morphisms in the one-object category induced by [[paramsMonoid]]. */
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
