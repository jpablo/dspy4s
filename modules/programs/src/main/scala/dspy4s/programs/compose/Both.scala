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

/** `fanout(a, b)` (compatibility name `parallel`) — run both programs on the same input and tuple their outputs. On the
  * synchronous `Either` substrate the two attempts run left-to-right and fail fast; this is Arrow-like `&&&`, not
  * concurrent execution and not by itself an `Applicative` instance. The result's raw merges both sub-predictions'
  * value records (`second` wins on a key collision).
  */
final case class Both[I, OA, OB, A <: Module[I, OA], B <: Module[I, OB]](
    first : A,
    second: B
) extends TransparentModule[I, (OA, OB)]:
  override val moduleName: String = "parallel"

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
  ): OptimizableStructure.Of[Both[I, OA, OB, A, B], NA + NB] with
    def arity(program: Both[I, OA, OB, A, B]): Int                       = pa.arity(program.first) + pb.arity(program.second)
    def inspect(program: Both[I, OA, OB, A, B]): Vector[OptimizableView] =
      PairOptimizableStructure.inspect(pa, pb)(program.first, program.second)

    def replace(program: Both[I, OA, OB, A, B], updates: Vector[OptimizableParameters]): Both[I, OA, OB, A, B] =
      PairOptimizableStructure.replace(pa, pb)(program.first, program.second, updates)((a, b) =>
        program.copy(first = a, second = b)
      )

    override def inspectNamed(program: Both[I, OA, OB, A, B]): Vector[(String, OptimizableView)] =
      PairOptimizableStructure.inspectNamed(pa, pb)(program.first, program.second)
