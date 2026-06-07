package dspy4s.evaluate

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.evaluate.contracts.EvaluationResult
import dspy4s.evaluate.contracts.Evaluator
import dspy4s.evaluate.contracts.ExampleEvaluation
import dspy4s.evaluate.contracts.Metric
import dspy4s.programs.runtime.ParallelExecutor

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt

final case class EvaluateConfig(
    devset: Vector[Example],
    metric: Metric,
    numThreads: Option[Int] = None,
    maxErrors: Option[Int] = None,
    failureScore: Double = 0.0,
    displayProgress: Boolean = false,
    displayTable: Either[Boolean, Int] = Left(false),
    saveAsCsv: Option[String] = None,
    saveAsJson: Option[String] = None,
    provideTraceback: Boolean = false,
    timeout: FiniteDuration = 120.seconds
)

final class Evaluate(config: EvaluateConfig) extends Evaluator:

  def apply(
      metric: Option[Metric] = None,
      devset: Option[Vector[Example]] = None,
      numThreads: Option[Int] = None,
      maxErrors: Option[Int] = None,
      failureScore: Option[Double] = None,
      saveAsCsv: Option[String] = None,
      saveAsJson: Option[String] = None
  )(program: Example => Either[DspyError, DynamicPrediction])(using RuntimeContext): Either[DspyError, EvaluationResult] =
    val mergedConfig = EvaluateConfig(
      devset = devset.getOrElse(config.devset),
      metric = metric.getOrElse(config.metric),
      numThreads = numThreads.orElse(config.numThreads),
      maxErrors = maxErrors.orElse(config.maxErrors),
      failureScore = failureScore.getOrElse(config.failureScore),
      displayProgress = config.displayProgress,
      displayTable = config.displayTable,
      saveAsCsv = saveAsCsv.orElse(config.saveAsCsv),
      saveAsJson = saveAsJson.orElse(config.saveAsJson),
      provideTraceback = config.provideTraceback,
      timeout = config.timeout
    )
    applyInternal(program, mergedConfig)

  private def applyInternal(
      fn: Example => Either[DspyError, DynamicPrediction],
      cfg: EvaluateConfig
  )(using RuntimeContext): Either[DspyError, EvaluationResult] =
    val dataset = cfg.devset
    val metric = cfg.metric

    if dataset.isEmpty then
      return Right(
        EvaluationResult(score = 0.0, results = Vector.empty, metricName = metric.name)
      )

    val executor = buildExecutor(cfg)

    executor.executeEither[Example, (DynamicPrediction, Double)](
      task = (ex: Example) =>
        // Runs on a parallel-executor worker thread; the ambient context is restored into the thread-local
        // there, so `RuntimeEnvironment.current` is the right `RuntimeContext` to hand the metric (so
        // LM-judged metrics can reach the LM).
        given RuntimeContext = dspy4s.core.runtime.RuntimeEnvironment.current
        fn(ex).flatMap { prediction =>
          metric.score(ex, prediction).map(score => (prediction, score))
        },
      data = dataset
    ).map { execResult =>
      val evaluations = dataset.indices.map { idx =>
        execResult.results(idx) match
          case Some((prediction, score)) =>
            ExampleEvaluation(dataset(idx), prediction, score)
          case None =>
            val capturedError =
              if cfg.provideTraceback then
                execResult.errors.get(idx).map(err => s"[${err.code}] ${err.message}")
              else None
            ExampleEvaluation(dataset(idx), DynamicPrediction.empty, cfg.failureScore, error = capturedError)
      }.toVector

      val totalScore = evaluations.map(_.score).sum
      val aggregate = if evaluations.nonEmpty then (totalScore / evaluations.size) * 100.0 else 0.0
      val result = EvaluationResult(
        score = aggregate,
        results = evaluations,
        metricName = metric.name,
        metadata = Map(
          "num_threads" -> cfg.numThreads.getOrElse("default"),
          "max_errors" -> cfg.maxErrors.getOrElse("default"),
          "devset_size" -> dataset.size
        )
      )

      cfg.saveAsJson.foreach(path => dspy4s.evaluate.EvaluationResultPersistence.saveAsJson(result, path))
      cfg.saveAsCsv.foreach(path => dspy4s.evaluate.EvaluationResultPersistence.saveAsCsv(result, path))

      if cfg.displayProgress then
        println(f"[Evaluate] score=${aggregate}%.2f%% on ${dataset.size} examples using metric '${metric.name}'")

      tableLimit(cfg.displayTable).foreach(limit => println(result.renderTable(limit)))

      result
    }

  override def evaluate(
      predict: Example => Either[DspyError, DynamicPrediction],
      dataset: Vector[Example],
      metric: Metric
  )(using RuntimeContext): Either[DspyError, EvaluationResult] =
    applyInternal(
      predict,
      config.copy(devset = dataset, metric = metric)
    )

  /** Resolves the `displayTable` config into an optional row limit to pass to `renderTable`.
    *   - `Left(false)` -> `None` (no table)
    *   - `Left(true)`  -> `Some(None)` (render all rows)
    *   - `Right(n)`    -> `Some(Some(n))` (render at most n rows)
    */
  private def tableLimit(spec: Either[Boolean, Int]): Option[Option[Int]] =
    spec match
      case Left(false) => None
      case Left(true)  => Some(None)
      case Right(n)    => Some(Some(n))

  private def buildExecutor(cfg: EvaluateConfig)(using RuntimeContext): ParallelExecutor =
    val ctx = summon[RuntimeContext]
    val resolvedNumThreads = cfg.numThreads.getOrElse(ctx.numThreads.getOrElse(8))
    val resolvedMaxErrors  = cfg.maxErrors.getOrElse(ctx.maxErrors.getOrElse(10))
    ParallelExecutor(
      numThreads = resolvedNumThreads,
      maxErrors = math.max(1, resolvedMaxErrors),
      timeout = cfg.timeout
    )

object Evaluate:
  def apply(
      devset: Vector[Example],
      metric: Metric,
      numThreads: Option[Int] = None,
      maxErrors: Option[Int] = None,
      failureScore: Double = 0.0,
      displayProgress: Boolean = false,
      displayTable: Int = 0,
      saveAsCsv: Option[String] = None,
      saveAsJson: Option[String] = None,
      timeout: FiniteDuration = 120.seconds
  ): Evaluate =
    val displaySpec: Either[Boolean, Int] = if displayTable > 0 then Right(displayTable) else Left(false)
    new Evaluate(
      EvaluateConfig(
        devset = devset,
        metric = metric,
        numThreads = numThreads,
        maxErrors = maxErrors,
        failureScore = failureScore,
        displayProgress = displayProgress,
        displayTable = displaySpec,
        saveAsCsv = saveAsCsv,
        saveAsJson = saveAsJson,
        timeout = timeout
      )
    )
