package dspy4s.programs.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.nowarn
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

final case class ParallelExecutionResult[+A](
    results: Vector[Option[A]],
    failedIndices: Vector[Int],
    errors: Map[Int, DspyError]
)

final class ParallelExecutor(
    numThreads: Int = 8,
    maxErrors: Int = 10,
    timeout: FiniteDuration = 120.seconds
):
  require(numThreads > 0, "numThreads must be greater than 0")
  require(maxErrors > 0, "maxErrors must be greater than 0")

  def execute[A, B](
      task: A => B,
      data: Vector[A]
  )(using RuntimeContext): Either[DspyError, ParallelExecutionResult[B]] =
    // executeInternal already wraps each task call in a try/catch that turns a throw into the same
    // `RuntimeError`, so a total `task` only needs lifting into `Right` — no second catch here.
    executeInternal(task = (item: A) => Right(task(item)), data = data)

  def executeEither[A, B](
      task: A => Either[DspyError, B],
      data: Vector[A]
  )(using RuntimeContext): Either[DspyError, ParallelExecutionResult[B]] =
    executeInternal(task = task, data = data)

  @nowarn("msg=unused")
  private def executeInternal[A, B](
      task: A => Either[DspyError, B],
      data: Vector[A]
  )(using RuntimeContext): Either[DspyError, ParallelExecutionResult[B]] =
    if data.isEmpty then
      return Right(ParallelExecutionResult(results = Vector.empty, failedIndices = Vector.empty, errors = Map.empty))

    val captured = ContextPropagation.capture
    val pool = Executors.newFixedThreadPool(numThreads)
    val completion = ExecutorCompletionService[(Int, Option[Either[DspyError, B]])](pool)
    val cancelRequested = AtomicBoolean(false)

    try
      def submit(index: Int): Unit =
        val _ = completion.submit(new Callable[(Int, Option[Either[DspyError, B]])]:
          override def call(): (Int, Option[Either[DspyError, B]]) =
            RuntimeEnvironment.withContext(captured) {
              RuntimeEnvironment.withGeneratedAsyncTask("parallel-task") {
                if cancelRequested.get() then index -> None
                else
                  val value: Either[DspyError, B] =
                    try task(data(index))
                    catch
                      case NonFatal(error) =>
                        Left(
                          RuntimeError(
                            component = "parallel_executor",
                            message = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
                          )
                        )
                  index -> Some(value)
              }
            }
        )

      var submitted = 0
      var completed = 0
      var next = 0

      while submitted < numThreads && next < data.size do
        submit(next)
        submitted += 1
        next += 1

      val resultBuffer = Array.fill[Option[B]](data.size)(None)
      val failedIndices = Vector.newBuilder[Int]
      val errors = scala.collection.mutable.Map.empty[Int, DspyError]
      var cancelledByErrors = false
      var timedOut = false

      while completed < submitted && !cancelledByErrors && !timedOut do
        val completedFuture = completion.poll(timeout.toMillis, TimeUnit.MILLISECONDS)
        if completedFuture == null then
          timedOut = true
        else
          val (index, maybeOutcome) = completedFuture.get()
          completed += 1

          maybeOutcome match
            case None => ()
            case Some(outcome) =>
              outcome match
                case Right(value) =>
                  resultBuffer(index) = Some(value)
                case Left(error) =>
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
      case NonFatal(error) =>
        Left(
          RuntimeError(
            component = "parallel_executor",
            message = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
          )
        )
    finally
      cancelRequested.set(true)
      val _ = pool.shutdownNow()

object ParallelExecutor:
  def fromSettings(timeout: FiniteDuration = 120.seconds)(using RuntimeContext): ParallelExecutor =
    val ctx = summon[RuntimeContext]
    ParallelExecutor(
      numThreads = ctx.numThreads.getOrElse(8),
      maxErrors  = ctx.maxErrors.getOrElse(10),
      timeout    = timeout
    )
