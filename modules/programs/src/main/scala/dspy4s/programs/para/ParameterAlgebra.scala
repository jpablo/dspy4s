package dspy4s.programs.para

import dspy4s.core.contracts.Monoid
import dspy4s.programs.DynamicPredict

/** The free monoid of homogeneous Para parameters under concatenation.
  *
  * This is the codomain of the `Predictors` homomorphism and makes parameter composition explicit rather than encoding
  * it in an ad-hoc category whose composition merely happens to be `++`.
  */
given paramsMonoid: Monoid[Vector[DynamicPredict]] with
  def empty: Vector[DynamicPredict] = Vector.empty
  extension (a: Vector[DynamicPredict])
    infix def combine(b: Vector[DynamicPredict]): Vector[DynamicPredict] = a ++ b

/** Morphisms in the one-object category induced by [[paramsMonoid]]. */
type ParamsHom = Delooped[Vector[DynamicPredict]]

/** The parameter monoid delooped into a category. This is the target of [[ReadFunctor]]. */
given paramsDeloop: Category[AnyObject, ParamsHom] = delooping[Vector[DynamicPredict]]
