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

/** `split(a, b)` — run two programs left-to-right on independent inputs and pair both outputs. It is the operation
  * beneath shared-input fan-out: `fanout(a, b) = copy >>> split(a, b)`. Because `Either` failures and runtime effects
  * make that order observable, this operation is not a bifunctorial monoidal tensor on unrestricted modules. It
  * currently lives at the `Module` level; lifting it into graded `Program` composition would require a graded ordered
  * independent-input operation distinct from [[dspy4s.algebra.OrderedFanout]]. Result raw merges both sub-predictions'
  * records (`second` wins on collision).
  */
final case class Tensor[
    I,
    J,
    A,
    B,
    FA <: Module[I, A],
    FB <: Module[J, B]
](
    first : FA,
    second: FB
) extends TransparentModule[(I, J), (A, B)]:
  override val moduleName: String = "tensor"

  override protected def forward(call: ProgramCall[(I, J)])(using
      RuntimeContext
  ): Either[DspyError, Prediction[(A, B)]] =
    for
      predA <- first(call.mapInput(_._1))
      predB <- second(call.mapInput(_._2))
    yield Tensor.pairedPrediction(predA, predB)

object Tensor:
  /** The result of two programs that ran one after the other. The output is the pair of the two outputs. The raw
    * prediction merges the value records of the two raw predictions, and `second` wins on a key collision. [[Both]]
    * uses the same result.
    */
  private[compose] def pairedPrediction[A, B](first: Prediction[A], second: Prediction[B]): Prediction[(A, B)] =
    Prediction(
      output = (first.output, second.output),
      raw = RawPrediction(values = DynamicValues.mergeRecords(first.raw.values, second.raw.values))
    )

  /** Structural `read(a) ++ read(b)`, same distribution as `AndThen` / `Both`. */
  given tensorOptimizableStructure[
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
      pa: OptimizableStructure.WithArity[FA, NA],
      pb: OptimizableStructure.WithArity[FB, NB]
  ): OptimizableStructure.Of[Tensor[I, J, A, B, FA, FB], NA + NB] =
    OptimizableStructure.pair[Tensor[I, J, A, B, FA, FB], FA, FB, NA, NB](
      "Tensor",
      Some("first"),
      Some("second"),
      _.first,
      _.second,
      (program, first, second) => program.copy(first = first, second = second),
      pa,
      pb
    )
