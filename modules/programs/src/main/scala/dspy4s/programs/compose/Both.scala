package dspy4s.programs.compose

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.data.RawPrediction
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.TransparentModule
import dspy4s.programs.optimization.*
import dspy4s.programs.contracts.Prediction

import scala.compiletime.ops.int.+

/** `fanout(a, b)` — run both programs on the same input and tuple their outputs. On the synchronous `Either` substrate
  * the two attempts run left-to-right and fail fast; this is Arrow-like `&&&`, not concurrent execution and not by
  * itself an `Applicative` instance. The result's raw merges both sub-predictions' value records (`second` wins on a
  * key collision).
  */
final case class Both[I, OA, OB, A <: Module[I, OA], B <: Module[I, OB]](
    first : A,
    second: B
) extends TransparentModule[I, (OA, OB)]:
  override val moduleName: String = "fanout"

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[(OA, OB)]] =
    for
      predA <- first(call)
      predB <- second(call)
    yield Prediction(
      output = (predA.output, predB.output),
      raw = RawPrediction(values = DynamicValues.mergeRecords(predA.raw.values, predB.raw.values))
    )

object Both:
  /** Same structural distribution as [[AndThen.andThenOptimizableStructure]], via [[PairOptimizableStructure]]. */
  given bothOptimizableStructure[
      I,
      OA,
      OB,
      A <: Module[I, OA],
      B <: Module[I, OB],
      NA <: Int,
      NB <: Int
  ](
      using
      pa: OptimizableStructure.WithArity[A, NA],
      pb: OptimizableStructure.WithArity[B, NB]
  ): OptimizableStructure.Of[Both[I, OA, OB, A, B], NA + NB] =
    PairOptimizableStructure.structure[Both[I, OA, OB, A, B], A, B, NA, NB](
      "Both",
      "first",
      "second",
      _.first,
      _.second,
      (program, first, second) => program.copy(first = first, second = second),
      pa,
      pb
    )
