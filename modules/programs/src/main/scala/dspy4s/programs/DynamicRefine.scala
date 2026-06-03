package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import zio.blocks.schema.DynamicValue

/** The untyped counterpart to typed [[Refine]]: best-of-`n` selection over a [[DynamicModule]], delegating to an
  * inner [[DynamicBestOfN]]. */
final case class DynamicRefine(
    module: DynamicModule,
    n: Int,
    rewardFn: (DynamicValue.Record, DynamicPrediction) => Double,
    threshold: Double,
    failCount: Option[Int] = None
) extends DynamicModule:
  private val bestOfN = DynamicBestOfN(
    module    = module,
    n         = n,
    rewardFn  = rewardFn,
    threshold = threshold,
    failCount = failCount
  )

  override val moduleName: String = "refine"

  override protected def forward(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    bestOfN.apply(input)
