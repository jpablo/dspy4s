package dspy4s.typed

import dspy4s.core.contracts.{FieldMetadata, FieldRole, TypeRef}
import munit.FunSuite

// Top-level fixtures: spec traits + supporting enum.
enum P5Tone derives ValueDecoder:
  case calm, urgent, frustrated

trait P5SentimentSpec extends Spec:
  def sentence:  InputField[String]
  def sentiment: OutputField[String]

trait P5ToneSpec extends Spec:
  def text: InputField[String]
  def tone: OutputField[P5Tone]

trait P5ToneInputSpec extends Spec:
  def tone: InputField[P5Tone]
  def label: OutputField[String]

trait P5MultiSpec extends Spec:
  def question: InputField[String]
  def context:  InputField[String]
  def answer:   OutputField[String]
  def score:    OutputField[Double]

/** Phase 5 trait-as-spec macro per docs/TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md. */
class Phase5SpecMacroSuite extends FunSuite:

  // ── Spec → Signature derivation ─────────────────────────────────────────

  test("spec trait derives a Signature with correct field names + roles") {
    val sig = TypedSignature.of[P5SentimentSpec]
    assertEquals(sig.untyped.name, "P5SentimentSpec")
    assertEquals(sig.untyped.inputFields.map(_.name),  Vector("sentence"))
    assertEquals(sig.untyped.outputFields.map(_.name), Vector("sentiment"))
  }

  test("spec trait preserves declaration order for multiple inputs and outputs") {
    val sig = TypedSignature.of[P5MultiSpec]
    assertEquals(sig.untyped.inputFields.map(_.name),  Vector("question", "context"))
    assertEquals(sig.untyped.outputFields.map(_.name), Vector("answer", "score"))
    assertEquals(sig.untyped.signatureString, "question, context -> answer, score")
  }

  test("spec trait field TypeRefs come from the ValueDecoder typeclass") {
    val sig = TypedSignature.of[P5MultiSpec]
    val byName = sig.untyped.fields.map(f => f.name -> f.typeRef.repr).toMap
    assertEquals(byName("question"), "string")
    assertEquals(byName("context"),  "string")
    assertEquals(byName("answer"),   "string")
    assertEquals(byName("score"),    "double")
  }

  test("spec trait propagates enum metadata to FieldSpec.metadata") {
    val sig = TypedSignature.of[P5ToneSpec]
    val toneField = sig.untyped.outputFields.find(_.name == "tone").get
    assertEquals(toneField.typeRef, TypeRef.string)
    assertEquals(
      toneField.metadata.get(FieldMetadata.EnumCases),
      Some("calm,urgent,frustrated")
    )
    assertEquals(toneField.metadata.get(FieldMetadata.EnumName), Some("P5Tone"))
  }

  test("spec trait fields are normalized (inferred prefix + description)") {
    val sig = TypedSignature.of[P5MultiSpec]
    val byName = sig.untyped.fields.map(f => f.name -> f).toMap
    assertEquals(byName("question").prefix, Some("Question:"))
    assertEquals(byName("answer").prefix,   Some("Answer:"))
    assertEquals(byName("score").prefix,    Some("Score:"))
  }

  // ── Named-tuple I/O / TypedSignature.of returns a usable TypedSignature ─

  test("of[T] returns a TypedSignature with named-tuple input and output types") {
    val sig = TypedSignature.of[P5SentimentSpec]
    val input = (sentence = "hello there")
    val encoded = sig.inputShape.encode(input)
    val decoded = sig.outputShape.decode(Map("sentiment" -> "positive")).toOption.get

    assertEquals(encoded, Map[String, Any]("sentence" -> "hello there"))
    val sentiment: String = decoded.sentiment
    assertEquals(sentiment, "positive")
  }

  test("of[T] outputShape rejects raw maps missing required fields") {
    val sig = TypedSignature.of[P5MultiSpec]
    val incomplete = Map[String, Any]("answer" -> "Paris")  // missing 'score'
    val result = sig.outputShape.decode(incomplete)
    assert(result.isLeft, s"expected decode failure for missing field, got: $result")
  }

  // ── Decoder-aware MapShape: spec output types are honored at decode ─────

  test("spec outputShape decodes enum case names through the field's ValueDecoder") {
    val sig = TypedSignature.of[P5ToneSpec]
    val raw = Map[String, Any]("tone" -> "calm")
    val decoded = sig.outputShape.decode(raw).toOption.get
    val tone: P5Tone = decoded.tone
    assertEquals(tone, P5Tone.calm)  // typed enum value, not the raw string
  }

  test("spec outputShape coerces numeric strings to the declared primitive type") {
    val sig = TypedSignature.of[P5MultiSpec]
    val raw = Map[String, Any]("answer" -> "Paris", "score" -> "0.5")
    val decoded = sig.outputShape.decode(raw).toOption.get
    val answer: String = decoded.answer
    val score:  Double = decoded.score
    assertEquals(answer, "Paris")
    assertEquals(score,  0.5)        // coerced from "0.5" to Double
  }

  test("spec outputShape surfaces decoder failures as Left(DspyError)") {
    val sig = TypedSignature.of[P5ToneSpec]
    val raw = Map[String, Any]("tone" -> "confused")  // not a valid P5Tone case
    val result = sig.outputShape.decode(raw)
    assert(result.isLeft, s"expected decode failure for invalid enum value, got: $result")
  }

  test("spec inputShape encodes typed enum values to their case-name strings") {
    val sig = TypedSignature.of[P5ToneInputSpec]
    val input = (tone = P5Tone.urgent)
    val encoded = sig.inputShape.encode(input)
    assertEquals(encoded("tone"), "urgent")
  }

  // ── Cross-surface parity ────────────────────────────────────────────────

  test("spec-derived signature matches builder-built signature for the same shape") {
    val fromSpec = TypedSignature.of[P5MultiSpec].untyped
    val fromBuilder = TypedSignature
      .builder("P5MultiSpec")
      .input[String]("question")
      .input[String]("context")
      .output[String]("answer")
      .output[Double]("score")
      .build

    assertEquals(fromSpec.name, fromBuilder.name)
    assertEquals(fromSpec.signatureString, fromBuilder.signatureString)
    assertEquals(
      fromSpec.fields.map(f => (f.name, f.role, f.typeRef.repr)),
      fromBuilder.fields.map(f => (f.name, f.role, f.typeRef.repr))
    )
  }

  // ── Compile-time validation ─────────────────────────────────────────────

  test("compile error: method not wrapped in InputField/OutputField") {
    val errors = compileErrors("""
      trait BadSpec extends dspy4s.typed.Spec:
        def sentence: String   // wrong: not wrapped in InputField/OutputField
      val sig = dspy4s.typed.TypedSignature.of[BadSpec]
    """)
    assert(errors.contains("must return InputField"),
      s"expected helpful error about marker types, got:\n$errors")
  }

  test("compile error: method with parameters") {
    val errors = compileErrors("""
      trait BadSpec extends dspy4s.typed.Spec:
        def f(x: Int): dspy4s.typed.InputField[String]
      val sig = dspy4s.typed.TypedSignature.of[BadSpec]
    """)
    assert(errors.contains("must be parameterless"),
      s"expected helpful error about parameters, got:\n$errors")
  }

  test("compile error: missing ValueDecoder for inner type") {
    val errors = compileErrors("""
      class NotDecodable
      trait BadSpec extends dspy4s.typed.Spec:
        def field: dspy4s.typed.OutputField[NotDecodable]
      val sig = dspy4s.typed.TypedSignature.of[BadSpec]
    """)
    assert(errors.contains("No ValueDecoder"),
      s"expected helpful error about missing ValueDecoder, got:\n$errors")
  }

  test("compile error: empty spec trait") {
    val errors = compileErrors("""
      trait EmptySpec extends dspy4s.typed.Spec
      val sig = dspy4s.typed.TypedSignature.of[EmptySpec]
    """)
    assert(errors.contains("at least one"),
      s"expected helpful error about empty spec, got:\n$errors")
  }

  test("compile error: concrete (non-abstract) methods on a spec trait") {
    val errors = compileErrors("""
      trait BadSpec extends dspy4s.typed.Spec:
        def question: dspy4s.typed.InputField[String]
        def helper(): String = "oops"   // concrete -- not allowed
      val sig = dspy4s.typed.TypedSignature.of[BadSpec]
    """)
    assert(errors.contains("concrete method"),
      s"expected helpful error about concrete methods, got:\n$errors")
  }
