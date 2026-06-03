package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.updated
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import zio.blocks.schema.DynamicValue

/** The untyped (data-bag) counterpart to typed [[BestOfN]]: runs an inner [[DynamicModule]] up to `n` times and
  * keeps the highest-reward [[DynamicPrediction]] (reward over the raw `DynamicValue.Record`). Shares the
  * selection loop with the typed version via [[BestOfN.selectBest]]; use this when working at the dynamic layer
  * (no static `I` / `O`). */
final case class DynamicBestOfN(
    module: DynamicModule,
    n: Int,
    rewardFn: (DynamicValue.Record, DynamicPrediction) => Double,
    threshold: Double,
    failCount: Option[Int] = None
) extends DynamicModule:
  require(n > 0, "n must be greater than 0")

  override val moduleName: String = "best_of_n"

  override protected def forward(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    val rolloutStart = input.rolloutId.getOrElse(0)
    BestOfN.selectBest[DynamicPrediction](n, threshold, failCount, moduleName)(
      runAttempt = idx =>
        module.apply(input.copy(
          rolloutId = Some(rolloutStart + idx),                                   // framework cache-busting selector
          config    = input.config.updated("temperature", DynamicValues.fromAny(1.0d))  // provider knob
        )),
      reward = prediction => BestOfN.guardedReward(moduleName)(rewardFn(input.inputs, prediction))
    )
