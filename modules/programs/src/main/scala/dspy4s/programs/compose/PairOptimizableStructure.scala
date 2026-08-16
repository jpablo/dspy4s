package dspy4s.programs.compose

import dspy4s.programs.optimization.ParameterOptic
import dspy4s.programs.optimization.OptimizableStructure

import scala.compiletime.ops.int.+

/** Shared `OptimizableStructure` distribution for the two-child combinators ([[AndThen]], [[Both]], and [[Tensor]]):
  * structural `inspect(first) ++ inspect(second)`, replacement sliced by `first`'s arity, and `first.` / `second.` name
  * prefixing.
  */
private[compose] object PairOptimizableStructure:
  def structure[Whole, A, B, NA <: Int, NB <: Int](
      label      : String,
      leftName   : String,
      rightName  : String,
      getLeft    : Whole => A,
      getRight   : Whole => B,
      replacePair: (Whole, A, B) => Whole,
      left       : OptimizableStructure.WithArity[A, NA],
      right      : OptimizableStructure.WithArity[B, NB]
  ): OptimizableStructure.Of[Whole, NA + NB] =
    ParameterOptic.pairStructure(
      label,
      getLeft,
      getRight,
      replacePair,
      left,
      right,
      Some(leftName),
      Some(rightName)
    )
