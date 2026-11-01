package dspy4s.programs.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/** The result of a batch that completed: one entry for each item, in the order of the items. A `Left` is the error of
  * the task of that item.
  */
final case class ParallelExecutionResult[+A](results: Vector[Either[DspyError, A]])

/** Runs a task for each item of a batch on a pool of `numThreads` threads (upstream `dspy/utils/parallelizer.py`).
  *
  * @param maxErrors
  *   the number of tasks that can fail before the executor cancels the batch and gives a `Left`
  * @param timeout
  *   the longest wait for the next task that completes. When no task completes in this time, the batch is a `Left`. The
  *   default, `Duration.Inf`, gives no time limit: the executor then waits for each task. Upstream never fails a batch
  *   for time: its `timeout` (120 seconds) only submits a slow task again (`dspy/utils/parallelizer.py`). The port does
  *   not submit a task again (PORT_GAPS G-41).
  */
final class ParallelExecutor(
    val numThreads: ThreadCount = ParallelExecutor.DefaultNumThreads,
    val maxErrors : ErrorLimit  = ParallelExecutor.DefaultMaxErrors,
    val timeout   : Duration    = Duration.Inf
):

  // The workers run in the effective context of the call: the given context, filled from the installed one (decision
  // D8 of UPGRADE-3.2.1-to-3.4.0: the given context wins). `ContextPropagation.captureAll` reads the installed
  // context, so each entry point installs the given context first. A task reads `RuntimeEnvironment.current` on its
  // worker to get that context.
  def execute[A, B](
      task: A => B,
      data: Vector[A]
  )(using ctx: RuntimeContext): Either[DspyError, ParallelExecutionResult[B]] =
    // executeInternal already wraps each task call in a try/catch that turns a throw into the same
    // `RuntimeError`, so a total `task` only needs lifting into `Right` — no second catch here.
    RuntimeEnvironment.withInstalled(ctx)(executeInternal(task = (item: A) => Right(task(item)), data = data))

  def executeEither[A, B](
      task: A => Either[DspyError, B],
      data: Vector[A]
  )(using ctx: RuntimeContext): Either[DspyError, ParallelExecutionResult[B]] =
    RuntimeEnvironment.withInstalled(ctx)(executeInternal(task = task, data = data))

  private def executeInternal[A, B](
      task: A => Either[DspyError, B],
      data: Vector[A]
  ): Either[DspyError, ParallelExecutionResult[B]] =
    if data.isEmpty then return Right(ParallelExecutionResult(Vector.empty))

    val captured        = ContextPropagation.captureAll
    val pool            = Executors.newFixedThreadPool(numThreads)
    val completion      = ExecutorCompletionService[(Int, Either[DspyError, B])](pool)
    val cancelRequested = AtomicBoolean(false)
    val cancelled       = RuntimeError("parallel_executor", "Execution cancelled due to errors or interruption.")

    def thrown(error: Throwable): RuntimeError =
      RuntimeError(component = "parallel_executor", message = DspyError.messageOf(error))

    try
      def submit(index: Int): Unit =
        val _ = completion.submit(new Callable[(Int, Either[DspyError, B])]:
          override def call(): (Int, Either[DspyError, B]) =
            captured.runAsTask("parallel-task") {
              // The loop below reads no result after the cancel request, so this `Left` is not visible.
              if cancelRequested.get() then index -> Left(cancelled)
              else
                val value: Either[DspyError, B] =
                  try task(data(index))
                  catch
                    case NonFatal(error) => Left(thrown(error))
                index -> value
            })

      var completed = 0
      var next      = 0

      while next < numThreads && next < data.size do
        submit(next)
        next += 1

      val results                    = scala.collection.mutable.Map.empty[Int, Either[DspyError, B]]
      var failures                   = 0
      var stopped: Option[DspyError] = None

      while completed < next && stopped.isEmpty do
        val completedFuture = timeout match
          case limit: FiniteDuration => completion.poll(limit.toMillis, TimeUnit.MILLISECONDS)
          case _: Duration.Infinite  => completion.take()
        if completedFuture == null then
          stopped = Some(RuntimeError("parallel_executor", s"Execution timed out after ${timeout.toSeconds} seconds"))
        else
          val (index, outcome) = completedFuture.get()
          completed += 1
          results.update(index, outcome)

          if outcome.isLeft then
            failures += 1
            if failures >= maxErrors then
              stopped = Some(cancelled)
              cancelRequested.set(true)

          if stopped.isEmpty && next < data.size then
            submit(next)
            next += 1

      stopped.toLeft(ParallelExecutionResult(Vector.tabulate(data.size)(results)))
    catch
      case NonFatal(error) => Left(thrown(error))
    finally
      cancelRequested.set(true)
      val _ = pool.shutdownNow()

object ParallelExecutor:
  /** The number of threads of an executor when the caller and the context give none. */
  val DefaultNumThreads: ThreadCount = ThreadCount(8)

  /** The number of failed tasks that stops a batch when the caller and the context give no limit. */
  val DefaultMaxErrors: ErrorLimit = ErrorLimit(10)

  /** Build an executor from the ambient context's `numThreads` / `maxErrors`, falling back to the defaults. Explicit
    * `numThreads` / `maxErrors` arguments win over the context (the resolution chain [[dspy4s.programs.Parallel]]
    * uses); the fallback constants live only here. The default `timeout` gives no time limit, as for the constructor.
    */
  def fromSettings(
      timeout   : Duration            = Duration.Inf,
      numThreads: Option[ThreadCount] = None,
      maxErrors : Option[ErrorLimit]  = None
  )(using RuntimeContext): ParallelExecutor =
    val ctx = summon[RuntimeContext]
    ParallelExecutor(
      numThreads = numThreads.orElse(ctx.numThreads).getOrElse(DefaultNumThreads),
      maxErrors = maxErrors.orElse(ctx.maxErrors).getOrElse(DefaultMaxErrors),
      timeout = timeout
    )
