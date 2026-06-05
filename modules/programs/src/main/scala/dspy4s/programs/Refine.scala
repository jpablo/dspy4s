package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue

/** Typed `Refine`: the same best-of-`n` selection as [[BestOfN]], exposed under DSPy's `Refine` name.
  * Output-preserving (`Module[TypedCall[I], Prediction[O]]`); delegates to an inner typed [[BestOfN]]. */
final case class Refine[I, O](
    module: Module[TypedCall[I], Prediction[O]],
    n: Int,
    rewardFn: (I, Prediction[O]) => Double,
    threshold: Double,
    failCount: Option[Int] = None
) extends Module[TypedCall[I], Prediction[O]]:
  private val bestOfN = BestOfN(
    module    = module,
    n         = n,
    rewardFn  = rewardFn,
    threshold = threshold,
    failCount = failCount
  )

  override val moduleName: String = "refine"

  override protected def callInputs(call: TypedCall[I]): DynamicValue.Record = DynamicValue.Record.empty
  override protected def callTraceEnabled(call: TypedCall[I]): Boolean       = call.traceEnabled
  override protected def tracePayload(prediction: Prediction[O]): DynamicValue.Record = prediction.raw.values

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    bestOfN.apply(call)

  /** Convenience entry mirroring the typed caller signature; builds a [[TypedCall]] and dispatches through the
    * wrapped [[apply]]. */
  def apply(
      input: I,
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    apply(TypedCall(input, config, traceEnabled))
