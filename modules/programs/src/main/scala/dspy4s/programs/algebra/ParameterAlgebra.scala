package dspy4s.programs.algebra

import dspy4s.core.algebra.{AnyObject, Category, Delooped, Functor, Id, Monoid, delooping}
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.optimization.OptimizableView

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
  * This is the codomain of the `OptimizableTraversal` homomorphism and makes parameter composition explicit rather than
  * encoding it in an ad-hoc category whose composition merely happens to be `++`.
  */
given paramsMonoid: Monoid[Vector[OptimizableParameters]] with
  def empty: Vector[OptimizableParameters] = Vector.empty
  extension (a: Vector[OptimizableParameters])
    infix def combine(b: Vector[OptimizableParameters]): Vector[OptimizableParameters] = a ++ b

/** Morphisms in the one-object category induced by [[paramsMonoid]]. */
type ParamsHom = Delooped[Vector[OptimizableParameters]]

/** The parameter monoid delooped into a category. This is the target of [[ReadFunctor]]. */
given paramsDeloop: Category[AnyObject, ParamsHom] = delooping[Vector[OptimizableParameters]]

/** Forgets optimizer metadata while retaining the ordered writable parameters. */
object ForgetMetadataFunctor
    extends Functor[Id, AnyObject, ViewsHom, AnyObject, ParamsHom](using
      viewsDeloop,
      paramsDeloop
    ):
  def map[A, B](views: ViewsHom[A, B]): ParamsHom[A, B] =
    views.map(_.parameters)
