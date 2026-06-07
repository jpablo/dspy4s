package dspy4s.programs

import dspy4s.core.contracts.Completions
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.NotFoundError
import dspy4s.core.contracts.ValidationError
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Aggregation utilities for collapsing multiple candidate completions into a single [[DynamicPrediction]]. Port
  * of Python DSPy's `dspy.predict.aggregation`.
  *
  * The primary entry point is [[majority]], which picks the most-common value for a given output field across a
  * set of candidate completions. Ties are broken by first occurrence (Python parity).
  *
  * The default normalizer is a minimal trim-and-blank-check — pass `NormalizeText.normalize` from
  * `dspy4s.evaluate.metrics` (or any custom function) for the heavier normalization. We keep the default minimal
  * so `programs` doesn't need to depend on `evaluate` (which already depends on `programs`).
  */
object Aggregation:

  /** Minimal default: stringify the value, trim whitespace; treat blank as absent. */
  val defaultNormalize: DynamicValue => Option[String] = {
    case _: DynamicValue.Null.type                          => None
    case DynamicValue.Primitive(PrimitiveValue.String(s))   => Option(s.trim).filter(_.nonEmpty)
    case other =>
      val rendered = DynamicValues.renderText(other).trim
      Option(rendered).filter(_.nonEmpty)
  }

  /** Returns a [[DynamicPrediction]] whose chosen completion's value for the given field is the majority across
    * `rows`. Ties are broken by first occurrence.
    *
    *   - `field = None` picks the **last** key in the first row (matches Python's "last output field" default
    *     when no signature is available).
    *   - `normalize` returns `None` to exclude a row from the count entirely. If every row normalizes to `None`,
    *     the count falls back to raw values so we still pick a winner. */
  def majority(
      rows: Vector[DynamicValue.Record],
      field: Option[String] = None,
      normalize: DynamicValue => Option[String] = defaultNormalize
  ): Either[DspyError, DynamicPrediction] =
    if rows.isEmpty then Left(ValidationError("Cannot compute majority over an empty set of completions"))
    else
      val resolvedField = field.getOrElse(DynamicValues.recordKeys(rows.head).last)
      val rowAccessor: DynamicValue.Record => Either[DspyError, DynamicValue] = row =>
        DynamicValues.recordGet(row, resolvedField).toRight(
          NotFoundError("completion_field", s"Completion field '$resolvedField' does not exist")
        )

      rows.foldLeft[Either[DspyError, Vector[(DynamicValue.Record, Option[String])]]](Right(Vector.empty)) {
        (acc, row) =>
          for
            soFar    <- acc
            rawValue <- rowAccessor(row)
          yield soFar :+ (row -> normalize(rawValue))
      }.flatMap { paired =>
        val filtered = paired.filter(_._2.isDefined)
        val counted = if filtered.nonEmpty then filtered else paired.map { case (r, _) => r -> Some("") }
        val tally = counted.foldLeft(Map.empty[String, Int]) { (m, pair) =>
          pair._2 match
            case Some(k) => m.updated(k, m.getOrElse(k, 0) + 1)
            case None    => m
        }
        if tally.isEmpty then Left(ValidationError("No countable values for majority"))
        else
          val maxCount = tally.values.max
          // First-occurrence tie-break (Python `Counter.most_common` parity): among keys tied at the max count,
          // pick the row whose normalized value appears FIRST in declaration order. `tally` is an unordered Map,
          // so `maxBy` would break ties in hash order (arbitrary for >=5 distinct values) -- scan `counted` instead.
          val winner = counted.iterator.collectFirst {
            case (row, Some(k)) if tally.getOrElse(k, 0) == maxCount => row
          }.getOrElse(rows.head)
          DynamicPrediction.fromRows(Vector(winner))
      }

  /** Run majority over the [[Completions]] embedded in a [[DynamicPrediction]], falling back to a single-row vote
    * when the prediction has no completions attached. */
  def majorityOf(
      prediction: DynamicPrediction,
      field: Option[String] = None,
      normalize: DynamicValue => Option[String] = defaultNormalize
  ): Either[DspyError, DynamicPrediction] =
    prediction.completions match
      case Some(completions) => majorityOf(completions, field, normalize)
      case None              => majority(Vector(prediction.values), field, normalize)

  /** Run majority over a [[Completions]] directly. Convenience overload. */
  def majorityOf(
      completions: Completions,
      field: Option[String],
      normalize: DynamicValue => Option[String]
  ): Either[DspyError, DynamicPrediction] =
    if completions.size == 0 then
      Left(ValidationError("Cannot compute majority over an empty Completions"))
    else
      val rows: Vector[DynamicValue.Record] = (0 until completions.size).toVector.flatMap { idx =>
        completions.at(idx).toOption.map(_.values)
      }
      majority(rows, field, normalize)
