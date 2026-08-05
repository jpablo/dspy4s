package dspy4s.programs.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.algebra.ScalaMonad
import dspy4s.signatures.Shape
import zio.blocks.schema.DynamicValue

/** A prediction whose raw field values have been decoded into a semantic output value `O`. Constructed only after every
  * required output field has been decoded successfully — field access on `output` is always safe (no lazy parsing, no
  * per-field `Either`), and all decode failures surface at construction time as a `Left` from `Shape.decode`.
  *
  * The schema-uninterpreted `RawPrediction` remains available via `raw` so callers can still reach completions, LM
  * usage, and adapter metadata.
  *
  * Phase 2 carries the typed output as the decoded value itself: case-class signatures expose ordinary case-class
  * fields, and trait-spec signatures expose named-tuple fields. In both cases `p.output.sentiment` is typed dot-access
  * with no lazy parsing.
  *
  * The companion exposes the canonical writer [[dspy4s.algebra.ScalaMonad]]: [[Prediction.map]] changes only the
  * semantic output, while [[Prediction.flatMap]] accumulates `RawPrediction` evidence in execution order. Its unit and
  * multiplication are natural transformations satisfying the categorical monad laws.
  */
final case class Prediction[O](
    output: O,
    raw   : RawPrediction
):
  def map[B](f: O => B): Prediction[B] =
    Prediction(f(output), raw)

  def flatMap[B](f: O => Prediction[B]): Prediction[B] =
    val next = f(output)
    Prediction(next.output, raw.followedBy(next.raw))

object Prediction:

  def pure[O](output: O): Prediction[O] = Prediction(output, RawPrediction.empty)

  given monad: ScalaMonad[Prediction] =
    ScalaMonad.fromComponents(
      [A, B] => (f: A => B) => (value: Prediction[A]) => value.map(f),
      [A] => (value: A) => Prediction.pure(value),
      [A] => (value: Prediction[Prediction[A]]) => value.flatMap(identity)
    )

  /** Lift the erased prediction boundary into the uniform module result. The dynamic semantic output is exactly the raw
    * prediction's value record; completions, usage, and adapter metadata remain available through [[Prediction.raw]].
    */
  def dynamic(raw: RawPrediction): Prediction[DynamicValue.Record] =
    Prediction(output = raw.values, raw = raw)

  /** Decodes a `RawPrediction` against an output `Shape`, lifting decode errors into the `Either` channel.
    *
    * Reads the field values directly from `raw.values`. Multi-completion decoding — picking a specific completion index
    * or producing one typed prediction per completion — is deferred to a later phase.
    */
  def from[O](raw: RawPrediction, outputShape: Shape[O]): Either[DspyError, Prediction[O]] =
    outputShape.decode(raw.values).map(o => Prediction(o, raw))
