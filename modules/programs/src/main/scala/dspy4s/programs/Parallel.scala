package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.data.RawPrediction
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
    maxErrors : Option[ErrorLimit]  = None,
    timeout   : FiniteDuration      = 120.seconds
):
  private def resolvedExecutor(using RuntimeContext): ParallelExecutor =
    ParallelExecutor.fromSettings(timeout = timeout, numThreads = numThreads, maxErrors = maxErrors)

  def run(
      tasks: Vector[(DynamicModule, ProgramCall[DynamicValue.Record])]
  )(using RuntimeContext): Either[DspyError, ParallelExecutionResult[RawPrediction]] =
    resolvedExecutor.executeEither[(DynamicModule, ProgramCall[DynamicValue.Record]), RawPrediction](
      task = (pair: (DynamicModule, ProgramCall[DynamicValue.Record])) => pair._1(pair._2).map(_.raw),
      data = tasks
    )

  def apply(
      tasks: Vector[(DynamicModule, ProgramCall[DynamicValue.Record])]
  )(using RuntimeContext): Either[DspyError, ParallelExecutionResult[RawPrediction]] =
    run(tasks)
