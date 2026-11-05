package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeDelta
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.ParallelExecutionResult
import dspy4s.programs.runtime.ParallelExecutor
import zio.blocks.schema.DynamicValue

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.util.Try

/** Runs programs in parallel with a [[ParallelExecutor]] (upstream `dspy.Parallel`).
  *
  * The trace and the history of each task reach the context of the caller, in the order of the tasks, also when the
  * executor gives a `Left`. In upstream, the workers add to one shared trace list. A task runs in an isolated context,
  * and the caller adds its output after the executor returns, so that the order does not depend on the threads. A task
  * that the executor did not start has no output. [[ParallelExecutor]] itself does not do this, because `Evaluate` and
  * GEPA drop the output of their workers on purpose.
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
  def run(
      tasks: Vector[(DynamicModule, ProgramCall[DynamicValue.Record])]
  )(using RuntimeContext): Either[DspyError, ParallelExecutionResult[RawPrediction]] =
    // The output of each task, by the index of the task. A slot is empty for a task that did not start.
    val outputs = TrieMap.empty[Int, RuntimeDelta]
    val result  = ParallelExecutor
      .fromSettings(timeout = timeout, numThreads = numThreads, maxErrors = maxErrors)
      .executeEither[Int, RawPrediction](
        task = (index: Int) =>
          val (module, call) = tasks(index)
          // `Try` keeps the output of a task that throws. `get` gives the exception to the executor, which counts it.
          val executed = RuntimeEnvironment.isolatedAttempt(RuntimeEnvironment.current)(Try(module(call).map(_.raw)))
          outputs.update(index, executed.delta)
          executed.value.get
        ,
        data = tasks.indices.toVector
      )
    tasks.indices.foreach(index => outputs.get(index).foreach(RuntimeEnvironment.propagate))
    result
