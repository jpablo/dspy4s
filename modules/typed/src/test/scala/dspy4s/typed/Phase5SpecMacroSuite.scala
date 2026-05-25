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

  // ── MapShape / TypedSignature.of returns a usable TypedSignature ────────

  test("of[T] returns a TypedSignature[Map, Map] whose shapes encode/decode identity") {
    val sig = TypedSignature.of[P5SentimentSpec]
    val input  = Map[String, Any]("sentence" -> "hello there")
    val output = Map[String, Any]("sentiment" -> "positive")

    assertEquals(sig.inputShape.encode(input), input)
    assertEquals(sig.outputShape.decode(output), Right(output))
  }

  test("of[T] outputShape rejects raw maps missing required fields") {
    val sig = TypedSignature.of[P5MultiSpec]
    val incomplete = Map[String, Any]("answer" -> "Paris")  // missing 'score'
    val result = sig.outputShape.decode(incomplete)
    assert(result.isLeft, s"expected decode failure for missing field, got: $result")
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
