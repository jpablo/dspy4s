package dspy4s.evaluate

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.evaluate.contracts.{EvaluationResult, ExampleEvaluation}
import dspy4s.programs.{PredictionBackend, ProgramEvent, ProgramRunner, RecordProgramWithEnv}
import zio.{URIO, ZIO}

/** Effectful metric over a prediction and its explicit interpreter events. */
trait Metric:
  def name: String
  def score(
      example   : Example,
      prediction: RawPrediction,
      events    : Vector[ProgramEvent]
  ): ZIO[PredictionBackend, DspyError, Double]

final case class EvaluateOptions(
    parallelism  : Int     = 8,
    failureScore : Double  = 0.0,
    includeErrors: Boolean = false
):
  require(parallelism > 0, "Evaluate parallelism must be positive")

/** Stateless evaluator for a functional record program. Program and metric failures become per-example results. */
object Evaluate:

  def apply[I, O, R](
      program: RecordProgramWithEnv[I, O, R],
      dataset: Vector[Example],
      metric : Metric,
      options: EvaluateOptions = EvaluateOptions()
  ): URIO[R & PredictionBackend, EvaluationResult] =
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

  private def evaluateOne[I, O, R](
      program: RecordProgramWithEnv[I, O, R],
      example: Example,
      metric : Metric,
      options: EvaluateOptions
  ): URIO[R & PredictionBackend, ExampleEvaluation] =
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
      options   : EvaluateOptions
  ): ExampleEvaluation =
    ExampleEvaluation(
      example,
      prediction,
      options.failureScore,
      if options.includeErrors then Some(s"[${error.code}] ${error.message}") else None
    )
