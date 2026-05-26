package dspy4s.typed

import zio.blocks.schema.Schema

import dspy4s.core.contracts.{
  DspyError, FieldRole, NotFoundError, DynamicPrediction, ValidationError
}
import munit.FunSuite

// Top-level: Schema/Mirror derivation does not work for path-dependent types
// declared inside test classes (Phase 0 finding).
case class P2SentenceInput(sentence: String) derives Schema
case class P2ScoredSentiment(sentiment: String, confidence: Double) derives Schema

enum P2Sentiment:
  case sadness, joy, love, anger, fear, surprise

object P2Sentiment extends FieldCodec.FlatEnum[P2Sentiment]

case class P2EnumOutput(sentiment: P2Sentiment) derives Schema

case class P2TwoInputs(question: String, context: String) derives Schema
case class P2TwoOutputs(answer: String, score: Double) derives Schema

/** Phase 2 typed-core suite per docs/TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md.
  *
  * Maps 1:1 to the plan's Phase 2 test list. */
class Phase2TypedCoreSuite extends FunSuite:

  // ── Shape derivation: field metadata ─────────────────────────────────────

  test("Shape field order matches case-class declaration order") {
    val shape = Shape.derived[P2TwoOutputs]
    assertEquals(shape.fieldSpecs.map(_.name), Vector("answer", "score"))
  }

  test("Shape field names match source members") {
    val shape = Shape.derived[P2SentenceInput]
    assertEquals(shape.fieldSpecs.map(_.name), Vector("sentence"))
  }

  test("Signature.derived assigns input and output roles correctly") {
    val sig = Signature.derived[P2SentenceInput, P2ScoredSentiment](name = "Emotion")
    val inputs  = sig.layout.inputFields.map(_.name)
    val outputs = sig.layout.outputFields.map(_.name)
    assertEquals(inputs, Vector("sentence"))
    assertEquals(outputs, Vector("sentiment", "confidence"))
  }

  test("Signature.layout emits the same shape as a hand-written SignatureLayout") {
    val sig = Signature.derived[P2SentenceInput, P2ScoredSentiment](
      name = "Emotion",
      instructions = "Classify emotion."
    )
    assertEquals(sig.layout.name, "Emotion")
    assertEquals(sig.layout.instructions, Some("Classify emotion."))
    assertEquals(sig.layout.signatureString, "sentence -> sentiment, confidence")
    assertEquals(
      sig.layout.fields.map(f => (f.name, f.role, f.typeRef.repr)),
      Vector(
        ("sentence",   FieldRole.Input,  "string"),
        ("sentiment",  FieldRole.Output, "string"),
        ("confidence", FieldRole.Output, "double")
      )
    )
  }

  test("Signature.withInstructions preserves typed shapes") {
    val sig = Signature.derived[P2SentenceInput, P2ScoredSentiment]("Emotion")
    val instructed = sig.withInstructions("Classify emotion.")

    assertEquals(instructed.instructions, Some("Classify emotion."))
    assertEquals(instructed.layout.instructions, Some("Classify emotion."))
    assertEquals(
      instructed.inputShape.encode(P2SentenceInput("hello")),
      Map[String, Any]("sentence" -> "hello")
    )
    assertEquals(
      instructed.outputShape.decode(Map("sentiment" -> "joy", "confidence" -> 0.9)),
      Right(P2ScoredSentiment("joy", 0.9))
    )

    assertEquals(instructed.withInstructions(None).instructions, None)
  }

  // ── Shape encode/decode round-trip ────────────────────────────────────────

  test("Shape.encode produces a Map keyed by field name") {
    val shape = Shape.derived[P2ScoredSentiment]
    val encoded = shape.encode(P2ScoredSentiment("joy", 0.92))
    assertEquals(encoded, Map[String, Any]("sentiment" -> "joy", "confidence" -> 0.92))
  }

  test("Shape.decode round-trips a typed value") {
    val shape = Shape.derived[P2ScoredSentiment]
    val decoded = shape.decode(Map("sentiment" -> "joy", "confidence" -> 0.92))
    assertEquals(decoded, Right(P2ScoredSentiment("joy", 0.92)))
  }

  test("Shape.decode tolerates primitive coercion (string -> double)") {
    val shape = Shape.derived[P2ScoredSentiment]
    val decoded = shape.decode(Map("sentiment" -> "joy", "confidence" -> "0.5"))
    assertEquals(decoded, Right(P2ScoredSentiment("joy", 0.5)))
  }

  // ── Failure modes: missing / invalid fields ──────────────────────────────

  test("missing required output field produces a NotFoundError") {
    val shape = Shape.derived[P2ScoredSentiment]
    val decoded = shape.decode(Map("sentiment" -> "joy"))
    decoded match
      case Left(_: NotFoundError) => ()
      case other => fail(s"expected NotFoundError, got: $other")
  }

  test("invalid primitive conversion produces a ValidationError") {
    val shape = Shape.derived[P2ScoredSentiment]
    val decoded = shape.decode(Map("sentiment" -> "joy", "confidence" -> "not-a-number"))
    decoded match
      case Left(_: ValidationError) => ()
      case other => fail(s"expected ValidationError, got: $other")
  }

  // ── Enum decoding ────────────────────────────────────────────────────────

  test("FieldCodec enum derivation accepts case names as strings") {
    val dec = summon[FieldCodec[P2Sentiment]]
    assertEquals(dec.decode("joy"),       Right(P2Sentiment.joy))
    assertEquals(dec.decode("sadness"),   Right(P2Sentiment.sadness))
  }

  test("FieldCodec enum derivation accepts already-typed enum values") {
    val dec = summon[FieldCodec[P2Sentiment]]
    assertEquals(dec.decode(P2Sentiment.fear), Right(P2Sentiment.fear))
  }

  test("enum-like outputs reject values outside the declared set") {
    val shape = Shape.derived[P2EnumOutput]
    val decoded = shape.decode(Map("sentiment" -> "confused"))
    decoded match
      case Left(_: ValidationError) => () // zio-blocks Schema error format is opaque; just verify it's a Left
      case other => fail(s"expected ValidationError, got: $other")
  }

  test("enum field uses TypeRef.string with allowed-cases metadata for adapters") {
    val shape = Shape.derived[P2EnumOutput]
    val fs = shape.fieldSpecs.head
    assertEquals(fs.name, "sentiment")
    assertEquals(fs.typeRef, dspy4s.core.contracts.TypeRef.string)
    assertEquals(
      fs.metadata.get(dspy4s.core.contracts.FieldMetadata.EnumCases),
      Some("sadness,joy,love,anger,fear,surprise")
    )
    assertEquals(
      fs.metadata.get(dspy4s.core.contracts.FieldMetadata.EnumName),
      Some("P2Sentiment")
    )
  }

  test("enum encoder uses case name (not toString) so overrides can't drift") {
    val dec = summon[FieldCodec[P2Sentiment]]
    assertEquals(dec.encode(P2Sentiment.joy), "joy")
    assertEquals(dec.encode(P2Sentiment.sadness), "sadness")
    // Encoded value must round-trip through decode.
    assertEquals(dec.decode(dec.encode(P2Sentiment.love)), Right(P2Sentiment.love))
  }

  test("primitive fields carry empty metadata (no enum constraints)") {
    val shape = Shape.derived[P2ScoredSentiment]
    shape.fieldSpecs.foreach { fs =>
      assertEquals(fs.metadata, Map.empty[String, String], s"field '${fs.name}'")
    }
  }

  // ── Prediction: never constructed after a decode failure ───────────

  test("Prediction is never constructed when decode fails") {
    val shape = Shape.derived[P2ScoredSentiment]
    val raw   = DynamicPrediction(values = Map("sentiment" -> "joy"))  // missing 'confidence'
    val result = Prediction.from(raw, shape)
    assert(result.isLeft, s"expected failure but got: $result")
  }

  test("Prediction.from succeeds when all required outputs decode") {
    val shape = Shape.derived[P2ScoredSentiment]
    val raw   = DynamicPrediction(values = Map("sentiment" -> "joy", "confidence" -> 0.92))
    val result = Prediction.from(raw, shape)
    result match
      case Right(tp) =>
        assertEquals(tp.output, P2ScoredSentiment("joy", 0.92))
        assert(tp.raw eq raw, "Prediction must preserve the original raw DynamicPrediction")
      case Left(err) => fail(s"expected success but got: $err")
  }

  // ── End-to-end: Signature round-trip ────────────────────────────────

  test("Signature encodes inputs and decodes outputs end-to-end") {
    val sig = Signature.derived[P2SentenceInput, P2ScoredSentiment]("Emotion")
    val input = P2SentenceInput("i started feeling vulnerable")

    // Encode input → Map (what Predict will hand to ProgramCall)
    val inputMap = sig.inputShape.encode(input)
    assertEquals(inputMap, Map[String, Any]("sentence" -> "i started feeling vulnerable"))

    // Decode output ← Map (what Predict will receive from DynamicPrediction)
    val outputMap = Map[String, Any]("sentiment" -> "joy", "confidence" -> 0.85)
    val output    = sig.outputShape.decode(outputMap)
    assertEquals(output, Right(P2ScoredSentiment("joy", 0.85)))
  }
