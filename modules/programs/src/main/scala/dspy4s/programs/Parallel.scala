package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.data.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ThreadCount
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.ParallelExecutionResult
import dspy4s.programs.runtime.ParallelExecutor
import zio.blocks.schema.DynamicValue

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

final case class Parallel(
    numThreads: Option[ThreadCount] = None,
    maxErrors: Option[ErrorLimit] = None,
    timeout: FiniteDuration = 120.seconds
):
  private def resolvedExecutor(using RuntimeContext): ParallelExecutor =
    val ctx = summon[RuntimeContext]
    ParallelExecutor(
      numThreads = numThreads.getOrElse(ctx.numThreads.getOrElse(ThreadCount(8))),
      maxErrors = maxErrors.getOrElse(ctx.maxErrors.getOrElse(ErrorLimit(10))),
      timeout = timeout
    )

  def run(
      tasks: Vector[(DynamicModule, ProgramCall[DynamicValue.Record])]
  )(using RuntimeContext): Either[DspyError, ParallelExecutionResult[DynamicPrediction]] =
    resolvedExecutor.executeEither[(DynamicModule, ProgramCall[DynamicValue.Record]), DynamicPrediction](
      task = (pair: (DynamicModule, ProgramCall[DynamicValue.Record])) => pair._1.apply(pair._2),
      data = tasks
    )

  def apply(
      tasks: Vector[(DynamicModule, ProgramCall[DynamicValue.Record])]
  )(using RuntimeContext): Either[DspyError, ParallelExecutionResult[DynamicPrediction]] =
    run(tasks)
