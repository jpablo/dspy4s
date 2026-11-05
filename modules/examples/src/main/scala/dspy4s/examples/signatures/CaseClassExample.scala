/** Signatures — case-class surface.
  *
  * Mirrors DSPy's class-based signature style (snippet "Emotion" from docs/docs/learn/programming/signatures.md).
  * `caseClassMain` calls an LM.
  *
  * Status: example
  */
package dspy4s.examples.signatures

import zio.blocks.schema.Schema

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.examples.Demo
import dspy4s.signatures.Signature

// These types are at the top level so that their names are short. A type in an object also works, as
// `MyContainer.Query` in learn/programming/Signatures.scala shows.
// --8<-- [start:derived-types]
case class EmotionInput(sentence: String) derives Schema

enum Emotion derives Schema:
  case sadness, joy, love, anger, fear, surprise

case class EmotionOutput(sentiment: Emotion) derives Schema
// --8<-- [end:derived-types]

/** Build a `Signature` from two case classes — one for inputs, one for outputs. The resulting signature is
  * compiler-checked at the program boundary:
  *
  *   - encode: `Predict(signature)(EmotionInput("..."))` accepts a value; the shape encodes it into the dynamic
  *     `ProgramCall.input` record.
  *   - decode: `Prediction.output` is an `EmotionOutput`, so `tp.output.sentiment` has type `Emotion` with no runtime
  *     cast.
  *   - enum constraints reach the LM through `FieldSpec.enumValues`: the prompt of each adapter names the allowed
  *     values of the field (`PromptText.outputNote`). The `JSONAdapter` also sends `Shape.jsonSchema` as the
  *     `response_format` of the request, when the model supports a response schema.
  */
object CaseClassExample:

  // --8<-- [start:derived-sig]
  val signature: Signature[EmotionInput, EmotionOutput] = Signature.derived[EmotionInput, EmotionOutput](
    name = "Emotion",
    instructions = "Classify emotion in the given sentence."
  )
  // --8<-- [end:derived-sig]

  /** With an LM and adapter configured in `RuntimeContext`, `Predict(signature)(...)` returns
    * `Either[DspyError, Prediction[EmotionOutput]]`.
    */
  def classify(sentence: String)(using RuntimeContext): Either[DspyError, Emotion] =
    import dspy4s.programs.strategies.Predict
    Predict(signature)(EmotionInput(sentence))
      .map(_.output.sentiment)

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.signatures.caseClassMain"
@main def caseClassMain(): Unit =
  Demo.withLm {
    println("CaseClass: " + CaseClassExample.classify("i started feeling a little vulnerable"))
  }
