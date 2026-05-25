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
 *     `TypedSignature.fromType[(in: I) => (out: O)]("Name")`.
 *   - Python **class-based** signatures (`class X(dspy.Signature): ...`)
 *     become Scala **spec traits** with `InputField[T]` / `OutputField[T]`
 *     members and `TypedSignature.of[T <: Spec]`.
 *
 * Both surfaces produce a `TypedSignature[I, O]` where `I` / `O` are named
 * tuples (or case classes in the case-class derivation surface — not used
 * here), so call sites get typed dot-access:
 *
 *   TypedPredict(sig).run((field = "...")).map(_.output.field)
 *
 * `ChainOfThought` has a typed counterpart `TypedChainOfThought` that
 * augments the output named tuple with `reasoning: String` (the field CoT
 * injects at the runtime layer). Snippets 3, 4, and 6 use it directly.
 */
package dspy4s.examples.learn.programming

import dspy4s.core.contracts.{DspyError, RuntimeContext, TypeRef}
import dspy4s.programs.{TypedChainOfThought, TypedPredict}
import dspy4s.typed.{FieldCodec, InputField, OutputField, Spec, TypedSignature}
import kyo.Schema

// ── Top-level types referenced by the translations below ─────────────────
// Top-level so Schema / Mirror derivation and the trait-spec macro see them.

/** Python's `Literal['sadness', 'joy', ...]` (snippet 5) becomes a Scala
  * enum. `FieldCodec.FlatEnum` gives the companion both a `FieldCodec`
  * (for top-level OutputField use) and a flat-string `Schema` (for
  * nested-product use). */
enum Emotion:
  case sadness, joy, love, anger, fear, surprise

object Emotion extends FieldCodec.FlatEnum[Emotion]

/** Placeholder for `dspy.Image` (snippet 7). dspy4s does not yet ship a
  * built-in `Image` value type with adapter support; this local case class
  * keeps the spec trait compilable. Replace with a real Image type when
  * one lands. */
case class Image(url: String) derives Schema

/** Custom output type (snippet 8). */
case class QueryResult(text: String, score: Double) derives Schema

/** Nested-namespace custom types (snippet 8, second half). */
object MyContainer:
  case class Query(text: String) derives Schema
  case class Score(score: Double) derives Schema

/** `Map[String, List[String]]` (snippet 6) is not `<: Product`, so it
  * doesn't pick up the `schemaBackedProduct` low-priority fallback. We
  * provide an explicit `FieldCodec` over kyo-schema's collection Schema. */
given FieldCodec[Map[String, List[String]]] =
  FieldCodec.fromSchema[Map[String, List[String]]](typeRef = TypeRef.json)

// ── Class-based spec traits (snippets 5, 6, 7) ───────────────────────────

/** Snippet 5: `class Emotion(dspy.Signature)` → spec trait. */
trait EmotionSpec extends Spec:
  def sentence:  InputField[String]
  def sentiment: OutputField[Emotion]

/** Snippet 6: `class CheckCitationFaithfulness(dspy.Signature)` → spec trait. */
trait CheckCitationFaithfulnessSpec extends Spec:
  def context:      InputField[String]
  def text:         InputField[String]
  def faithfulness: OutputField[Boolean]
  def evidence:     OutputField[Map[String, List[String]]]

/** Snippet 7: `class DogPictureSignature(dspy.Signature)` → spec trait. */
trait DogPictureSpec extends Spec:
  def image_1: InputField[Image]
  def answer:  OutputField[String]

object Signatures:

  /** Small helper: `TypedSignature.fromType` doesn't take an `instructions`
    * parameter, so attach them by re-wrapping the untyped signature. */
  extension [I, O](sig: TypedSignature[I, O])
    def withInstructions(text: String): TypedSignature[I, O] =
      sig.copy(untyped = sig.untyped.withInstructions(text))

  // ── Snippet 1 (lines 37–46) ─ string DSL with instructions ────────────
  // Python (string-based):
  // | toxicity = dspy.Predict(
  // |     dspy.Signature(
  // |         "comment -> toxic: bool",
  // |         instructions="Mark as 'toxic' if the comment includes insults, harassment, or sarcastic derogatory remarks.",
  // |     )
  // | )
  // | comment = "you are beautiful."
  // | toxicity(comment=comment).toxic
  val toxicitySig =
    TypedSignature
      .fromType[(comment: String) => (toxic: Boolean)]("Toxicity")
      .withInstructions(
        "Mark as 'toxic' if the comment includes insults, harassment, or sarcastic derogatory remarks."
      )

  val toxicity = TypedPredict(toxicitySig)

  def callToxicity(comment: String)(using RuntimeContext): Either[DspyError, Boolean] =
    toxicity.run((comment = comment)).map(_.output.toxic)

  // ── Snippet 2 (lines 56–61) ─ Example A: Sentiment Classification ─────
  // Python (string-based):
  // | sentence = "it's a charming and often affecting journey."  # example from the SST-2 dataset.
  // |
  // | classify = dspy.Predict('sentence -> sentiment: bool')  # we'll see an example with Literal[] later
  // | classify(sentence=sentence).sentiment
  val sentimentSig =
    TypedSignature.fromType[(sentence: String) => (sentiment: Boolean)]("ClassifySentiment")

  val classifySentiment = TypedPredict(sentimentSig)

  def callSentiment(sentence: String)(using RuntimeContext): Either[DspyError, Boolean] =
    classifySentiment.run((sentence = sentence)).map(_.output.sentiment)

  // ── Snippet 3 (lines 69–77) ─ Example B: Summarization (ChainOfThought) ─
  // Python (string-based):
  // | # Example from the XSum dataset.
  // | document = """..."""
  // |
  // | summarize = dspy.ChainOfThought('document -> summary')
  // | response = summarize(document=document)
  // |
  // | print(response.summary)
  //
  // `TypedChainOfThought` augments the output named tuple by prepending
  // `reasoning: String`, so `tp.output.reasoning` and `tp.output.summary`
  // are both typed dot-accesses with no `.value(...)` indirection.
  val summarizeSig =
    TypedSignature.fromType[(document: String) => (summary: String)]("Summarize")

  val summarize = TypedChainOfThought(summarizeSig)

  def callSummarize(document: String)(using RuntimeContext): Either[DspyError, String] =
    summarize.run((document = document)).map(_.output.summary)

  // ── Snippet 4 (lines 87–89) ─ inspect the reasoning field ─────────────
  // Python:
  // | print("Reasoning:", response.reasoning)
  //
  // CoT injects a `reasoning` field at position 0 of the augmented output.
  // With TypedChainOfThought that field shows up as
  // `tp.output.reasoning: String` — no dynamic lookup needed.
  def callSummarizeWithReasoning(document: String)(using RuntimeContext)
      : Either[DspyError, (String, String)] =
    summarize.run((document = document)).map { tp =>
      (tp.output.reasoning, tp.output.summary)
    }

  // ── Snippet 5 (lines 107–119) ─ Example C: class-based Classification ──
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
  //
  // Python's `Literal[...]` becomes the top-level `Emotion` enum. The trait
  // spec carries the same role + type metadata; the docstring becomes
  // instructions via `.withInstructions`.
  val emotionSig =
    TypedSignature.of[EmotionSpec].withInstructions("Classify emotion.")

  val classifyEmotion = TypedPredict(emotionSig)

  def callClassifyEmotion(sentence: String)(using RuntimeContext): Either[DspyError, Emotion] =
    classifyEmotion.run((sentence = sentence)).map(_.output.sentiment)

  // ── Snippet 6 (lines 132–146) ─ Example D: faithfulness check ─────────
  // Python (class-based):
  // | class CheckCitationFaithfulness(dspy.Signature):
  // |     """Verify that the text is based on the provided context."""
  // |
  // |     context: str = dspy.InputField(desc="facts here are assumed to be true")
  // |     text: str = dspy.InputField()
  // |     faithfulness: bool = dspy.OutputField()
  // |     evidence: dict[str, list[str]] = dspy.OutputField(desc="Supporting evidence for claims")
  //
  // Note: per-field descriptions (`desc=...`) are not yet first-class on
  // the trait-spec surface; metadata flows through `FieldCodec.metadata`.
  val faithfulnessSig =
    TypedSignature
      .of[CheckCitationFaithfulnessSpec]
      .withInstructions("Verify that the text is based on the provided context.")

  val faithfulness = TypedChainOfThought(faithfulnessSig)

  // ── Snippet 7 (lines 159–167) ─ Example E: multi-modal image ──────────
  // Python (class-based):
  // | class DogPictureSignature(dspy.Signature):
  // |     """Output the dog breed of the dog in the image."""
  // |     image_1: dspy.Image = dspy.InputField(desc="An image of a dog")
  // |     answer: str = dspy.OutputField(desc="The dog breed of the dog in the image")
  // |
  // | image_url = "https://picsum.photos/id/237/200/300"
  // | classify = dspy.Predict(DogPictureSignature)
  // | classify(image_1=dspy.Image.from_url(image_url))
  //
  // Placeholder `Image` type defined above. Until dspy4s ships a real
  // image value type with adapter support, callers construct
  // `Image("https://...")` directly.
  val dogPictureSig =
    TypedSignature
      .of[DogPictureSpec]
      .withInstructions("Output the dog breed of the dog in the image.")

  val classifyDog = TypedPredict(dogPictureSig)

  def callClassifyDog(imageUrl: String)(using RuntimeContext): Either[DspyError, String] =
    classifyDog.run((image_1 = Image(imageUrl))).map(_.output.answer)

  // ── Snippet 8 (lines 190–204) ─ Working with Custom Types ─────────────
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
  // Pydantic models port to Scala case classes with `derives Schema`.
  // Their `FieldCodec` comes from the `schemaBackedProduct` fallback,
  // which fires automatically for any `<: Product` type with a `Schema`
  // in scope.
  val customTypeSig =
    TypedSignature.fromType[(query: String) => (result: QueryResult)]("CustomType")

  val nestedCustomTypeSig =
    TypedSignature.fromType[
      (query: MyContainer.Query) => (score: MyContainer.Score)
    ]("Nested")
