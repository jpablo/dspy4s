package dspy4s.programs.optimization

import scala.deriving.Mirror
import scala.util.NotGiven

/** Structural Mirror derivation over a case class. */
trait LowPriorityOptimizableTraversal:

  /** Mirror derivation over a case class: each field's fixed-arity traversal is concatenated (left -> right field order)
    * for `read`, its arity contributes to the type-level sum, and `replace` slices updates by field arity before rebuilding
    * via `m.fromProduct`. Every field must provide evidence; intentionally parameter-free field types opt in explicitly
    * through [[OptimizableTraversal.empty]].
    *
    * The `NotGiven[OptimizableLeaf[P]]` guard keeps the structural derivation from competing with
    * [[OptimizableTraversal.fromOptimizableLeaf]]: a type that is itself a leaf (e.g.
    * [[dspy4s.programs.DynamicPredict]]) must resolve to the 1-element leaf instance, not be torn apart into its
    * case-class fields.
    */
  inline given derived[P <: Product, N <: Int](using
      m: Mirror.ProductOf[P],
      @annotation.unused notLeaf: NotGiven[OptimizableLeaf[P]],
      fields: OptimizableTraversal.FieldTraversals[m.MirroredElemTypes, N]
  ): OptimizableTraversal.Of[P, N] =
    new OptimizableTraversal.DerivedOptimizableTraversal[P, N](
      m,
      fields.instances,
      scala.compiletime.constValueTuple[m.MirroredElemLabels].toList.map(_.toString)
    )
