package dspy4s.core.data

import dspy4s.core.contracts.{DspyError, DynamicValues, ValidationError}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue

/** A column-oriented view of N candidate completions for one LM call. Each field name maps to a vector of N values (one
  * per candidate). All columns must have the same length, which defines [[size]]. [[at]] converts a single column index
  * back to row form when a call site wants a per-candidate record.
  *
  * Most code paths see exactly one completion per call (`size == 1`), produced by [[Completions.fromRows]] with a
  * single-row input. Multiple completions arise when an LM provider returns `n > 1` choices (e.g. OpenAI's `n`
  * parameter) or when `BestOfN` runs `Predict` multiple times and packages the results.
  *
  * The `require` enforces the equal-column-length invariant at construction time so all downstream `at(i)` calls can
  * read column `i` without bounds-checking each field individually.
  */
final case class Completions(fields: Map[String, Vector[DynamicValue]]):
  private val lengths = fields.values.map(_.size).toSet
  require(lengths.size <= 1, "All completion fields must have the same number of values")

  def size: Int = fields.values.headOption.map(_.size).getOrElse(0)

  def at(index: Int): Either[DspyError, RawPrediction] =
    if index < 0 || index >= size then
      Left(ValidationError(s"Completion index $index out of bounds for size $size"))
    else
      val row = DynamicValue.Record(Chunk.from(fields.iterator.map((k, vs) => k -> vs(index)).toSeq))
      Right(RawPrediction(values = row))

object Completions:
  /** Convert N row-shaped records into the columnar layout. Fails if any row's key set differs from the first row's —
    * since `Completions.at(i)` is supposed to return a row whose fields are uniform across all candidates, missing
    * fields would corrupt that invariant. An empty input is the empty completion (`size == 0`).
    */
  def fromRows(rows: Vector[DynamicValue.Record]): Either[DspyError, Completions] =
    if rows.isEmpty then Right(Completions(Map.empty))
    else
      val expectedKeys = DynamicValues.recordKeys(rows.head).toSet
      if rows.exists(r => DynamicValues.recordKeys(r).toSet != expectedKeys) then
        Left(ValidationError("All completion rows must include the same set of fields"))
      else
        val columns = expectedKeys.iterator.map { key =>
          key -> rows.map(r => DynamicValues.recordGet(r, key).getOrElse(DynamicValue.Null))
        }.toMap
        Right(Completions(columns))
