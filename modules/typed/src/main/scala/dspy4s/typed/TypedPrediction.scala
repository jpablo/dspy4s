package dspy4s.typed

import dspy4s.core.contracts.{DspyError, DynamicPrediction}

/** A `DynamicPrediction` whose output fields have been decoded into a typed case
  * class instance `O`. Constructed only after every required output field
  * has been decoded successfully — field access on `output` is always safe
  * (no lazy parsing, no per-field `Either`), and all decode failures
  * surface at construction time as a `Left` from `Shape.decode`.
  *
  * The raw `DynamicPrediction` remains available via `raw` so callers can still
  * reach completions, LM usage, and adapter metadata.
  *
  * Phase 2 carries the typed output as the decoded value itself: case-class
  * signatures expose ordinary case-class fields, and trait-spec signatures
  * expose named-tuple fields. In both cases `p.output.sentiment` is typed
  * dot-access with no lazy parsing.
  */
final case class TypedPrediction[O](
    output: O,
    raw: DynamicPrediction
)

object TypedPrediction:

  /** Decodes a raw `DynamicPrediction` against an output `Shape`, lifting decode
    * errors into the `Either` channel.
    *
    * Reads the field values directly from `raw.values` (the `Record` trait
    * surface that `DynamicPrediction` extends). Multi-completion decoding — picking
    * a specific completion index or producing one typed prediction per
    * completion — is deferred to a later phase. */
  def from[O](raw: DynamicPrediction, outputShape: Shape[O]): Either[DspyError, TypedPrediction[O]] =
    outputShape.decode(raw.values).map(o => TypedPrediction(o, raw))
