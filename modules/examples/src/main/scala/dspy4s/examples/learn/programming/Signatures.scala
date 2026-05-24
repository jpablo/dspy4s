/**
 * Signatures
 *
 * Source:   docs/docs/learn/programming/signatures.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/signatures.md
 * Status:   translated (8/8 python snippets)
 *
 * Notes on porting:
 *   - In dspy4s, `dspy.Signature(...)` is built either from the inline DSL
 *     via `Signature("inputs -> outputs", instructions = "...")` or
 *     constructed explicitly via `SignatureDsl.create(name, inputFields,
 *     outputFields, instructions)` for class-based signatures.
 *   - There is no metaclass-driven `class X(dspy.Signature)` form — class-based
 *     signatures port to `SignatureDsl.create` with `FieldSpec` entries.
 *   - `Signature.apply` returns `Either[DspyError, Signature]` so parse errors
 *     stay strongly typed; bind via for-comprehension or `.toOption.get` at
 *     declarative call sites.
 *   - Programs are invoked with the varargs sugar
 *     `program.run("field" -> value, ...)` (or the explicit
 *     `program.run(ProgramCall(...))` when `config`/`traceEnabled` need to
 *     be customized) and return `Either[DspyError, Prediction]`.
 *   - The `// Python:` blocks below preserve the original snippets verbatim
 *     for reference; the Scala translations follow.
 */
package dspy4s.examples.learn.programming

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.Signature
import dspy4s.core.contracts.TypeRef
import dspy4s.core.signatures.SignatureDsl
import dspy4s.programs.ChainOfThought
import dspy4s.programs.Predict

object Signatures {

  // ── Snippet 1 (lines 37–46) ─ inline signature + instructions ─────────
  // Python:
  // | toxicity = dspy.Predict(
  // |     dspy.Signature(
  // |         "comment -> toxic: bool",
  // |         instructions="Mark as 'toxic' if the comment includes insults, harassment, or sarcastic derogatory remarks.",
  // |     )
  // | )
  // | comment = "you are beautiful."
  // | toxicity(comment=comment).toxic
  val toxicitySignature: Signature =
    Signature(
      "comment -> toxic: bool",
      instructions = "Mark as 'toxic' if the comment includes insults, harassment, or sarcastic derogatory remarks."
    ).toOption.get

  val toxicity: Predict = Predict(toxicitySignature)

  def callToxicity(comment: String)(using RuntimeContext): Either[DspyError, Any] =
    toxicity.run("comment" -> comment).flatMap(_.value("toxic"))

  // ── Snippet 2 (lines 56–61) ─ Example A: Sentiment Classification ─────
  // Python:
  // | sentence = "it's a charming and often affecting journey."  # example from the SST-2 dataset.
  // |
  // | classify = dspy.Predict('sentence -> sentiment: bool')  # we'll see an example with Literal[] later
  // | classify(sentence=sentence).sentiment
  val sentimentSignature: Signature =
    Signature("sentence -> sentiment: bool").toOption.get

  val classifySentiment: Predict = Predict(sentimentSignature)

  def callSentiment(sentence: String)(using RuntimeContext): Either[DspyError, Any] =
    classifySentiment.run("sentence" -> sentence).flatMap(_.value("sentiment"))

  // ── Snippet 3 (lines 69–77) ─ Example B: Summarization (ChainOfThought) ─
  // Python:
  // | # Example from the XSum dataset.
  // | document = """The 21-year-old made seven appearances ..."""
  // |
  // | summarize = dspy.ChainOfThought('document -> summary')
  // | response = summarize(document=document)
  // |
  // | print(response.summary)
  val summarizeSignature: Signature =
    Signature("document -> summary").toOption.get

  val summarize: ChainOfThought = ChainOfThought(summarizeSignature)

  def callSummarize(document: String)(using RuntimeContext): Either[DspyError, Any] =
    summarize.run("document" -> document).flatMap(_.value("summary"))

  // ── Snippet 4 (lines 87–89) ─ inspect the reasoning field ─────────────
  // Python:
  // | print("Reasoning:", response.reasoning)
  //
  // ChainOfThought injects a `reasoning` output field (prefix "Reasoning:") at
  // position 0 of the augmented signature, so the resulting Prediction exposes
  // both `reasoning` and `summary`.
  def callSummarizeWithReasoning(document: String)(using RuntimeContext): Either[DspyError, (Any, Any)] =
    summarize.run("document" -> document).flatMap { p =>
      for
        reasoning <- p.value("reasoning")
        summary <- p.value("summary")
      yield (reasoning, summary)
    }

  // ── Snippet 5 (lines 107–119) ─ Example C: class-based Classification ──
  // Python:
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
  // Python's `Literal[...]` carries through as a free-form TypeRef token; the
  // adapter consumes the repr when formatting/parsing.
  val emotionSignature: Signature =
    SignatureDsl
      .create(
        name = "Emotion",
        inputFields = Vector(FieldSpec(name = "sentence", role = FieldRole.Input)),
        outputFields = Vector(
          FieldSpec(
            name = "sentiment",
            role = FieldRole.Output,
            typeRef = TypeRef("Literal['sadness', 'joy', 'love', 'anger', 'fear', 'surprise']")
          )
        ),
        instructions = Some("Classify emotion.")
      )
      .toOption
      .get

  val classifyEmotion: Predict = Predict(emotionSignature)

  // ── Snippet 6 (lines 132–146) ─ Example D: faithfulness check ─────────
  // Python:
  // | class CheckCitationFaithfulness(dspy.Signature):
  // |     """Verify that the text is based on the provided context."""
  // |
  // |     context: str = dspy.InputField(desc="facts here are assumed to be true")
  // |     text: str = dspy.InputField()
  // |     faithfulness: bool = dspy.OutputField()
  // |     evidence: dict[str, list[str]] = dspy.OutputField(desc="Supporting evidence for claims")
  val checkCitationFaithfulness: Signature =
    SignatureDsl
      .create(
        name = "CheckCitationFaithfulness",
        inputFields = Vector(
          FieldSpec(
            name = "context",
            role = FieldRole.Input,
            description = Some("facts here are assumed to be true")
          ),
          FieldSpec(name = "text", role = FieldRole.Input)
        ),
        outputFields = Vector(
          FieldSpec(name = "faithfulness", role = FieldRole.Output, typeRef = TypeRef.bool),
          FieldSpec(
            name = "evidence",
            role = FieldRole.Output,
            typeRef = TypeRef("dict[str, list[str]]"),
            description = Some("Supporting evidence for claims")
          )
        ),
        instructions = Some("Verify that the text is based on the provided context.")
      )
      .toOption
      .get

  val faithfulness: ChainOfThought = ChainOfThought(checkCitationFaithfulness)

  // ── Snippet 7 (lines 159–167) ─ Example E: multi-modal image ──────────
  // Python:
  // | class DogPictureSignature(dspy.Signature):
  // |     """Output the dog breed of the dog in the image."""
  // |     image_1: dspy.Image = dspy.InputField(desc="An image of a dog")
  // |     answer: str = dspy.OutputField(desc="The dog breed of the dog in the image")
  // |
  // | image_url = "https://picsum.photos/id/237/200/300"
  // | classify = dspy.Predict(DogPictureSignature)
  // | classify(image_1=dspy.Image.from_url(image_url))
  //
  // dspy4s does not yet ship a built-in `Image` value type (no equivalent of
  // `dspy.Image.from_url(...)`). The field below carries the type as an opaque
  // TypeRef token so the signature compiles; the adapter layer will need an
  // Image type before this example can actually run.
  val dogPictureSignature: Signature =
    SignatureDsl
      .create(
        name = "DogPictureSignature",
        inputFields = Vector(
          FieldSpec(
            name = "image_1",
            role = FieldRole.Input,
            typeRef = TypeRef("image"),
            description = Some("An image of a dog")
          )
        ),
        outputFields = Vector(
          FieldSpec(
            name = "answer",
            role = FieldRole.Output,
            description = Some("The dog breed of the dog in the image")
          )
        ),
        instructions = Some("Output the dog breed of the dog in the image.")
      )
      .toOption
      .get

  val classifyDog: Predict = Predict(dogPictureSignature)

  // ── Snippet 8 (lines 190–204) ─ Working with Custom Types ─────────────
  // Python:
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
  // In dspy4s the type token is a free-form `TypeRef` consumed by the adapter
  // (JSON / structured-output adapters). There is no pydantic-style schema
  // derivation today, so the user-defined types appear as opaque labels —
  // the meaning is supplied by the adapter that knows how to render/parse
  // values of that shape.
  val customTypeSignature: Signature =
    Signature("query: str -> result: QueryResult").toOption.get

  val nestedCustomTypeSignature: Signature =
    Signature("query: MyContainer.Query -> score: MyContainer.Score").toOption.get
}
