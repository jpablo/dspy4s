package dspy4s.programs.compose

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.data.RawPrediction
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.TransparentModule
import dspy4s.programs.optimization.OptimizableTraversal
import dspy4s.typed.Prediction

/** `copy`: duplicate the input `I` into `(I, I)`. Parameter-free (like `id`); the first half of a fan-out, so
  * `parallel(a, b) = copy >>> tensor(a, b)`. Copy commutes with deterministic programs but not effect-observing
  * programs; this is a useful classifier rather than a law of the unrestricted execution carrier.
  */
final case class Copy[I]() extends TransparentModule[I, (I, I)]:
  override val moduleName: String = "copy"
  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[(I, I)]] =
    Right(Prediction((call.input, call.input), RawPrediction.empty))

object Copy:
  given copyOptimizableTraversal[I]: OptimizableTraversal.WithArity[Copy[I], 0] =
    OptimizableTraversal.empty

/** `discard`: drop the input, producing `()`. Parameter-free. Although `f >>> discard` and `discard` return the same
  * value, the former still runs `f` and can fail, spend tokens, or invoke tools. No naturality law is claimed for
  * unrestricted executable programs.
  */
final case class Discard[I]() extends TransparentModule[I, Unit]:
  override val moduleName: String = "discard"
  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[Unit]] =
    Right(Prediction((), RawPrediction.empty))

object Discard:
  given discardOptimizableTraversal[I]: OptimizableTraversal.WithArity[Discard[I], 0] =
    OptimizableTraversal.empty

/** `swap`: exchange two components. Parameter-free and involutive (`swap >>> swap = id`) as a structural value
  * transformation; it does not make ordered effectful execution symmetric.
  */
final case class Swap[I, J]() extends TransparentModule[(I, J), (J, I)]:
  override val moduleName: String = "swap"
  override protected def forward(call: ProgramCall[(I, J)])(using
      RuntimeContext
  ): Either[DspyError, Prediction[(J, I)]] =
    val (i, j) = call.input
    Right(Prediction((j, i), RawPrediction.empty))

object Swap:
  given swapOptimizableTraversal[I, J]: OptimizableTraversal.WithArity[Swap[I, J], 0] =
    OptimizableTraversal.empty
