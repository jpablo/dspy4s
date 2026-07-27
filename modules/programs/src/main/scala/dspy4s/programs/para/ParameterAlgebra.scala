package dspy4s.programs.para

import dspy4s.core.contracts.Monoid
import dspy4s.programs.predictors.PredictorState

/** The free monoid of homogeneous Para parameters under concatenation.
  *
  * This is the codomain of the `PredictorTraversal` homomorphism and makes parameter composition explicit rather than encoding
  * it in an ad-hoc category whose composition merely happens to be `++`.
  */
given paramsMonoid: Monoid[Vector[PredictorState]] with
  def empty: Vector[PredictorState] = Vector.empty
  extension (a: Vector[PredictorState])
    infix def combine(b: Vector[PredictorState]): Vector[PredictorState] = a ++ b

/** Morphisms in the one-object category induced by [[paramsMonoid]]. */
type ParamsHom = Delooped[Vector[PredictorState]]

/** The parameter monoid delooped into a category. This is the target of [[ReadFunctor]]. */
given paramsDeloop: Category[AnyObject, ParamsHom] = delooping[Vector[PredictorState]]
