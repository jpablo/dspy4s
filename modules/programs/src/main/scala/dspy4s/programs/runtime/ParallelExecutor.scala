package dspy4s.programs.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.runtime.ContextPropagation

import java.util.concurrent.Executors
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
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
    val captured = ContextPropagation.capture
    val pool = Executors.newFixedThreadPool(numThreads)
    val baseEc: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(pool)
    given ExecutionContext = ContextPropagation.wrapExecutionContext(baseEc, captured)

    try
      val indexed = data.zipWithIndex.map { (item, index) =>
        ContextPropagation.future {
          val value: Either[DspyError, B] =
            try Right(task(item))
            catch
              case NonFatal(error) =>
                Left(
                  RuntimeError(
                    component = "parallel_executor",
                    message = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
                  )
                )
          index -> value
        }
      }

      val completed = Await.result(Future.sequence(indexed), timeout).sortBy(_._1)

      val resultBuffer = Array.fill[Option[B]](data.size)(None)
      val failedIndices = Vector.newBuilder[Int]
      val errors = scala.collection.mutable.Map.empty[Int, DspyError]

      completed.foreach { (index, outcome) =>
        outcome match
          case Right(value) =>
            resultBuffer(index) = Some(value)
          case Left(error) =>
            failedIndices += index
            errors.update(index, error)
      }

      val errorMap = errors.toMap
      if errorMap.size >= maxErrors then
        Left(RuntimeError("parallel_executor", "Execution cancelled due to errors or interruption."))
      else
        Right(
          ParallelExecutionResult(
            results = resultBuffer.toVector,
            failedIndices = failedIndices.result().sorted,
            errors = errorMap
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
      baseEc.shutdown()

object ParallelExecutor:
  def fromSettings(timeout: FiniteDuration = 120.seconds)(using RuntimeContext): ParallelExecutor =
    val settings = summon[RuntimeContext].settings
    val numThreads = settings.get(SettingKeys.numThreads).getOrElse(8)
    val maxErrors = settings.get(SettingKeys.maxErrors).getOrElse(10)
    ParallelExecutor(numThreads = numThreads, maxErrors = maxErrors, timeout = timeout)
