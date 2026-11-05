package dspy4s.evaluate

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.PythonText
import dspy4s.evaluate.contracts.EvaluationResult
import zio.blocks.schema.DynamicValue

import java.nio.file.Files
import java.nio.file.Paths
import scala.util.control.NonFatal

/** Writes the [[EvaluationResult.rows]] to a file (the `save_as_json` and `save_as_csv` steps of upstream
  * `Evaluate.__call__`).
  */
object EvaluationResultPersistence:

  /** The rows as a JSON text, as upstream `json.dump(data, f)`: ASCII escapes, and no indent. */
  def saveAsJson(result: EvaluationResult, path: String): Either[String, Unit] =
    write(path, PythonText.jsonAscii(DynamicValues.sequence(result.rows.map(row => DynamicValues.record(row.toSeq*)))))

  /** The rows as a CSV text, in the form of the Python `csv` module. The columns are the union of the keys of the rows,
    * in the order of their first occurrence. Upstream takes the keys of the first row, and raises on another key.
    */
  def saveAsCsv(result: EvaluationResult, path: String): Either[String, Unit] =
    val rows    = result.rows
    val columns = (rows.flatMap(_.keysIterator) :+ result.metricName).distinct
    val lines   = columns +: rows.map(row => columns.map(column => row.get(column).fold("")(csvText)))
    write(path, lines.map(_.map(csvField).mkString("", ",", "\r\n")).mkString)

  private def write(path: String, text: String): Either[String, Unit] =
    try
      val _ = Files.writeString(Paths.get(path), text)
      Right(())
    catch
      case NonFatal(error) => Left(DspyError.messageOf(error))

  /** The CSV text of a value: a null is the empty text, as Python `csv`, and each other value is Python `str`. */
  private def csvText(value: DynamicValue): String =
    PythonText.unwrap(value) match
      case _: DynamicValue.Null.type => ""
      case other                     => PythonText.str(other)

  /** A CSV field, in quotes when it has a comma, a quote, or a line break (Python `csv.QUOTE_MINIMAL`). */
  private def csvField(text: String): String =
    if text.exists(char => char == ',' || char == '"' || char == '\r' || char == '\n') then
      "\"" + text.replace("\"", "\"\"") + "\""
    else text
