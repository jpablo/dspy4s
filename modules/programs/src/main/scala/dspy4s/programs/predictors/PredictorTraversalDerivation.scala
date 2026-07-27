package dspy4s.programs.predictors

import scala.deriving.Mirror
import scala.util.NotGiven

/** Lowest priority: the structural Mirror derivation over a case class. */
trait LowPriorityPredictorTraversal:

  /** Mirror derivation over a case class: each field's `PredictorTraversal` instances are concatenated (left -> right
    * field order) for `read`, and `replace` slices the updates by per-field read-arity, rebuilding via `m.fromProduct`.
    * Every field must provide evidence; intentionally parameter-free field types opt in explicitly through
    * [[PredictorTraversal.empty]].
    *
    * The `NotGiven[PredictorLens[P]]` guard keeps the structural derivation from competing with
    * [[PredictorTraversal.fromPredictorLens]]: a type that is itself a leaf (e.g.
    * [[dspy4s.programs.DynamicPredict]]) must resolve to the 1-element leaf instance, not be torn apart into its
    * case-class fields.
    */
  inline given derived[P <: Product](using
      m: Mirror.ProductOf[P],
      @annotation.unused notLeaf: NotGiven[PredictorLens[P]]
  ): PredictorTraversal[P] =
    new PredictorTraversal.DerivedPredictorTraversal[P](
      m,
      PredictorTraversal.summonFieldInstances[m.MirroredElemTypes],
      scala.compiletime.constValueTuple[m.MirroredElemLabels].toList.map(_.toString)
    )
