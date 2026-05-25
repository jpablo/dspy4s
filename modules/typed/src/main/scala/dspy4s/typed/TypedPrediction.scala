package dspy4s.typed

import dspy4s.core.contracts.{DspyError, Prediction}

/** A `Prediction` whose output fields have been decoded into a typed case
  * class instance `O`. Constructed only after every required output field
  * has been decoded successfully — field access on `output` is always safe
  * (no lazy parsing, no per-field `Either`), and all decode failures
  * surface at construction time as a `Left` from `Shape.decode`.
  *
  * The raw `Prediction` remains available via `raw` so callers can still
  * reach completions, LM usage, and adapter metadata.
  *
  * Phase 2 carries the typed output as the case class instance itself —
  * `p.output.sentiment` gives typed dot-access through ordinary case-class
  * field syntax. A later phase may wrap the typed output in a `kyo.Record`
  * for users who want intersection-typed structural composition.
  */
final case class TypedPrediction[O](
    output: O,
    raw: Prediction
)

object TypedPrediction:

  /** Decodes a raw `Prediction` against an output `Shape`, lifting decode
    * errors into the `Either` channel. */
  def from[O](raw: Prediction, outputShape: Shape[O]): Either[DspyError, TypedPrediction[O]] =
    val rawValues = raw.completions match
      case Some(comps) =>
        comps.at(0).map(_.values).getOrElse(extractValuesFromAny(raw))
      case None =>
        extractValuesFromAny(raw)
    outputShape.decode(rawValues).map(o => TypedPrediction(o, raw))

  /** Best-effort extraction of a `Map[String, Any]` from a `Prediction` when
    * completions aren't populated. Uses the `values` accessor available on
    * `PredictionData` (the only Prediction implementor today). */
  private def extractValuesFromAny(raw: Prediction): Map[String, Any] =
    raw match
      case pd: dspy4s.core.contracts.PredictionData => pd.values
      case _                                        => Map.empty
