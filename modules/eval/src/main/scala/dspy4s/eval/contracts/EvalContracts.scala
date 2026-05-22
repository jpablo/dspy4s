package dspy4s.eval.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry

object Evaluator:
  type PredictFn = Example => Either[DspyError, Prediction]

trait Metric:
  def name: String
  def score(example: Example, prediction: Prediction, trace: Vector[TraceEntry] = Vector.empty): Either[DspyError, Double]

final case class ExampleEvaluation(example: Example, prediction: Prediction, score: Double)

final case class EvaluationResult(
    score: Double,
    results: Vector[ExampleEvaluation],
    metricName: String,
    metadata: Map[String, Any] = Map.empty
):
  def aggregateScore: Double = score

trait Evaluator:
  def evaluate(
      predict: Evaluator.PredictFn,
      dataset: Vector[Example],
      metric: Metric
  )(using RuntimeContext): Either[DspyError, EvaluationResult]
