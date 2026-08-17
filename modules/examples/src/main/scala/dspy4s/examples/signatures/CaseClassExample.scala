/** Signatures — case-class surface.
  *
  * Mirrors DSPy's class-based signature style (snippet "Emotion" from docs/docs/learn/programming/signatures.md).
  * Compiles against the dspy4s engine; no live LM calls. Run shapes below are illustrative.
  *
  * Status: example
  */
package dspy4s.examples.signatures

import zio.blocks.schema.Schema

import dspy4s.core.contracts.{DspyError, :=}
import dspy4s.core.data.RawPrediction
import dspy4s.examples.Demo
import dspy4s.programs.{PredictionBackend, Program}
import dspy4s.programs.contracts.Prediction
import dspy4s.signatures.Signature

// Top-level types: Mirror derivation needs top-level case classes, and the
// enum's Schema must come from outside any enclosing class.
// --8<-- [start:derived-types]
case class EmotionInput(sentence: String) derives Schema

enum Emotion derives Schema:
  case sadness, joy, love, anger, fear, surprise

case class EmotionOutput(sentiment: Emotion) derives Schema
// --8<-- [end:derived-types]

/** Build a `Signature` from two case classes — one for inputs, one for outputs. The resulting signature is
  * compiler-checked at the program boundary:
  *
  *   - encode: `Program.predict(signature)` accepts `EmotionInput`; the interpreter encodes it into a record.
  *   - decode: `Prediction.output` is an `EmotionOutput`, so `tp.output.sentiment` has type `Emotion` with no runtime
  *     cast.
  *   - enum constraints reach the LM via `Shape.jsonSchemaString` (rendered from the backing `Schema[O]`); the
  *     `JSONAdapter` inlines that schema into its prompt.
  */
object CaseClassExample:

  // --8<-- [start:derived-sig]
  val signature: Signature[EmotionInput, EmotionOutput] = Signature.derived[EmotionInput, EmotionOutput](
    name = "Emotion",
    instructions = "Classify emotion in the given sentence."
  )
  // --8<-- [end:derived-sig]

  /** A prediction is passive syntax. `Demo.run` supplies the explicit backend to `ProgramRunner`. */
  val classifyProgram = Program.predict(signature)

  def classify(sentence: String)(using PredictionBackend): Either[DspyError, Emotion] =
    Demo.run(classifyProgram, EmotionInput(sentence)).map(_.output.sentiment)

  /** Offline demonstration: build a `Prediction` from a raw prediction map without invoking an LM. Useful for tests and
    * for showing the decode boundary.
    */
  def fromRawValues(rawSentiment: String): Either[DspyError, Prediction[EmotionOutput]] =
    val raw =
      RawPrediction(values = dspy4s.core.contracts.DynamicValues.recordFromEntries(Vector("sentiment" := rawSentiment)))
    Prediction.from(raw, signature.outputShape)

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.signatures.caseClassMain"
@main def caseClassMain(): Unit =
  Demo.withLm {
    println("CaseClass: " + CaseClassExample.classify("i started feeling a little vulnerable"))
  }
