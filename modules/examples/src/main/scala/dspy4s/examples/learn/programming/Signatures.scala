/** Signatures
  *
  * Source: docs/docs/learn/programming/signatures.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/signatures.md Status: translated (8/8
  * python snippets)
  *
  * Translation rule:
  *   - Python **string-based** signatures (e.g. `dspy.Predict("a -> b")`, `dspy.ChainOfThought("a -> b")`, or
  *     `dspy.Signature("a -> b")`) become Scala **function signatures** via `Signature.fromType[(in: I) => (out: O)]`.
  *   - Python **class-based** signatures (`class X(dspy.Signature): ...`) become Scala **spec traits** with
  *     `InputField[T]` / `OutputField[T]` members and `Signature.of[T <: Spec]`.
  *
  * Both surfaces produce a `Signature[I, O]` where `I` / `O` are named tuples, so call sites get direct dot access:
  *
  * Demo.run(Program.predict(id, sig), (field = "...")).map(_.output.field)
  *
  * `ChainOfThought` augments the output named tuple with `reasoning: String` and delegates through `Predict`. Snippets
  * 3, 4, and 6 use it directly.
  *
  * Structure note: each snippet is a self-contained block — heading comment + python original + supporting types (when
  * any) + example object. Supporting types (enums, case classes, spec traits) must stay at the package level for
  * zio-blocks-schema / Mirror derivation and for the trait-spec macro to see them, so the per-snippet block places them
  * immediately above the example object that uses them.
  */
package dspy4s.examples.learn.programming

import dspy4s.core.contracts.DspyError
import dspy4s.examples.Demo
import dspy4s.programs.{ChainOfThought, ParameterId, PredictionBackend, Program}
import dspy4s.signatures.{InputField, OutputField, Spec, Signature}
import zio.blocks.schema.Schema

// ═══════════════════════════════════════════════════════════════════════════
// Snippet 1 (lines 37–46) — string DSL with instructions
// ═══════════════════════════════════════════════════════════════════════════
// Python (string-based):
// | toxicity = dspy.Predict(
// |     dspy.Signature(
// |         "comment -> toxic: bool",
// |         instructions="Mark as 'toxic' if the comment includes insults, harassment, or sarcastic derogatory remarks.",
// |     )
// | )
// | comment = "you are beautiful."
// | toxicity(comment=comment).toxic

// --8<-- [start:toxicity]
object ToxicityExample:
  val signature = Signature.fromType[(comment: String) => (toxic: Boolean)](
    instructions = "Mark as 'toxic' if the comment includes insults, harassment, or sarcastic derogatory remarks."
  )

  val toxicity = Program.predict(ParameterId("signatures/toxicity"), signature)

  def call(comment: String)(using PredictionBackend): Either[DspyError, Boolean] =
    Demo.run(toxicity, (comment = comment)).map(_.output.toxic)
// --8<-- [end:toxicity]

// ═══════════════════════════════════════════════════════════════════════════
// Snippet 2 (lines 56–61) — Example A: Sentiment Classification
// ═══════════════════════════════════════════════════════════════════════════
// Python (string-based):
// | sentence = "it's a charming and often affecting journey."  # example from the SST-2 dataset.
// |
// | classify = dspy.Predict('sentence -> sentiment: bool')  # we'll see an example with Literal[] later
// | classify(sentence=sentence).sentiment

// --8<-- [start:sentiment]
object SentimentExample:
  val classify = Program.predict(
    ParameterId("signatures/sentiment"),
    Signature.fromType[(sentence: String) => (sentiment: Boolean)]
  )

  def call(sentence: String)(using PredictionBackend): Either[DspyError, Boolean] =
    Demo.run(classify, (sentence = sentence)).map(_.output.sentiment)
// --8<-- [end:sentiment]

// ═══════════════════════════════════════════════════════════════════════════
// Snippets 3 + 4 (lines 69–89) — Example B: Summarization with CoT + reasoning
// ═══════════════════════════════════════════════════════════════════════════
// Python (string-based, snippet 3):
// | # Example from the XSum dataset.
// | document = """..."""
// |
// | summarize = dspy.ChainOfThought('document -> summary')
// | response = summarize(document=document)
// |
// | print(response.summary)
//
// Python (snippet 4, inspect the reasoning):
// | print("Reasoning:", response.reasoning)
//
// `ChainOfThought` augments the output named tuple by prepending
// `reasoning: String`, so `tp.output.reasoning` and `tp.output.summary`
// are both direct dot accesses with no `.value(...)` indirection.

// --8<-- [start:summarize]
object SummarizeExample:
  val program = ChainOfThought(
    ParameterId("signatures/summarize"),
    Signature.fromType[(document: String) => (summary: String)]
  )

  /** Snippet 3: just the summary. */
  def call(document: String)(using PredictionBackend): Either[DspyError, String] =
    Demo.run(program, (document = document)).map(_.output.summary)

  /** Snippet 4: both reasoning and summary. */
  def callWithReasoning(document: String)(using PredictionBackend): Either[DspyError, (String, String)] =
    Demo.run(program, (document = document)).map { tp =>
      (tp.output.reasoning, tp.output.summary)
    }
// --8<-- [end:summarize]

// ═══════════════════════════════════════════════════════════════════════════
// Snippet 5 (lines 107–119) — Example C: class-based Classification
// ═══════════════════════════════════════════════════════════════════════════
// Python (class-based):
// | from typing import Literal
// |
// | class Emotion(dspy.Signature):
// |     """Classify emotion."""
// |
// |     sentence: str = dspy.InputField()
// |     sentiment: Literal['sadness', 'joy', 'love', 'anger', 'fear', 'surprise'] = dspy.OutputField()
// |
// | sentence = "i started feeling a little vulnerable when the giant spotlight started blinding me"
// |
// | classify = dspy.Predict(Emotion)
// | classify(sentence=sentence)

/** Python's `Literal[...]` becomes a top-level Scala enum. `derives Schema` gives it a flat-string wire form (the enum
  * case name) at both the top-level OutputField boundary and inside nested products.
  */
// --8<-- [start:emotion]
enum Emotion derives Schema:
  case sadness, joy, love, anger, fear, surprise

trait EmotionSpec extends Spec:
  def sentence: InputField[String]
  def sentiment: OutputField[Emotion]

object EmotionExample:
  val classify = Program.predict(
    ParameterId("signatures/emotion"),
    Signature.of[EmotionSpec](instructions = "Classify emotion.")
  )

  def call(sentence: String)(using PredictionBackend): Either[DspyError, Emotion] =
    Demo.run(classify, (sentence = sentence)).map(_.output.sentiment)
// --8<-- [end:emotion]

// ═══════════════════════════════════════════════════════════════════════════
// Snippet 6 (lines 132–146) — Example D: faithfulness check
// ═══════════════════════════════════════════════════════════════════════════
// Python (class-based):
// | class CheckCitationFaithfulness(dspy.Signature):
// |     """Verify that the text is based on the provided context."""
// |
// |     context: str = dspy.InputField(desc="facts here are assumed to be true")
// |     text: str = dspy.InputField()
// |     faithfulness: bool = dspy.OutputField()
// |     evidence: dict[str, list[str]] = dspy.OutputField(desc="Supporting evidence for claims")
//
// Note: per-field descriptions (`desc=...`) are not yet first-class on the
// trait-spec surface; only signature-level instructions are.

trait CheckCitationFaithfulnessSpec extends Spec:
  def context: InputField[String]
  def text: InputField[String]
  def faithfulness: OutputField[Boolean]
  def evidence: OutputField[Map[String, List[String]]]

object FaithfulnessExample:
  val signature = Signature.of[CheckCitationFaithfulnessSpec](
    instructions = "Verify that the text is based on the provided context."
  )

  val program = ChainOfThought(ParameterId("signatures/faithfulness"), signature)

// ═══════════════════════════════════════════════════════════════════════════
// Snippet 7 (lines 159–167) — Example E: multi-modal image
// ═══════════════════════════════════════════════════════════════════════════
// Python (class-based):
// | class DogPictureSignature(dspy.Signature):
// |     """Output the dog breed of the dog in the image."""
// |     image_1: dspy.Image = dspy.InputField(desc="An image of a dog")
// |     answer: str = dspy.OutputField(desc="The dog breed of the dog in the image")
// |
// | image_url = "https://picsum.photos/id/237/200/300"
// | classify = dspy.Predict(DogPictureSignature)
// | classify(image_1=dspy.Image.from_url(image_url))

/** Placeholder for `dspy.Image`. dspy4s does not yet ship a built-in `Image` value type with adapter support; this
  * local case class keeps the spec trait compilable. Replace with a real Image type when one lands.
  */
case class Image(url: String) derives Schema

trait DogPictureSpec extends Spec:
  def image_1: InputField[Image]
  def answer: OutputField[String]

object DogPictureExample:
  val signature = Signature.of[DogPictureSpec](
    instructions = "Output the dog breed of the dog in the image."
  )

  val program = Program.predict(ParameterId("signatures/dog-picture"), signature)

  def call(imageUrl: String)(using PredictionBackend): Either[DspyError, String] =
    Demo.run(program, (image_1 = Image(imageUrl))).map(_.output.answer)

// ═══════════════════════════════════════════════════════════════════════════
// Snippet 8 (lines 190–204) — Working with Custom Types
// ═══════════════════════════════════════════════════════════════════════════
// Python (string-based with custom types):
// | # Simple custom type
// | class QueryResult(pydantic.BaseModel):
// |     text: str
// |     score: float
// |
// | signature = dspy.Signature("query: str -> result: QueryResult")
// |
// | class MyContainer:
// |     class Query(pydantic.BaseModel):
// |         text: str
// |     class Score(pydantic.BaseModel):
// |         score: float
// |
// | signature = dspy.Signature("query: MyContainer.Query -> score: MyContainer.Score")
//
// Pydantic models port to Scala case classes with `derives Schema`. The
// `Schema` drives both the field's wire `TypeRef` and its nested encode/decode
// at the signature boundary.

// --8<-- [start:custom-types]
case class QueryResult(text: String, score: Double) derives Schema

object MyContainer:
  case class Query(text: String) derives Schema
  case class Score(score: Double) derives Schema

object CustomTypesExample:
  val signature = Signature.fromType[(query: String) => (result: QueryResult)]

  val nestedSignature = Signature.fromType[
    (query: MyContainer.Query) => (score: MyContainer.Score)
  ]
// --8<-- [end:custom-types]

// ═══════════════════════════════════════════════════════════════════════════
// ProgramRunner entrypoint
// ═══════════════════════════════════════════════════════════════════════════
// The example objects above only declare programs; running them needs a
// `PredictionBackend`. `Demo.withLm` creates the live OpenAI backend from
// `OPENAI_API_KEY` and exposes it to the call methods.
//
// Run with:  OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.main"
// --8<-- [start:run]
@main def main(): Unit =
  Demo.withLm {
    println("Toxicity:  " + ToxicityExample.call("you are beautiful."))
    println("Sentiment: " + SentimentExample.call("it's a charming and often affecting journey."))
    println("Emotion:   " + EmotionExample.call("i started feeling a little vulnerable"))
    println("Summary:   " + SummarizeExample.call("The cat sat on the mat. The sun was warm."))
  }
// --8<-- [end:run]
