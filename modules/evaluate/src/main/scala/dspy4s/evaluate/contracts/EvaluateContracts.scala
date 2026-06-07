package dspy4s.evaluate.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry

object Evaluator:
  type PredictFn = Example => Either[DspyError, DynamicPrediction]

trait Metric:
  def name: String
  def score(example: Example, prediction: DynamicPrediction, trace: Vector[TraceEntry] = Vector.empty): Either[DspyError, Double]

/** A single per-example evaluation outcome. `error` carries the captured failure message when the program
  * (or metric) failed on this example and traceback capture was enabled; it is `None` on success or when
  * traceback capture is disabled. */
final case class ExampleEvaluation(
    example: Example,
    prediction: DynamicPrediction,
    score: Double,
    error: Option[String] = None
)

final case class EvaluationResult(
    score: Double,
    results: Vector[ExampleEvaluation],
    metricName: String,
    metadata: Map[String, Any] = Map.empty
):
  def aggregateScore: Double = score

  /** Renders the evaluation results as a dependency-free, fixed-width plain-text table. Columns are the union
    * of example field names, the prediction's `answer`/fields, plus `score` (and `error` when any row has one).
    * `limit`, when set, caps the number of rows rendered (mirrors dspy's `display_table` row limit). */
  def renderTable(limit: Option[Int] = None): String =
    val rows = limit.fold(results)(n => results.take(math.max(0, n)))

    val exampleCols: Vector[String] =
      results.iterator.flatMap(r => DynamicValues.recordKeys(r.example.values)).toVector.distinct
    val predCols: Vector[String] =
      results.iterator
        .flatMap(r => DynamicValues.recordKeys(r.prediction.values))
        .map(name => s"pred:$name")
        .toVector
        .distinct
    val hasError = results.exists(_.error.isDefined)

    val headers: Vector[String] =
      exampleCols ++ predCols ++ Vector("score") ++ (if hasError then Vector("error") else Vector.empty)

    def cell(r: ExampleEvaluation, header: String): String =
      header match
        case "score"             => f"${r.score}%.4f"
        case "error"             => r.error.getOrElse("")
        case h if h.startsWith("pred:") =>
          r.prediction.get(h.stripPrefix("pred:")).map(DynamicValues.renderText).getOrElse("")
        case h                   =>
          r.example.get(h).map(DynamicValues.renderText).getOrElse("")

    val dataRows: Vector[Vector[String]] = rows.map(r => headers.map(h => cell(r, h)))

    val widths: Vector[Int] =
      headers.indices.map { c =>
        (headers(c).length +: dataRows.map(_(c).length)).max
      }.toVector

    def renderRow(cells: Vector[String]): String =
      cells.zip(widths).map((value, w) => value.padTo(w, ' ')).mkString("| ", " | ", " |")

    val separator = widths.map(w => "-" * (w + 2)).mkString("|", "|", "|")

    val lines = renderRow(headers) +: separator +: dataRows.map(renderRow)
    lines.mkString("", "\n", "\n")

trait Evaluator:
  def evaluate(
      predict: Evaluator.PredictFn,
      dataset: Vector[Example],
      metric: Metric
  )(using RuntimeContext): Either[DspyError, EvaluationResult]
