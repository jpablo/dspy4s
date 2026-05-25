package dspy4s.programs

import dspy4s.core.contracts.Completions
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.NotFoundError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.PredictionData
import dspy4s.core.contracts.ValidationError

/** Aggregation utilities for collapsing multiple candidate completions into a
  * single [[DynamicPrediction]]. Port of Python DSPy's `dspy.predict.aggregation`.
  *
  * The primary entry point is [[majority]], which picks the most-common value
  * for a given output field across a set of candidate completions. Ties are
  * broken by first occurrence (Python parity).
  *
  * Note on the default normalizer: Python's default is `dspy.evaluate.normalize_text`
  * (NFD-normalize → strip diacritics → lowercase → strip punctuation →
  * remove `a/an/the` → collapse whitespace). dspy4s's default here is the
  * minimal trim-and-blank-check — pass `NormalizeText.normalize` from
  * `dspy4s.evaluate.metrics` (or any custom function) for the heavier
  * normalization. We keep the default minimal so `programs` doesn't need
  * to depend on `evaluate` (which already depends on `programs`).
  */
object Aggregation:

  /** Minimal default: stringify the value, trim whitespace; treat blank as
    * absent. Matches Python parity well enough for the common case of
    * comparing already-canonical strings. */
  val defaultNormalize: Any => Option[String] = {
    case null         => None
    case s: String    => Option(s.trim).filter(_.nonEmpty)
    case other        => Option(other.toString.trim).filter(_.nonEmpty)
  }

  /** Returns a [[DynamicPrediction]] whose chosen completion's value for the given
    * field is the majority across `rows`. Ties are broken by first occurrence.
    *
    *   - `field = None` picks the **last** key in the first row (matches
    *     Python's "last output field" default when no signature is available).
    *   - `normalize` returns `None` to exclude a row from the count entirely.
    *     If every row normalizes to `None`, the count falls back to raw
    *     values so we still pick a winner.
    */
  def majority(
      rows: Vector[Map[String, Any]],
      field: Option[String] = None,
      normalize: Any => Option[String] = defaultNormalize
  ): Either[DspyError, DynamicPrediction] =
    if rows.isEmpty then Left(ValidationError("Cannot compute majority over an empty set of completions"))
    else
      val resolvedField = field.getOrElse(rows.head.keys.toVector.last)
      val rowAccessor: Map[String, Any] => Either[DspyError, Any] = row =>
        row.get(resolvedField).toRight(
          NotFoundError("completion_field", s"Completion field '$resolvedField' does not exist")
        )

      // For each row, compute its normalized key (or None when excluded).
      rows.foldLeft[Either[DspyError, Vector[(Map[String, Any], Option[String])]]](Right(Vector.empty)) { (acc, row) =>
        for
          soFar <- acc
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
          val majorityKey = tally.maxBy(_._2)._1
          val winner = counted.iterator.collectFirst {
            case (row, Some(k)) if k == majorityKey => row
          }.getOrElse(rows.head)
          PredictionData.fromRows(Vector(winner))
      }

  /** Run majority over the [[Completions]] embedded in a [[DynamicPrediction]],
    * falling back to a single-row vote when the prediction has no
    * completions attached. Convenience overload — defaults live only on
    * the row-based primary [[majority]] above (Scala 3 forbids defaults on
    * multiple overloads of the same name). */
  def majorityOf(
      prediction: DynamicPrediction,
      field: Option[String] = None,
      normalize: Any => Option[String] = defaultNormalize
  ): Either[DspyError, DynamicPrediction] =
    prediction.completions match
      case Some(completions) => majorityOf(completions, field, normalize)
      case None              => majority(Vector(prediction.values), field, normalize)

  /** Run majority over a [[Completions]] directly. Convenience overload. */
  def majorityOf(
      completions: Completions,
      field: Option[String],
      normalize: Any => Option[String]
  ): Either[DspyError, DynamicPrediction] =
    if completions.size == 0 then
      Left(ValidationError("Cannot compute majority over an empty Completions"))
    else
      val rows: Vector[Map[String, Any]] = (0 until completions.size).toVector.map { idx =>
        completions.fields.map { case (k, v) => k -> v(idx) }
      }
      majority(rows, field, normalize)
