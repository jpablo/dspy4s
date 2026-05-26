package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.ParallelExecutionResult
import dspy4s.programs.runtime.ParallelExecutor

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

final case class Parallel(
    numThreads: Option[Int] = None,
    maxErrors: Option[Int] = None,
    timeout: FiniteDuration = 120.seconds
):
  private def resolvedExecutor(using RuntimeContext): ParallelExecutor =
    val ctx = summon[RuntimeContext]
    ParallelExecutor(
      numThreads = numThreads.getOrElse(ctx.numThreads.getOrElse(8)),
      maxErrors = maxErrors.getOrElse(ctx.maxErrors.getOrElse(10)),
      timeout = timeout
    )

  def run(
      tasks: Vector[(PredictProgram, ProgramCall)]
  )(using RuntimeContext): Either[DspyError, ParallelExecutionResult[DynamicPrediction]] =
    resolvedExecutor.executeEither[(PredictProgram, ProgramCall), DynamicPrediction](
      task = (pair: (PredictProgram, ProgramCall)) => pair._1.run(pair._2),
      data = tasks
    )

  def apply(
      tasks: Vector[(PredictProgram, ProgramCall)]
  )(using RuntimeContext): Either[DspyError, ParallelExecutionResult[DynamicPrediction]] =
    run(tasks)
