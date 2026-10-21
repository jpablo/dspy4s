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

import scala.concurrent.duration.Duration

/** Runs programs in parallel with a [[ParallelExecutor]] (upstream `dspy.Parallel`).
  *
  * @param timeout
  *   the time limit of the executor. The default, `Duration.Inf`, gives no time limit: upstream never fails a batch for
  *   time, and its `timeout` only submits a slow task again (`dspy/utils/parallelizer.py`, PORT_GAPS G-41).
  */
final case class Parallel(
    numThreads: Option[ThreadCount] = None,
    maxErrors : Option[ErrorLimit]  = None,
    timeout   : Duration            = Duration.Inf
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
