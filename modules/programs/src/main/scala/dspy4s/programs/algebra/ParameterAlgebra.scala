package dspy4s.programs.algebra

import dspy4s.core.algebra.{AnyObject, Category, Delooped, Monoid, delooping}
import dspy4s.programs.optimization.OptimizableParameters

/** The free monoid of homogeneous optimizable parameters under concatenation.
  *
  * This is the codomain of the `OptimizableTraversal` homomorphism and makes parameter composition explicit rather than encoding
  * it in an ad-hoc category whose composition merely happens to be `++`.
  */
given paramsMonoid: Monoid[Vector[OptimizableParameters]] with
  def empty: Vector[OptimizableParameters] = Vector.empty
  extension (a: Vector[OptimizableParameters])
    infix def combine(b: Vector[OptimizableParameters]): Vector[OptimizableParameters] = a ++ b

/** Morphisms in the one-object category induced by [[paramsMonoid]]. */
type ParamsHom = Delooped[Vector[OptimizableParameters]]

/** The parameter monoid delooped into a category. This is the target of [[ReadFunctor]]. */
given paramsDeloop: Category[AnyObject, ParamsHom] = delooping[Vector[OptimizableParameters]]
