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
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

final case class ParallelExecutionResult[+A](
    results      : Vector[Option[A]],
    failedIndices: Vector[Int],
    errors       : Map[Int, DspyError]
)

/** Runs a task for each item of a batch on a pool of `numThreads` threads (upstream `dspy/utils/parallelizer.py`).
  *
  * @param maxErrors
  *   the number of tasks that can fail before the executor cancels the batch and gives a `Left`
  * @param timeout
  *   the longest wait for the next task that completes. When no task completes in this time, the batch is a `Left`.
  *   Upstream submits a slow task again and never fails the batch. `Duration.Inf` gives no time limit: the executor
  *   then waits for each task.
  */
final class ParallelExecutor(
    val numThreads: ThreadCount = ParallelExecutor.DefaultNumThreads,
    val maxErrors : ErrorLimit  = ErrorLimit(10),
    val timeout   : Duration    = 120.seconds
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
    if data.isEmpty then
      return Right(ParallelExecutionResult(results = Vector.empty, failedIndices = Vector.empty, errors = Map.empty))

    val captured        = ContextPropagation.captureAll
    val pool            = Executors.newFixedThreadPool(numThreads)
    val completion      = ExecutorCompletionService[(Int, Option[Either[DspyError, B]])](pool)
    val cancelRequested = AtomicBoolean(false)

    try
      def submit(index: Int): Unit =
        val _ = completion.submit(new Callable[(Int, Option[Either[DspyError, B]])]:
          override def call(): (Int, Option[Either[DspyError, B]]) =
            captured.run {
              RuntimeEnvironment.withGeneratedAsyncTask("parallel-task") {
                if cancelRequested.get() then index -> None
                else
                  val value: Either[DspyError, B] =
                    try task(data(index))
                    catch
                      case NonFatal(error) => Left(
                          RuntimeError(
                            component = "parallel_executor",
                            message = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
                          )
                        )
                  index -> Some(value)
              }
            })

      var submitted = 0
      var completed = 0
      var next      = 0

      while submitted < numThreads && next < data.size do
        submit(next)
        submitted += 1
        next += 1

      val resultBuffer      = Array.fill[Option[B]](data.size)(None)
      val failedIndices     = Vector.newBuilder[Int]
      val errors            = scala.collection.mutable.Map.empty[Int, DspyError]
      var cancelledByErrors = false
      var timedOut          = false

      while completed < submitted && !cancelledByErrors && !timedOut do
        val completedFuture = timeout match
          case limit: FiniteDuration => completion.poll(limit.toMillis, TimeUnit.MILLISECONDS)
          case _: Duration.Infinite  => completion.take()
        if completedFuture == null then
          timedOut = true
        else
          val (index, maybeOutcome) = completedFuture.get()
          completed += 1

          maybeOutcome match
            case None          => ()
            case Some(outcome) => outcome match
                case Right(value) => resultBuffer(index) = Some(value)
                case Left(error)  =>
                  failedIndices += index
                  errors.update(index, error)
                  if errors.size >= maxErrors then
                    cancelledByErrors = true
                    cancelRequested.set(true)

          if !cancelledByErrors && next < data.size then
            submit(next)
            submitted += 1
            next += 1

      if timedOut then
        Left(RuntimeError("parallel_executor", s"Execution timed out after ${timeout.toSeconds} seconds"))
      else if cancelledByErrors then
        Left(RuntimeError("parallel_executor", "Execution cancelled due to errors or interruption."))
      else
        Right(
          ParallelExecutionResult(
            results = resultBuffer.toVector,
            failedIndices = failedIndices.result().sorted,
            errors = errors.toMap
          )
        )
    catch
      case NonFatal(error) => Left(
          RuntimeError(
            component = "parallel_executor",
            message = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
          )
        )
    finally
      cancelRequested.set(true)
      val _ = pool.shutdownNow()

object ParallelExecutor:
  /** The number of threads of an executor when the caller and the context give none. */
  val DefaultNumThreads: ThreadCount = ThreadCount(8)

  /** Build an executor from the ambient context's `numThreads` / `maxErrors`, falling back to the defaults. Explicit
    * `numThreads` / `maxErrors` arguments win over the context (the resolution chain [[dspy4s.programs.Parallel]]
    * uses); the fallback constants live only here.
    */
  def fromSettings(
      timeout   : Duration            = 120.seconds,
      numThreads: Option[ThreadCount] = None,
      maxErrors : Option[ErrorLimit]  = None
  )(using RuntimeContext): ParallelExecutor =
    val ctx = summon[RuntimeContext]
    ParallelExecutor(
      numThreads = numThreads.orElse(ctx.numThreads).getOrElse(DefaultNumThreads),
      maxErrors = maxErrors.orElse(ctx.maxErrors).getOrElse(ErrorLimit(10)),
      timeout = timeout
    )
