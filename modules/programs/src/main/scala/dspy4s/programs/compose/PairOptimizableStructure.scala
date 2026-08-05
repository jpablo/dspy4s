package dspy4s.programs.compose

import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.optimization.OptimizableView

/** Shared `OptimizableStructure` distribution for the two-child combinators ([[AndThen]], [[Both]], and [[Tensor]]):
  * structural `inspect(first) ++ inspect(second)`, replacement sliced by `first`'s arity, and `first.` / `second.` name
  * prefixing.
  */
private[compose] object PairOptimizableStructure:
  def inspect[A, B](
      pa: OptimizableStructure[A],
      pb: OptimizableStructure[B]
  )(first: A, second: B): Vector[OptimizableView] =
    pa.inspect(first) ++ pb.inspect(second)

  def replace[A, B, P](pa: OptimizableStructure[A], pb: OptimizableStructure[B])(
      first  : A,
      second : B,
      updates: Vector[OptimizableParameters]
  )(
      rebuild: (A, B) => P
  ): P =
    val (firstUpdates, secondUpdates) = updates.splitAt(pa.read(first).size)
    rebuild(pa.replace(first, firstUpdates), pb.replace(second, secondUpdates))

  def inspectNamed[A, B](
      pa: OptimizableStructure[A],
      pb: OptimizableStructure[B]
  )(first: A, second: B): Vector[(String, OptimizableView)] =
    pa.inspectNamed(first).map { case (sub, view) => (if sub == "self" then "first" else s"first.$sub") -> view } ++
      pb.inspectNamed(second).map { case (sub, view) => (if sub == "self" then "second" else s"second.$sub") -> view }
