package dspy4s.evaluate.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.PythonText
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import java.util.Locale
import scala.collection.immutable.VectorMap

object Evaluator:
  type PredictFn = Example => Either[DspyError, RawPrediction]

trait Metric:
  def name: String

  /** Score a single prediction against its example. `trace` is the program's per-call trace (empty during evaluation;
    * non-empty during bootstrapping, mirroring Python's `trace is None` branch). The ambient [[RuntimeContext]] lets
    * LM-judged metrics (e.g. `SemanticF1`) invoke a language model during scoring; builtin string-comparison metrics
    * ignore it.
    */
  def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry] = Vector.empty)(using
      RuntimeContext
  ): Either[DspyError, Double]

/** The outcome of one example. `error` holds the failure of the program or the metric as `[code] message`. It is `None`
  * for a success.
  */
final case class ExampleEvaluation(
    example   : Example,
    prediction: RawPrediction,
    score     : Double,
    error     : Option[String] = None
)

final case class EvaluationResult(
    score     : Double,
    results   : Vector[ExampleEvaluation],
    metricName: String
):

  /** One row for each result, in order (upstream `_prepare_results_output` and `merge_dicts`,
    * `dspy/evaluate/evaluate.py`). The row has the fields of the example, with the prefix `example_` only when the
    * prediction has a field with the same name. Then it has the fields of the prediction, with the prefix `pred_` only
    * on that collision. Then it has the metric name with the score. The score replaces a field with the metric name in
    * the position of that field, as Python `|` does.
    */
  def rows: Vector[VectorMap[String, DynamicValue]] =
    results.map { result =>
      val exampleFields    = DynamicValues.recordEntries(result.example.values)
      val predictionFields = DynamicValues.recordEntries(result.prediction.values)
      val exampleNames     = exampleFields.map(_._1).toSet
      val predictionNames  = predictionFields.map(_._1).toSet
      val merged           = VectorMap.from(
        exampleFields.map((name, value) => (if predictionNames(name) then s"example_$name" else name) -> value) ++
          predictionFields.map((name, value) => (if exampleNames(name) then s"pred_$name" else name) -> value)
      )
      merged.updated(metricName, DynamicValue.Primitive(PrimitiveValue.Double(result.score)))
    }

  /** The results as a plain-text table with fixed column widths. The columns come from [[rows]]. A cell has at most 25
    * words, then `...` (upstream `truncate_cell`). A cell of the metric column has the form `%.3f` (upstream
    * `stylize_metric_name`). When a result has an error, the last column is `<error>`, or a name with more angle
    * brackets when a field has that name. With a `limit` below the row count, the table has that many rows, and the
    * last line says how many rows it hides (upstream `_display_result_table`).
    */
  def renderTable(limit: Option[Int] = None): String =
    val allRows      = rows
    val fieldColumns = (allRows.flatMap(_.keysIterator) :+ metricName).distinct
    val errorColumn  = Option.when(results.exists(_.error.isDefined))(
      Iterator.iterate("<error>")(name => s"<$name>").dropWhile(name => fieldColumns.contains(name)).next()
    )
    val headers = fieldColumns ++ errorColumn

    val paired = results.zip(allRows)
    val shown  = limit.fold(paired)(paired.take)
    val cells  = shown.map { (result, row) =>
      val fieldCells = fieldColumns.map { column =>
        if column == metricName then "%.3f".formatLocal(Locale.ROOT, result.score)
        else row.get(column).fold("")(value => truncateCell(PythonText.str(value)))
      }
      fieldCells ++ errorColumn.map(_ => truncateCell(result.error.getOrElse("")))
    }

    val widths: Vector[Int] = headers.indices.map(c => (headers(c).length +: cells.map(_(c).length)).max).toVector

    def renderRow(row: Vector[String]): String =
      row.zip(widths).map((value, w) => value.padTo(w, ' ')).mkString("| ", " | ", " |")

    val separator = widths.map(w => "-" * (w + 2)).mkString("|", "|", "|")
    val hidden    = paired.size - shown.size
    val footer    = Option.when(hidden > 0)(s"... $hidden more rows not displayed ...")
    ((renderRow(headers) +: separator +: cells.map(renderRow)) ++ footer).mkString("", "\n", "\n")

  private def truncateCell(text: String): String =
    val words = PythonText.split(text)
    if words.size > EvaluationResult.MaxCellWords then words.take(EvaluationResult.MaxCellWords).mkString(" ") + "..."
    else text

object EvaluationResult:
  private val MaxCellWords: Int = 25

trait Evaluator:
  def evaluate(
      predict: Evaluator.PredictFn,
      dataset: Vector[Example],
      metric : Metric
  )(using RuntimeContext): Either[DspyError, EvaluationResult]
