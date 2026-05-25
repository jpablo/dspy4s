package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeContextData
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall

import scala.util.control.NonFatal

final case class BestOfN(
    module: PredictProgram,
    n: Int,
    rewardFn: (Map[String, Any], DynamicPrediction) => Double,
    threshold: Double,
    failCount: Option[Int] = None
) extends PredictProgram:
  require(n > 0, "n must be greater than 0")

  override val moduleName: String = "best_of_n"

  override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    val baseContext = RuntimeEnvironment.current
    val rolloutStart = readRolloutId(input.config)
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
        config = input.config
          .updated("rollout_id", rolloutId)
          .updated("temperature", 1.0d)
      )

      val isolated = RuntimeContextData(
        settings = baseContext.settings,
        callbacks = baseContext.callbacks
      )

      val (attemptResult, trace, history) = RuntimeEnvironment.withContext(isolated) {
        given RuntimeContext = RuntimeEnvironment.current
        val result = module.run(attemptCall)
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
      inputs: Map[String, Any],
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

  private def readRolloutId(config: Map[String, Any]): Int =
    config.get("rollout_id") match
      case Some(value: Int)    => value
      case Some(value: Long)   => value.toInt
      case Some(value: Short)  => value.toInt
      case Some(value: Double) => value.toInt
      case Some(value: Float)  => value.toInt
      case _                   => 0
