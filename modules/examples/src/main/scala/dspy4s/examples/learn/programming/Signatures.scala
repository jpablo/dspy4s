/**
 * Signatures
 *
 * Source:   docs/docs/learn/programming/signatures.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/signatures.md
 * Status:   translated (8/8 python snippets)
 *
 * Translation rule:
 *   - Python **string-based** signatures (e.g. `dspy.Predict("a -> b")`,
 *     `dspy.ChainOfThought("a -> b")`, or `dspy.Signature("a -> b")`)
 *     become Scala **function signatures** via
 *     `TypedSignature.fromType[(in: I) => (out: O)]`.
 *   - Python **class-based** signatures (`class X(dspy.Signature): ...`)
 *     become Scala **spec traits** with `InputField[T]` / `OutputField[T]`
 *     members and `TypedSignature.of[T <: Spec]`.
 *
 * Both surfaces produce a `TypedSignature[I, O]` where `I` / `O` are named
 * tuples, so call sites get typed dot-access:
 *
 *   TypedPredict(sig).run((field = "...")).map(_.output.field)
 *
 * `ChainOfThought` has a typed counterpart `TypedChainOfThought` that
 * augments the output named tuple with `reasoning: String` (the field CoT
 * injects at the runtime layer). Snippets 3, 4, and 6 use it directly.
 *
 * Structure note: each snippet is a self-contained block — heading
 * comment + python original + supporting types (when any) + example
 * object. Supporting types (enums, case classes, `given`s, spec traits)
 * must stay at the package level for kyo-schema / Mirror derivation and
 * for the trait-spec macro to see them, so the per-snippet block places
 * them immediately above the example object that uses them.
 */
package dspy4s.examples.learn.programming

import dspy4s.core.contracts.{DspyError, RuntimeContext, TypeRef}
import dspy4s.programs.{TypedChainOfThought, TypedPredict}
import dspy4s.typed.{FieldCodec, InputField, OutputField, Spec, TypedSignature}
import kyo.Schema

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

object ToxicityExample:
  val signature =
    TypedSignature.fromType[(comment: String) => (toxic: Boolean)](
      instructions =
        "Mark as 'toxic' if the comment includes insults, harassment, or sarcastic derogatory remarks."
    )

  val toxicity = TypedPredict(signature)

  def call(comment: String)(using RuntimeContext): Either[DspyError, Boolean] =
    toxicity.run((comment = comment)).map(_.output.toxic)

// ═══════════════════════════════════════════════════════════════════════════
// Snippet 2 (lines 56–61) — Example A: Sentiment Classification
// ═══════════════════════════════════════════════════════════════════════════
// Python (string-based):
// | sentence = "it's a charming and often affecting journey."  # example from the SST-2 dataset.
// |
// | classify = dspy.Predict('sentence -> sentiment: bool')  # we'll see an example with Literal[] later
// | classify(sentence=sentence).sentiment

object SentimentExample:
  val classify = TypedPredict(TypedSignature.fromType[(sentence: String) => (sentiment: Boolean)])

  def call(sentence: String)(using RuntimeContext): Either[DspyError, Boolean] =
    classify.run((sentence = sentence)).map(_.output.sentiment)

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
// `TypedChainOfThought` augments the output named tuple by prepending
// `reasoning: String`, so `tp.output.reasoning` and `tp.output.summary`
// are both typed dot-accesses with no `.value(...)` indirection.

object SummarizeExample:
  val program = TypedChainOfThought(TypedSignature.fromType[(document: String) => (summary: String)])

  /** Snippet 3: just the summary. */
  def call(document: String)(using RuntimeContext): Either[DspyError, String] =
    program.run((document = document)).map(_.output.summary)

  /** Snippet 4: both reasoning and summary. */
  def callWithReasoning(document: String)(using RuntimeContext): Either[DspyError, (String, String)] =
    program.run((document = document)).map { tp =>
      (tp.output.reasoning, tp.output.summary)
    }

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

/** Python's `Literal[...]` becomes a top-level Scala enum.
  * `FieldCodec.FlatEnum` gives the companion both a `FieldCodec` (for
  * top-level OutputField use) and a flat-string `Schema` (for nested-
  * product use). */
enum Emotion:
  case sadness, joy, love, anger, fear, surprise

object Emotion extends FieldCodec.FlatEnum[Emotion]

trait EmotionSpec extends Spec:
  def sentence:  InputField[String]
  def sentiment: OutputField[Emotion]

object EmotionExample:
  val classify = TypedPredict(TypedSignature.of[EmotionSpec](instructions = "Classify emotion."))

  def call(sentence: String)(using RuntimeContext): Either[DspyError, Emotion] =
    classify.run((sentence = sentence)).map(_.output.sentiment)

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

/** `Map[String, List[String]]` is not `<: Product`, so it doesn't pick
  * up the `schemaBackedProduct` low-priority fallback. Provide an
  * explicit `FieldCodec` over kyo-schema's collection Schema. */
given FieldCodec[Map[String, List[String]]] =
  FieldCodec.fromSchema[Map[String, List[String]]](typeRef = TypeRef.json)

trait CheckCitationFaithfulnessSpec extends Spec:
  def context:      InputField[String]
  def text:         InputField[String]
  def faithfulness: OutputField[Boolean]
  def evidence:     OutputField[Map[String, List[String]]]

object FaithfulnessExample:
  val signature =
    TypedSignature.of[CheckCitationFaithfulnessSpec](
      instructions = "Verify that the text is based on the provided context."
    )

  val program = TypedChainOfThought(signature)

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

/** Placeholder for `dspy.Image`. dspy4s does not yet ship a built-in
  * `Image` value type with adapter support; this local case class keeps
  * the spec trait compilable. Replace with a real Image type when one
  * lands. */
case class Image(url: String) derives Schema

trait DogPictureSpec extends Spec:
  def image_1: InputField[Image]
  def answer:  OutputField[String]

object DogPictureExample:
  val signature =
    TypedSignature.of[DogPictureSpec](
      instructions = "Output the dog breed of the dog in the image."
    )

  val program = TypedPredict(signature)

  def call(imageUrl: String)(using RuntimeContext): Either[DspyError, String] =
    program.run((image_1 = Image(imageUrl))).map(_.output.answer)

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
// Pydantic models port to Scala case classes with `derives Schema`. Their
// `FieldCodec` comes from the `schemaBackedProduct` fallback, which fires
// automatically for any `<: Product` type with a `Schema` in scope.

case class QueryResult(text: String, score: Double) derives Schema

object MyContainer:
  case class Query(text: String) derives Schema
  case class Score(score: Double) derives Schema

object CustomTypesExample:
  val signature =
    TypedSignature.fromType[(query: String) => (result: QueryResult)]

  val nestedSignature =
    TypedSignature.fromType[
      (query: MyContainer.Query) => (score: MyContainer.Score)
    ]
