package dspy4s.programs.compose

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.data.RawPrediction
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.TransparentModule
import dspy4s.programs.optimization.*
import dspy4s.typed.Prediction

import scala.compiletime.ops.int.+

/** `split(a, b)` (compatibility name `tensor`) — run two programs left-to-right on independent inputs and pair both
  * outputs. It is the operation beneath shared-input fan-out: `fanout(a, b) = copy >>> split(a, b)`. Because `Either`
  * failures and runtime effects make that order observable, this operation is not a bifunctorial monoidal tensor on
  * unrestricted modules. Unlike `parallel` it does NOT lift into packaged `Program`/`OrderedFanout`: its input `(I, J)`
  * has no canonical single-record decoder (fan-out reuses the shared input's decoder; the tensor's two inputs don't),
  * so it lives at the `Module` level. Result raw merges both sub-predictions' records (`second` wins on collision).
  */
final case class Tensor[
    I,
    J,
    A,
    B,
    FA <: Module[I, A],
    FB <: Module[J, B]
](
    first: FA,
    second: FB
) extends TransparentModule[(I, J), (A, B)]:
  override val moduleName: String = "tensor"

  override protected def forward(call: ProgramCall[(I, J)])(using
      RuntimeContext
  ): Either[DspyError, Prediction[(A, B)]] =
    for
      predA <- first(call.mapInput(_._1))
      predB <- second(call.mapInput(_._2))
    yield Prediction(
      output = (predA.output, predB.output),
      raw = RawPrediction(values = DynamicValues.mergeRecords(predA.raw.values, predB.raw.values))
    )

object Tensor:
  /** Structural `read(a) ++ read(b)`, same distribution as `AndThen` / `Both` (via [[PairOptimizableTraversal]]). */
  given tensorOptimizableTraversal[
      I,
      J,
      A,
      B,
      FA <: Module[I, A],
      FB <: Module[J, B],
      NA <: Int,
      NB <: Int
  ](
      using
      pa: OptimizableTraversal.WithArity[FA, NA],
      pb: OptimizableTraversal.WithArity[FB, NB]
  ): OptimizableTraversal.Of[Tensor[I, J, A, B, FA, FB], NA + NB] with
    def arity(program: Tensor[I, J, A, B, FA, FB]): Int = pa.arity(program.first) + pb.arity(program.second)
    def inspect(program: Tensor[I, J, A, B, FA, FB]): Vector[OptimizableView] =
      PairOptimizableTraversal.inspect(pa, pb)(program.first, program.second)

    def replace(
        program: Tensor[I, J, A, B, FA, FB],
        updates: Vector[OptimizableParameters]
    ): Tensor[I, J, A, B, FA, FB] =
      PairOptimizableTraversal.replace(pa, pb)(program.first, program.second, updates)((a, b) =>
        program.copy(first = a, second = b)
      )

    override def inspectNamed(program: Tensor[I, J, A, B, FA, FB]): Vector[(String, OptimizableView)] =
      PairOptimizableTraversal.inspectNamed(pa, pb)(program.first, program.second)
