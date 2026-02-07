package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall

final case class Refine(
    module: PredictProgram,
    n: Int,
    rewardFn: (Map[String, Any], Prediction) => Double,
    threshold: Double,
    failCount: Option[Int] = None
) extends PredictProgram:
  private val bestOfN = BestOfN(
    module = module,
    n = n,
    rewardFn = rewardFn,
    threshold = threshold,
    failCount = failCount
  )

  override val moduleName: String = "refine"

  override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, Prediction] =
    bestOfN.run(input)
