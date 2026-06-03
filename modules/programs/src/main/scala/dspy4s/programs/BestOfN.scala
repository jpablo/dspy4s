package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.updated
import zio.blocks.schema.DynamicValue

import scala.util.control.NonFatal

final case class BestOfN(
    module: DynamicModule,
    n: Int,
    rewardFn: (DynamicValue.Record, DynamicPrediction) => Double,
    threshold: Double,
    failCount: Option[Int] = None
) extends DynamicModule:
  require(n > 0, "n must be greater than 0")

  override val moduleName: String = "best_of_n"

  override protected def forward(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    val baseContext = RuntimeEnvironment.current
    val rolloutStart = input.rolloutId.getOrElse(0)
    var remainingFailures = failCount.getOrElse(n)
    var bestReward = Double.NegativeInfinity
    var bestPrediction: Option[DynamicPrediction] = None
    var bestTrace = Vector.empty[TraceEntry]
    var bestHistory = Vector.empty[HistoryEntry]
    var lastError: Option[DspyError] = None

    var idx = 0
    while idx < n do
      val rolloutId = rolloutStart + idx
      val attemptCall = input.copy(
        rolloutId = Some(rolloutId),                       // framework cache-busting selector (typed)
        // real provider knob -> stays in the bag
        config    = input.config.updated("temperature", DynamicValues.fromAny(1.0d))
      )

      val isolated = baseContext.copy(trace = Vector.empty, history = Vector.empty)

      val (attemptResult, trace, history) = RuntimeEnvironment.withContext(isolated) {
        given RuntimeContext = RuntimeEnvironment.current
        val result = module.apply(attemptCall)
        val current = RuntimeEnvironment.current
        (result, current.trace, current.history)
      }

      attemptResult match
        case Right(prediction) =>
          val reward = evaluateReward(input.inputs, prediction)
          reward match
            case Left(error) =>
              return Left(error)
            case Right(score) =>
              if score > bestReward then
                bestReward = score
                bestPrediction = Some(prediction)
                bestTrace = trace
                bestHistory = history

              if score >= threshold then
                idx = n
              else idx += 1

        case Left(error) =>
          lastError = Some(error)
          if idx > remainingFailures then return Left(error)
          remainingFailures -= 1
          idx += 1

    bestPrediction match
      case Some(prediction) =>
        bestTrace.foreach(RuntimeEnvironment.appendTrace)
        bestHistory.foreach(RuntimeEnvironment.appendHistory)
        Right(prediction)
      case None =>
        Left(lastError.getOrElse(RuntimeError("best_of_n", "No successful predictions were produced")))

  private def evaluateReward(
      inputs: DynamicValue.Record,
      prediction: DynamicPrediction
  ): Either[DspyError, Double] =
    try Right(rewardFn(inputs, prediction))
    catch
      case NonFatal(error) =>
        Left(
          RuntimeError(
            "best_of_n",
            s"Reward function failed: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
          )
        )

