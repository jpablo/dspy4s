package dspy4s.programs

import dspy4s.core.collections.Traverse
import dspy4s.core.data.Completions
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.NotFoundError
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.ValidationError
import zio.blocks.schema.DynamicValue

/** Aggregation utilities for collapsing multiple candidate completions into a single [[RawPrediction]]. Port of Python
  * DSPy's `dspy.predict.aggregation`.
  *
  * The primary entry point is [[majority]], which picks the most-common value for a given output field across a set of
  * candidate completions. Ties are broken by first occurrence (Python parity).
  *
  * The default normalizer is a minimal trim-and-blank-check — pass `NormalizeText.normalize` from
  * `dspy4s.evaluate.metrics` (or any custom function) for the heavier normalization. We keep the default minimal so
  * `programs` doesn't need to depend on `evaluate` (which already depends on `programs`).
  */
object Aggregation:

  /** Minimal default: stringify the value, trim whitespace; treat blank as absent. */
  val defaultNormalize: DynamicValue => Option[String] = value =>
    Option.unless(DynamicValues.isNull(value))(DynamicValues.renderText(value).trim).filter(_.nonEmpty)

  /** Returns a [[RawPrediction]] whose chosen completion's value for the given field is the majority across `rows`.
    * Ties are broken by first occurrence.
    *
    *   - `field = None` picks the **last** key in the first row (matches Python's "last output field" default when no
    *     signature is available).
    *   - `normalize` returns `None` to exclude a row from the count entirely. If every row normalizes to `None`, the
    *     first row wins.
    */
  def majority(
      rows     : Vector[DynamicValue.Record],
      field    : Option[String]                 = None,
      normalize: DynamicValue => Option[String] = defaultNormalize
  ): Either[DspyError, RawPrediction] =
    if rows.isEmpty then Left(ValidationError("Cannot compute majority over an empty set of completions"))
    else
      // "Last key" must skip the synthetic `tool_calls` value PredictEngine appends on native-tool turns —
      // otherwise the default vote runs over tool-call payloads instead of the answer field.
      val resolvedField = field.getOrElse {
        val keys = DynamicValues.recordKeys(rows.head)
        keys.filterNot(_ == ToolCall.ToolCallsKey).lastOption.getOrElse(keys.last)
      }
      Traverse
        .either(rows) { row =>
          DynamicValues.recordGet(row, resolvedField)
            .toRight(NotFoundError("completion_field", s"Completion field '$resolvedField' does not exist"))
            .map(value => normalize(value).map(_ -> row))
        }
        .flatMap { counted =>
          val votes    = counted.flatten
          val tally    = votes.groupMapReduce(_._1)(_ => 1)(_ + _)
          val maxCount = tally.values.maxOption
          // First-occurrence tie-break (Python `Counter.most_common` parity): among keys tied at the max count, pick
          // the row whose normalized value appears FIRST in declaration order. `tally` is an unordered Map, so `maxBy`
          // would break ties in hash order (arbitrary for >=5 distinct values). With no votes, the first row wins.
          val winner = votes.find((key, _) => maxCount.contains(tally(key))).fold(rows.head)(_._2)
          RawPrediction.fromRows(Vector(winner))
        }

  /** Run majority over the [[Completions]] embedded in a [[RawPrediction]], falling back to a single-row vote when the
    * prediction has no completions attached.
    */
  def majorityOf(
      prediction: RawPrediction,
      field     : Option[String]                 = None,
      normalize : DynamicValue => Option[String] = defaultNormalize
  ): Either[DspyError, RawPrediction] =
    prediction.completions match
      case Some(completions) => majorityOf(completions, field, normalize)
      case None              => majority(Vector(prediction.values), field, normalize)

  /** Run majority over a [[Completions]] directly. Convenience overload. */
  def majorityOf(
      completions: Completions,
      field      : Option[String],
      normalize  : DynamicValue => Option[String]
  ): Either[DspyError, RawPrediction] =
    if completions.size == 0 then
      Left(ValidationError("Cannot compute majority over an empty Completions"))
    else
      val rows: Vector[DynamicValue.Record] = (0 until completions.size).toVector.flatMap { idx =>
        completions.at(idx).toOption.map(_.values)
      }
      majority(rows, field, normalize)
