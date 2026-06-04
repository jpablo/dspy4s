package dspy4s.typed

import zio.blocks.schema.Schema

import dspy4s.core.contracts.{
  NotFoundError, DynamicPrediction, SignatureLayout, TypeRef, :=
}
import munit.FunSuite

// Top-level fixtures (Phase 0 finding: Mirror derivation needs top-level types,
// and per-suite-prefixed names avoid collisions across test files).
case class P3CommentInput(comment: String, lang: String) derives Schema
case class P3ClassifyOutput(toxic: Boolean, confidence: Double) derives Schema

enum P3Tone derives Schema:
  case neutral, positive, negative

case class P3ToneOutput(tone: P3Tone) derives Schema

/** Phase 3 surfaces per docs/TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md.
  *
  * The case-class API is already in Phase 2 (`Signature.derived`); these
  * tests round out coverage and add the new SignatureBuilder surface. */
class Phase3SurfacesSuite extends FunSuite:

  // ── Builder API ─────────────────────────────────────────────────────────

  test("builder emits a SignatureLayout with input then output fields in declaration order") {
    val sig = Signature
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

  test("builder field TypeRefs are derived from each field's Schema") {
    val sig = Signature
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

  test("builder enum field carries TypeRef.string at the wire boundary") {
    val sig = Signature
      .builder("ToneCheck")
      .input[String]("text")
      .output[P3Tone]("tone")
      .build

    val toneField = sig.outputFields.find(_.name == "tone").get
    assertEquals(toneField.typeRef, TypeRef.string)
  }

  test("builder is immutable — chained calls don't mutate earlier references") {
    val base = Signature.builder("Base").input[String]("a")
    val withB = base.output[Int]("b")
    val withC = base.output[String]("c")  // forked from `base`, not `withB`

    assertEquals(withB.build.outputFields.map(_.name), Vector("b"))
    assertEquals(withC.build.outputFields.map(_.name), Vector("c"))
  }

  test("builder empty instructions become None") {
    val sig = Signature
      .builder("Empty")
      .input[String]("x")
      .output[String]("y")
      .instructions("")
      .build
    assertEquals(sig.instructions, None)
  }

  // ── Case-class API parity (Phase 2 surface, exercised end-to-end) ────────

  test("case-class derived signature has expected field names and roles") {
    val sig = Signature.derived[P3CommentInput, P3ClassifyOutput]("Classify")
    assertEquals(sig.layout.inputFields.map(_.name), Vector("comment", "lang"))
    assertEquals(sig.layout.outputFields.map(_.name), Vector("toxic", "confidence"))
  }

  test("case-class encode produces a Map keyed by case-class field names") {
    val sig = Signature.derived[P3CommentInput, P3ClassifyOutput]("Classify")
    val input = P3CommentInput("hello there", "en")
    val encoded = sig.inputShape.encode(input)
    assertEquals(encoded, rec("comment" := "hello there", "lang" := "en"))
  }

  test("decoded Prediction exposes case-class fields directly") {
    val sig = Signature.derived[P3CommentInput, P3ClassifyOutput]("Classify")
    val raw = DynamicPrediction(values = rec(
      "toxic"      := false,
      "confidence" := 0.91
    ))
    val result = Prediction.from(raw, sig.outputShape)
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
    // SignatureLayout.create routes through FieldSpec.normalize, so each
    // FieldSpec gets a prefix derived from its name and a description
    // template. Adapters depend on these for prompt rendering.
    val sig = Signature
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
    val sig = Signature.derived[P3CommentInput, P3ClassifyOutput]("Classify")
    val byName = sig.layout.fields.map(f => f.name -> f).toMap
    // P3CommentInput.comment / lang and P3ClassifyOutput.toxic / confidence
    // are all lowercase, so the inferred prefix is just the capitalized form.
    assertEquals(byName("comment").prefix,    Some("Comment:"))
    assertEquals(byName("toxic").prefix,      Some("Toxic:"))
    assertEquals(byName("confidence").prefix, Some("Confidence:"))
  }

  test("builder rejects invalid field names by throwing IllegalArgumentException") {
    // SignatureLayout.create validates identifiers. The builder routes the
    // failure into a thrown exception (programmer error at construction).
    intercept[IllegalArgumentException] {
      Signature
        .builder("Bad")
        .input[String]("field-with-dash")
        .output[String]("ok")
        .build
    }
  }

  test("builder and case-class derivation produce equivalent Signatures for the same shape") {
    val fromBuilder = Signature
      .builder("Classify")
      .input[String]("comment")
      .input[String]("lang")
      .output[Boolean]("toxic")
      .output[Double]("confidence")
      .build

    val fromCases = Signature
      .derived[P3CommentInput, P3ClassifyOutput]("Classify")
      .layout

    assertEquals(fromBuilder.name, fromCases.name)
    assertEquals(fromBuilder.signatureString, fromCases.signatureString)
    assertEquals(
      fromBuilder.fields.map(f => (f.name, f.role, f.typeRef.repr)),
      fromCases.fields.map(f => (f.name, f.role, f.typeRef.repr))
    )
  }

  // ── String DSL surface ──────────────────────────────────────────────────

  test("SignatureLayout.parse turns a DSPy-style string DSL into a layout") {
    val parsed = SignatureLayout.parse("question -> answer")
    parsed match
      case Right(layout) =>
        assertEquals(layout.inputFields.map(_.name),  Vector("question"))
        assertEquals(layout.outputFields.map(_.name), Vector("answer"))
        assertEquals(layout.instructions, None)
      case Left(err) => fail(s"expected parse success, got: $err")
  }

  test("SignatureLayout.parse carries non-empty instructions") {
    val parsed = SignatureLayout.parse(
      "question -> answer",
      instructions = "Answer the question concisely."
    )
    assertEquals(
      parsed.toOption.flatMap(_.instructions),
      Some("Answer the question concisely.")
    )
  }

  test("Signature.fromStringDynamic produces a Record-backed wrapper (MapShape)") {
    val sig = Signature.fromStringDynamic("question -> answer").toOption.get
    assertEquals(sig.layout.inputFields.map(_.name),  Vector("question"))
    assertEquals(sig.layout.outputFields.map(_.name), Vector("answer"))

    // Map-shape encode is identity for the input record.
    val encoded = sig.inputShape.encode(rec("question" := "what is 2+2?"))
    assertEquals(encoded, rec("question" := "what is 2+2?"))

    // Output decode succeeds for the declared field, fails when missing.
    val decoded = sig.outputShape.decode(rec("answer" := "4"))
    assertEquals(decoded, Right(rec("answer" := "4")))

    sig.outputShape.decode(zio.blocks.schema.DynamicValue.Record.empty) match
      case Left(_: NotFoundError) => () // expected
      case other                  => fail(s"expected NotFoundError, got: $other")
  }

  test("Signature.fromString (typed literal) is named-tuple-typed with scalar field types") {
    // The literal is parsed at compile time into NamedTuple I/O: (question: String) -> (answer: Boolean).
    val sig = Signature.fromString("question -> answer: bool")
    assertEquals(sig.layout.inputFields.map(_.name),  Vector("question"))
    assertEquals(sig.layout.outputFields.map(_.name), Vector("answer"))

    // Output decodes to a typed named tuple -> `.answer` is a Boolean (typed dot-access).
    val decoded = sig.outputShape.decode(rec("answer" := true))
    assertEquals(decoded.map(_.answer), Right(true))
  }
