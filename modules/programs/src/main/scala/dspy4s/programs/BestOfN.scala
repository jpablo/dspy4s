package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.updated
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.programs.runtime.AttemptSelection
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue

/** Typed `BestOfN`: runs an inner typed program up to `n` times and keeps the highest-reward `Prediction[O]`,
  * short-circuiting once `rewardFn` reaches `threshold`. Output-preserving — it returns the inner program's `O`
  * unchanged (a `Module[TypedCall[I], Prediction[O]]`), so it composes around any typed program (`Predict`,
  * `ChainOfThought`, …). The repeated samples are made distinct by a per-attempt `rolloutId` (cache-busting);
  * `failCount` bounds tolerated failures before giving up (defaults to `n`).
  *
  * `callInputs` is empty: a generic wrapper has no `Signature` to encode `I` for its own trace entry, so the
  * meaningful inputs live on the nested inner program's event. The best-of-`n` selection loop lives in
  * [[dspy4s.programs.runtime.AttemptSelection.bestOf]] (generic over the attempt result), shared with [[Refine]];
  * `BestOfN` is its independent-samples instance (no inter-attempt feedback). */
final case class BestOfN[I, O](
    module: Module[TypedCall[I], Prediction[O]],
    n: Int,
    rewardFn: (I, Prediction[O]) => Double,
    threshold: Double,
    failCount: Option[Int] = None
) extends Module[TypedCall[I], Prediction[O]]:
  require(n > 0, "n must be greater than 0")

  override val moduleName: String = "best_of_n"

  override protected def callInputs(call: TypedCall[I]): DynamicValue.Record = DynamicValue.Record.empty
  override protected def callTraceEnabled(call: TypedCall[I]): Boolean       = call.traceEnabled
  override protected def tracePayload(prediction: Prediction[O]): DynamicValue.Record = prediction.raw.values

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val rolloutStart = call.rolloutId.getOrElse(0)
    AttemptSelection.bestOf[Prediction[O]](n, threshold, failCount, moduleName)(
      runAttempt = idx =>
        module.apply(call.copy(
          rolloutId = Some(rolloutStart + idx),                                  // framework cache-busting selector
          config    = call.config.updated("temperature", DynamicValues.fromAny(1.0d))  // provider knob
        )),
      reward = prediction => AttemptSelection.guardedReward(moduleName)(rewardFn(call.input, prediction))
    )

  /** Convenience entry mirroring the typed caller signature; builds a [[TypedCall]] and dispatches through the
    * wrapped [[apply]]. */
  def apply(
      input: I,
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    apply(TypedCall(input, config, traceEnabled))
