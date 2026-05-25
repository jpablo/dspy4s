package dspy4s.typed

import dspy4s.core.contracts.{
  FieldMetadata, FieldRole, NotFoundError, PredictionData, Signature,
  SignatureSpec, TypeRef
}
import munit.FunSuite

// Top-level fixtures (Phase 0 finding: Mirror derivation needs top-level types,
// and per-suite-prefixed names avoid collisions across test files).
case class P3CommentInput(comment: String, lang: String)
case class P3ClassifyOutput(toxic: Boolean, confidence: Double)

enum P3Tone derives ValueDecoder:
  case neutral, positive, negative

case class P3ToneOutput(tone: P3Tone) derives Shape

/** Phase 3 surfaces per docs/TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md.
  *
  * The case-class API is already in Phase 2 (`TypedSignature.derived`); these
  * tests round out coverage and add the new SignatureBuilder surface. */
class Phase3SurfacesSuite extends FunSuite:

  // ── Builder API ─────────────────────────────────────────────────────────

  test("builder emits a Signature with input then output fields in declaration order") {
    val sig = TypedSignature
      .builder("Classify")
      .input[String]("comment")
      .input[String]("lang")
      .output[Boolean]("toxic")
      .output[Double]("confidence")
      .instructions("Classify the comment for toxicity.")
      .build

    assertEquals(sig.name, "Classify")
    assertEquals(sig.instructions, Some("Classify the comment for toxicity."))
    assertEquals(sig.inputFields.map(_.name), Vector("comment", "lang"))
    assertEquals(sig.outputFields.map(_.name), Vector("toxic", "confidence"))
    assertEquals(sig.signatureString, "comment, lang -> toxic, confidence")
  }

  test("builder field TypeRefs come from the ValueDecoder typeclass") {
    val sig = TypedSignature
      .builder("Bag")
      .input[String]("a")
      .input[Int]("b")
      .output[Double]("c")
      .output[Boolean]("d")
      .build

    val tref = sig.fields.map(f => f.name -> f.typeRef.repr).toMap
    assertEquals(tref("a"), "string")
    assertEquals(tref("b"), "int")
    assertEquals(tref("c"), "double")
    assertEquals(tref("d"), "bool")
  }

  test("builder enum field carries TypeRef.string + EnumCases / EnumName metadata") {
    val sig = TypedSignature
      .builder("ToneCheck")
      .input[String]("text")
      .output[P3Tone]("tone")
      .build

    val toneField = sig.outputFields.find(_.name == "tone").get
    assertEquals(toneField.typeRef, TypeRef.string)
    assertEquals(
      toneField.metadata.get(FieldMetadata.EnumCases),
      Some("neutral,positive,negative")
    )
    assertEquals(toneField.metadata.get(FieldMetadata.EnumName), Some("P3Tone"))
  }

  test("builder is immutable — chained calls don't mutate earlier references") {
    val base = TypedSignature.builder("Base").input[String]("a")
    val withB = base.output[Int]("b")
    val withC = base.output[String]("c")  // forked from `base`, not `withB`

    assertEquals(withB.build.outputFields.map(_.name), Vector("b"))
    assertEquals(withC.build.outputFields.map(_.name), Vector("c"))
  }

  test("builder empty instructions become None") {
    val sig = TypedSignature
      .builder("Empty")
      .input[String]("x")
      .output[String]("y")
      .instructions("")
      .build
    assertEquals(sig.instructions, None)
  }

  // ── Case-class API parity (Phase 2 surface, exercised end-to-end) ────────

  test("case-class derived signature has expected field names and roles") {
    val sig = TypedSignature.derived[P3CommentInput, P3ClassifyOutput]("Classify")
    assertEquals(sig.untyped.inputFields.map(_.name), Vector("comment", "lang"))
    assertEquals(sig.untyped.outputFields.map(_.name), Vector("toxic", "confidence"))
  }

  test("case-class encode produces a Map keyed by case-class field names") {
    val sig = TypedSignature.derived[P3CommentInput, P3ClassifyOutput]("Classify")
    val input = P3CommentInput("hello there", "en")
    val encoded = sig.inputShape.encode(input)
    assertEquals(encoded, Map[String, Any]("comment" -> "hello there", "lang" -> "en"))
  }

  test("decoded TypedPrediction exposes case-class fields directly") {
    val sig = TypedSignature.derived[P3CommentInput, P3ClassifyOutput]("Classify")
    val raw = PredictionData(values = Map(
      "toxic"      -> false,
      "confidence" -> 0.91
    ))
    val result = TypedPrediction.from(raw, sig.outputShape)
    result match
      case Right(tp) =>
        // Direct case-class field access — typed, no Either at the field level.
        val toxic: Boolean = tp.output.toxic
        val conf:  Double  = tp.output.confidence
        assertEquals(toxic, false)
        assertEquals(conf,  0.91)
      case Left(err) => fail(s"expected success but got: $err")
  }

  // ── Cross-surface parity ────────────────────────────────────────────────

  test("builder fields are normalized (inferred prefix + description)") {
    // SignatureSpec.create routes through FieldSpec.normalize, so each
    // FieldSpec gets a prefix derived from its name and a description
    // template. Adapters depend on these for prompt rendering.
    val sig = TypedSignature
      .builder("Bag")
      .input[String]("userName")  // camelCase exercises prefix inference
      .output[Int]("scoreValue")
      .build

    val byName = sig.fields.map(f => f.name -> f).toMap
    assertEquals(byName("userName").prefix,      Some("User Name:"))
    assertEquals(byName("userName").description, Some("${userName}"))
    assertEquals(byName("scoreValue").prefix,    Some("Score Value:"))
    assertEquals(byName("scoreValue").description, Some("${scoreValue}"))
  }

  test("case-class derived fields are normalized identically to the builder path") {
    val sig = TypedSignature.derived[P3CommentInput, P3ClassifyOutput]("Classify")
    val byName = sig.untyped.fields.map(f => f.name -> f).toMap
    // P3CommentInput.comment / lang and P3ClassifyOutput.toxic / confidence
    // are all lowercase, so the inferred prefix is just the capitalized form.
    assertEquals(byName("comment").prefix,    Some("Comment:"))
    assertEquals(byName("toxic").prefix,      Some("Toxic:"))
    assertEquals(byName("confidence").prefix, Some("Confidence:"))
  }

  test("builder rejects invalid field names by throwing IllegalArgumentException") {
    // SignatureSpec.create validates identifiers. The builder routes the
    // failure into a thrown exception (programmer error at construction).
    intercept[IllegalArgumentException] {
      TypedSignature
        .builder("Bad")
        .input[String]("field-with-dash")
        .output[String]("ok")
        .build
    }
  }

  test("builder and case-class derivation produce equivalent Signatures for the same shape") {
    val fromBuilder = TypedSignature
      .builder("Classify")
      .input[String]("comment")
      .input[String]("lang")
      .output[Boolean]("toxic")
      .output[Double]("confidence")
      .build

    val fromCases = TypedSignature
      .derived[P3CommentInput, P3ClassifyOutput]("Classify")
      .untyped

    assertEquals(fromBuilder.name, fromCases.name)
    assertEquals(fromBuilder.signatureString, fromCases.signatureString)
    assertEquals(
      fromBuilder.fields.map(f => (f.name, f.role, f.typeRef.repr)),
      fromCases.fields.map(f => (f.name, f.role, f.typeRef.repr))
    )
  }
