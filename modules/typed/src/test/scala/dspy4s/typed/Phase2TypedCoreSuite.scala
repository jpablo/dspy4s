package dspy4s.typed

import dspy4s.core.contracts.{
  DspyError, FieldRole, NotFoundError, PredictionData, ValidationError
}
import munit.FunSuite

// Top-level: Schema/Mirror derivation does not work for path-dependent types
// declared inside test classes (Phase 0 finding).
case class P2SentenceInput(sentence: String)
case class P2ScoredSentiment(sentiment: String, confidence: Double)

enum P2Sentiment derives ValueDecoder:
  case sadness, joy, love, anger, fear, surprise

case class P2EnumOutput(sentiment: P2Sentiment) derives Shape

case class P2TwoInputs(question: String, context: String) derives Shape
case class P2TwoOutputs(answer: String, score: Double) derives Shape

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

  test("TypedSignature.derived assigns input and output roles correctly") {
    val sig = TypedSignature.derived[P2SentenceInput, P2ScoredSentiment](name = "Emotion")
    val inputs  = sig.untyped.inputFields.map(_.name)
    val outputs = sig.untyped.outputFields.map(_.name)
    assertEquals(inputs, Vector("sentence"))
    assertEquals(outputs, Vector("sentiment", "confidence"))
  }

  test("TypedSignature.untyped emits the same shape as a hand-written SignatureSpec") {
    val sig = TypedSignature.derived[P2SentenceInput, P2ScoredSentiment](
      name = "Emotion",
      instructions = "Classify emotion."
    )
    assertEquals(sig.untyped.name, "Emotion")
    assertEquals(sig.untyped.instructions, Some("Classify emotion."))
    assertEquals(sig.untyped.signatureString, "sentence -> sentiment, confidence")
    assertEquals(
      sig.untyped.fields.map(f => (f.name, f.role, f.typeRef.repr)),
      Vector(
        ("sentence",   FieldRole.Input,  "string"),
        ("sentiment",  FieldRole.Output, "string"),
        ("confidence", FieldRole.Output, "double")
      )
    )
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

  test("ValueDecoder enum derivation accepts case names as strings") {
    val dec = summon[ValueDecoder[P2Sentiment]]
    assertEquals(dec.decode("joy"),       Right(P2Sentiment.joy))
    assertEquals(dec.decode("sadness"),   Right(P2Sentiment.sadness))
  }

  test("ValueDecoder enum derivation accepts already-typed enum values") {
    val dec = summon[ValueDecoder[P2Sentiment]]
    assertEquals(dec.decode(P2Sentiment.fear), Right(P2Sentiment.fear))
  }

  test("enum-like outputs reject values outside the declared set") {
    val shape = Shape.derived[P2EnumOutput]
    val decoded = shape.decode(Map("sentiment" -> "confused"))
    decoded match
      case Left(err: ValidationError) =>
        assert(err.message.contains("confused"))
        assert(err.message.contains("joy"))  // mentions one of the valid options
      case other => fail(s"expected ValidationError, got: $other")
  }

  test("enum field uses TypeRef.string with allowed-cases metadata for adapters") {
    val shape = Shape.derived[P2EnumOutput]
    val fs = shape.fieldSpecs.head
    assertEquals(fs.name, "sentiment")
    assertEquals(fs.typeRef, dspy4s.core.contracts.TypeRef.string)
    assertEquals(
      fs.metadata.get(ValueDecoder.Meta.EnumCases),
      Some("sadness,joy,love,anger,fear,surprise")
    )
    assertEquals(fs.metadata.get(ValueDecoder.Meta.EnumName), Some("P2Sentiment"))
  }

  test("primitive fields carry empty metadata (no enum constraints)") {
    val shape = Shape.derived[P2ScoredSentiment]
    shape.fieldSpecs.foreach { fs =>
      assertEquals(fs.metadata, Map.empty[String, String], s"field '${fs.name}'")
    }
  }

  // ── TypedPrediction: never constructed after a decode failure ───────────

  test("TypedPrediction is never constructed when decode fails") {
    val shape = Shape.derived[P2ScoredSentiment]
    val raw   = PredictionData(values = Map("sentiment" -> "joy"))  // missing 'confidence'
    val result = TypedPrediction.from(raw, shape)
    assert(result.isLeft, s"expected failure but got: $result")
  }

  test("TypedPrediction.from succeeds when all required outputs decode") {
    val shape = Shape.derived[P2ScoredSentiment]
    val raw   = PredictionData(values = Map("sentiment" -> "joy", "confidence" -> 0.92))
    val result = TypedPrediction.from(raw, shape)
    result match
      case Right(tp) =>
        assertEquals(tp.output, P2ScoredSentiment("joy", 0.92))
        assert(tp.raw eq raw, "TypedPrediction must preserve the original raw Prediction")
      case Left(err) => fail(s"expected success but got: $err")
  }

  // ── End-to-end: TypedSignature round-trip ────────────────────────────────

  test("TypedSignature encodes inputs and decodes outputs end-to-end") {
    val sig = TypedSignature.derived[P2SentenceInput, P2ScoredSentiment]("Emotion")
    val input = P2SentenceInput("i started feeling vulnerable")

    // Encode input → Map (what TypedPredict will hand to ProgramCall)
    val inputMap = sig.inputShape.encode(input)
    assertEquals(inputMap, Map[String, Any]("sentence" -> "i started feeling vulnerable"))

    // Decode output ← Map (what TypedPredict will receive from Prediction)
    val outputMap = Map[String, Any]("sentiment" -> "joy", "confidence" -> 0.85)
    val output    = sig.outputShape.decode(outputMap)
    assertEquals(output, Right(P2ScoredSentiment("joy", 0.85)))
  }
