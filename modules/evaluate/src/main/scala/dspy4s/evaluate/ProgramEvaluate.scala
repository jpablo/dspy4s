package dspy4s.evaluate

import dspy4s.core.contracts.{DspyError, RuntimeContext, RuntimeError}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.evaluate.contracts.{EvaluationResult, ExampleEvaluation, Metric}
import dspy4s.programs.plan.{PredictionBackend, ProgramEvent, ProgramRunner, RecordProgram}
import zio.{IO, URIO, ZIO}

/** Effectful metric for the new program interpreter. */
trait ProgramMetric:
  def name: String
  def score(
      example   : Example,
      prediction: RawPrediction,
      events    : Vector[ProgramEvent]
  ): IO[DspyError, Double]

object ProgramMetric:
  /** Temporary bridge for a synchronous metric. The compatibility context is explicit. */
  def fromMetric(metric: Metric, context: RuntimeContext): ProgramMetric =
    new ProgramMetric:
      val name: String = metric.name

      def score(
          example                  : Example,
          prediction               : RawPrediction,
          @annotation.unused events: Vector[ProgramEvent]
      ): IO[DspyError, Double] =
        ZIO
          .attemptBlocking {
            given RuntimeContext = context
            metric.score(example, prediction)
          }
          .mapError(error =>
            RuntimeError(
              "evaluation_metric",
              Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
            )
          )
          .flatMap(ZIO.fromEither)

final case class ProgramEvaluateOptions(
    parallelism  : Int     = 8,
    failureScore : Double  = 0.0,
    includeErrors: Boolean = false
):
  require(parallelism > 0, "ProgramEvaluate parallelism must be positive")

/** Stateless evaluator for [[RecordProgram]]. Program and metric failures become per-example results. */
object ProgramEvaluate:

  def apply[I, O](
      program: RecordProgram[I, O],
      dataset: Vector[Example],
      metric : ProgramMetric,
      options: ProgramEvaluateOptions = ProgramEvaluateOptions()
  ): URIO[PredictionBackend, EvaluationResult] =
    ZIO
      .foreachPar(dataset)(example => evaluateOne(program, example, metric, options))
      .withParallelism(options.parallelism)
      .map { evaluations =>
        val aggregate =
          if evaluations.isEmpty then 0.0
          else evaluations.iterator.map(_.score).sum / evaluations.size * 100.0
        EvaluationResult(
          score = aggregate,
          results = evaluations,
          metricName = metric.name,
          metadata = Map(
            "parallelism" -> options.parallelism,
            "devset_size" -> dataset.size
          )
        )
      }

  private def evaluateOne[I, O](
      program: RecordProgram[I, O],
      example: Example,
      metric : ProgramMetric,
      options: ProgramEvaluateOptions
  ): URIO[PredictionBackend, ExampleEvaluation] =
    ProgramRunner.runRecordJournaled(program, example.inputs).flatMap { execution =>
      execution.outcome match
        case Left(error)       => ZIO.succeed(failed(example, RawPrediction.empty, error, options))
        case Right(prediction) => metric.score(example, prediction.raw, execution.events).either.map {
            case Left(error)  => failed(example, prediction.raw, error, options)
            case Right(score) => ExampleEvaluation(example, prediction.raw, score)
          }
    }

  private def failed(
      example   : Example,
      prediction: RawPrediction,
      error     : DspyError,
      options   : ProgramEvaluateOptions
  ): ExampleEvaluation =
    ExampleEvaluation(
      example,
      prediction,
      options.failureScore,
      if options.includeErrors then Some(s"[${error.code}] ${error.message}") else None
    )
