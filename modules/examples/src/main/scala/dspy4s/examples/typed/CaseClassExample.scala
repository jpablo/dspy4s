/**
 * Typed signatures — case-class surface.
 *
 * Mirrors DSPy's class-based signature style (snippet "Emotion" from
 * docs/docs/learn/programming/signatures.md). Compiles against the dspy4s
 * typed engine; no live LM calls. Run shapes below are illustrative.
 *
 * Status: example
 */
package dspy4s.examples.typed

import zio.blocks.schema.Schema

import dspy4s.core.contracts.{DspyError, DynamicPrediction, RuntimeContext}
import dspy4s.typed.{Prediction, Signature, FieldCodec}

// Top-level types: Mirror derivation needs top-level case classes, and the
// enum's Schema must come from outside any enclosing class.
case class EmotionInput(sentence: String) derives Schema

enum Emotion:
  case sadness, joy, love, anger, fear, surprise

object Emotion extends FieldCodec.FlatEnum[Emotion]

case class EmotionOutput(sentiment: Emotion) derives Schema

/**
 * Build a `Signature` from two case classes — one for inputs, one for
 * outputs. The resulting signature is fully typed at the program boundary:
 *
 *   - encode: `Predict.run(EmotionInput("..."))` accepts a typed value;
 *     the typed shape encodes it into the `ProgramCall.inputs` map.
 *   - decode: `Prediction.output` is a typed `EmotionOutput`, so
 *     `tp.output.sentiment` has type `Emotion` with no runtime cast.
 *   - metadata: enum-typed fields surface their allowed cases through
 *     `FieldSpec.metadata` (under `FieldMetadata.EnumCases`) so adapters
 *     can render valid options in prompts.
 */
object CaseClassExample:

  val signature: Signature[EmotionInput, EmotionOutput] =
    Signature.derived[EmotionInput, EmotionOutput](
      name         = "Emotion",
      instructions = "Classify emotion in the given sentence."
    )

  /** Illustrative call site. With an LM and adapter configured in
    * `RuntimeContext`, `Predict(signature).run(...)` returns
    * `Either[DspyError, Prediction[EmotionOutput]]`. */
  def classify(sentence: String)(using RuntimeContext): Either[DspyError, Emotion] =
    import dspy4s.programs.Predict
    Predict(signature)
      .run(EmotionInput(sentence))
      .map(_.output.sentiment)

  /** Offline demonstration: build a `Prediction` from a raw prediction
    * map without invoking an LM. Useful for tests and for showing the
    * decode boundary. */
  def fromRawValues(rawSentiment: String): Either[DspyError, Prediction[EmotionOutput]] =
    val raw = DynamicPrediction(values =
      dspy4s.core.contracts.DynamicValues.recordFromEntries(Vector("sentiment" -> rawSentiment))
    )
    Prediction.from(raw, signature.outputShape)
