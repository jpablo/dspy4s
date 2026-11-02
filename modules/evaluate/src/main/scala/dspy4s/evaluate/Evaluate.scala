package dspy4s.evaluate

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.contracts.PythonText
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.contracts.ValidationError
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.contracts.EvaluationResult
import dspy4s.evaluate.contracts.Evaluator
import dspy4s.evaluate.contracts.ExampleEvaluation
import dspy4s.evaluate.contracts.Metric
import dspy4s.programs.runtime.ParallelExecutor
import zio.blocks.schema.DynamicValue

import scala.concurrent.duration.Duration

/** The table that a run of [[Evaluate]] prints (upstream `display_table`). */
enum TableDisplay derives CanEqual:

  /** No table. */
  case Off

  /** A table of each row. */
  case All

  /** A table of the first `count` rows, and a line that says how many rows it hides. A count of 0 gives no table, as
    * upstream reads 0 as false.
    */
  case Rows(count: Int)

final case class EvaluateConfig(
    devset         : Vector[Example],
    metric         : Metric,
    numThreads     : Option[ThreadCount] = None,
    maxErrors      : Option[ErrorLimit]  = None,
    failureScore   : Double              = 0.0,
    displayProgress: Boolean             = false,
    displayTable   : TableDisplay        = TableDisplay.Off,
    saveAsCsv      : Option[String]      = None,
    saveAsJson     : Option[String]      = None,
    /** The time limit of the [[ParallelExecutor]] of a run. The default, `Duration.Inf`, gives no time limit: upstream
      * `Evaluate` never fails for time, because the `timeout` of its executor only submits a slow example again
      * (`dspy/utils/parallelizer.py`, PORT_GAPS G-41).
      */
    timeout: Duration = Duration.Inf,
    /** Metadata carried into the evaluation scope's [[RuntimeContext.callbackMetadata]], so callbacks firing during the
      * run can read it (Python's `Evaluate(callback_metadata=…)`). Empty default → scope unchanged.
      */
    callbackMetadata: DynamicValue.Record = DynamicValue.Record.empty
)

/** Runs a program on each example of a dev set and scores it with a metric (upstream `dspy.Evaluate`).
  *
  * Each run is an evaluate scope (upstream `on_evaluate_start` and `on_evaluate_end`) with the `evaluatorName`
  * `Evaluate`. The inputs are `devset_size` and `num_threads` (the thread count of the run), and the output is the
  * [[EvaluationResult]] or the failure. The scope runs in the context of the run, so it sees the `callbackMetadata`.
  *
  * The run installs the context that it gets (UPGRADE-3.2.1-to-3.4.0, decision D8: the given context wins), so the
  * callbacks and the parallel workers use the settings of that context.
  */
final class Evaluate(val config: EvaluateConfig) extends Evaluator:

  def apply(
      metric          : Option[Metric]              = None,
      devset          : Option[Vector[Example]]     = None,
      numThreads      : Option[ThreadCount]         = None,
      maxErrors       : Option[ErrorLimit]          = None,
      failureScore    : Option[Double]              = None,
      saveAsCsv       : Option[String]              = None,
      saveAsJson      : Option[String]              = None,
      callbackMetadata: Option[DynamicValue.Record] = None
  )(program: Example => Either[DspyError, RawPrediction])(using RuntimeContext): Either[DspyError, EvaluationResult] =
    applyInternal(
      program,
      config.copy(
        devset = devset.getOrElse(config.devset),
        metric = metric.getOrElse(config.metric),
        numThreads = numThreads.orElse(config.numThreads),
        maxErrors = maxErrors.orElse(config.maxErrors),
        failureScore = failureScore.getOrElse(config.failureScore),
        saveAsCsv = saveAsCsv.orElse(config.saveAsCsv),
        saveAsJson = saveAsJson.orElse(config.saveAsJson),
        callbackMetadata = callbackMetadata.getOrElse(config.callbackMetadata)
      )
    )

  /** One run of the evaluation, in an evaluate scope. */
  private def applyInternal(
      fn : Example => Either[DspyError, RawPrediction],
      cfg: EvaluateConfig
  )(using RuntimeContext): Either[DspyError, EvaluationResult] =
    // Carry the configured callbackMetadata into the evaluation scope so callbacks firing during the run can read
    // it (Python's `Evaluate(callback_metadata=…)`). Empty metadata leaves the ambient context untouched.
    val baseCtx      = summon[RuntimeContext]
    val withMetadata =
      if cfg.callbackMetadata.fields.isEmpty then baseCtx
      else baseCtx.withConfig(baseCtx.config.copy(callbackMetadata = Some(cfg.callbackMetadata)))
    // The run installs the given context (decision D8: the given context wins), and the run uses the effective context
    // of the call, also for the thread count of the inputs. It keeps the dynamic scope of this thread (the open call
    // scopes), so the evaluate scope nests under the scope that calls it, for example a compile scope, also when the
    // given context is older than that scope. The scope opens in the context of the run, so the parallel workers
    // capture its call id as their parent.
    RuntimeEnvironment.withInstalled(withMetadata) { effective ?=>
      val executor = ParallelExecutor.fromSettings(
        timeout = cfg.timeout,
        numThreads = cfg.numThreads,
        maxErrors = cfg.maxErrors
      )(using effective)
      val inputs: DynamicValue.Record = DynamicValues.record(
        "devset_size" := cfg.devset.size,
        "num_threads" := (executor.numThreads: Int)
      )
      CallbackDispatcher.withEvaluate("Evaluate", inputs)(runEvaluation(fn, cfg, executor)(using effective))
    }

  private def runEvaluation(
      fn      : Example => Either[DspyError, RawPrediction],
      cfg     : EvaluateConfig,
      executor: ParallelExecutor
  )(using RuntimeContext): Either[DspyError, EvaluationResult] =
    val dataset = cfg.devset
    val metric  = cfg.metric

    // An empty devset has no score. A score of 0.0 looks like a valid result to an optimizer, so this is an error
    // (Python raises `ValueError`).
    if dataset.isEmpty then Left(ValidationError("devset must contain at least one example, got an empty devset."))
    else
      val execResultE = executor.executeEither[Example, (RawPrediction, Double)](
        task = (ex: Example) =>
          // Runs on a parallel-executor worker thread; the ambient context is restored into the thread-local
          // there, so `RuntimeEnvironment.current` is the right `RuntimeContext` to hand the metric (so
          // LM-judged metrics can reach the LM).
          given RuntimeContext = RuntimeEnvironment.current
          fn(ex).flatMap { prediction =>
            metric.score(ex, prediction).map(score => (prediction, score))
          }
        ,
        data = dataset
      )

      execResultE.flatMap { execResult =>
        val evaluations = dataset.zip(execResult.results).map {
          case (example, Right((prediction, score))) => ExampleEvaluation(example, prediction, score)
          case (example, Left(err))                  => ExampleEvaluation(
              example,
              RawPrediction.empty,
              cfg.failureScore,
              error = Some(s"[${err.code}] ${err.message}")
            )
        }

        val ncorrect = evaluations.map(_.score).sum
        val result   = EvaluationResult(
          score = PythonText.round(100.0 * ncorrect / evaluations.size, 2),
          results = evaluations,
          metricName = metric.name
        )

        def persistenceError(kind: String, message: String): DspyError =
          RuntimeError("evaluation_persistence", s"Failed to save $kind results: $message")

        val persisted = cfg.saveAsJson
          .fold[Either[DspyError, Unit]](Right(()))(path =>
            EvaluationResultPersistence.saveAsJson(result, path).left.map(persistenceError("JSON", _))
          )
          .flatMap { _ =>
            cfg.saveAsCsv.fold[Either[DspyError, Unit]](Right(()))(path =>
              EvaluationResultPersistence.saveAsCsv(result, path).left.map(persistenceError("CSV", _))
            )
          }

        persisted.map { _ =>
          if cfg.displayProgress then
            println(f"[Evaluate] score=${result.score}%.2f%% on ${dataset.size} examples using metric '${metric.name}'")

          cfg.displayTable match
            case TableDisplay.Off                      => ()
            case TableDisplay.All                      => println(result.renderTable())
            case TableDisplay.Rows(count) if count > 0 => println(result.renderTable(Some(count)))
            case TableDisplay.Rows(_)                  => ()

          result
        }
      }

  override def evaluate(
      predict: Example => Either[DspyError, RawPrediction],
      dataset: Vector[Example],
      metric : Metric
  )(using RuntimeContext): Either[DspyError, EvaluationResult] =
    applyInternal(
      predict,
      config.copy(devset = dataset, metric = metric)
    )

object Evaluate:
  def apply(
      devset          : Vector[Example],
      metric          : Metric,
      numThreads      : Option[ThreadCount] = None,
      maxErrors       : Option[ErrorLimit]  = None,
      failureScore    : Double              = 0.0,
      displayProgress : Boolean             = false,
      displayTable    : TableDisplay        = TableDisplay.Off,
      saveAsCsv       : Option[String]      = None,
      saveAsJson      : Option[String]      = None,
      timeout         : Duration            = Duration.Inf,
      callbackMetadata: DynamicValue.Record = DynamicValue.Record.empty
  ): Evaluate =
    new Evaluate(
      EvaluateConfig(
        devset = devset,
        metric = metric,
        numThreads = numThreads,
        maxErrors = maxErrors,
        failureScore = failureScore,
        displayProgress = displayProgress,
        displayTable = displayTable,
        saveAsCsv = saveAsCsv,
        saveAsJson = saveAsJson,
        timeout = timeout,
        callbackMetadata = callbackMetadata
      )
    )
