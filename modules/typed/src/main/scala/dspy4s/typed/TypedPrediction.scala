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
    * errors into the `Either` channel.
    *
    * Reads the field values directly from `raw.values` (the `Record` trait
    * surface that `Prediction` extends). Multi-completion decoding — picking
    * a specific completion index or producing one typed prediction per
    * completion — is deferred to a later phase. */
  def from[O](raw: Prediction, outputShape: Shape[O]): Either[DspyError, TypedPrediction[O]] =
    outputShape.decode(raw.values).map(o => TypedPrediction(o, raw))
