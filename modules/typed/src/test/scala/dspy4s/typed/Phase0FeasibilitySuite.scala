package dspy4s.typed

import dspy4s.core.contracts.{DspyError, ParseError}
import kyo.{Record, Json, Schema, Result, DecodeException, ~}
import munit.FunSuite

// Top-level so Schema derivation works (path-dependent enums inside classes
// produce ClassCastExceptions; see EncodingProbe.scala for the discovery).
case class SentimentOutput(sentiment: String) derives Schema

enum Emotion derives Schema:
  case sadness, joy, love, anger, fear, surprise

case class EmotionOutput(sentiment: Emotion) derives Schema

/**
 * Phase 0 feasibility spike per docs/TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md.
 *
 * Verifies that `kyo-data` Record + `kyo-schema` Json at version 1.0.0-RC2
 * can cover the typed-signatures runtime contract. Each test maps to an
 * acceptance criterion in the plan.
 */
class Phase0FeasibilitySuite extends FunSuite:

  // ── 1. Typed-value construction ────────────────────────────────────────────

  test("construct a typed value with sentence: String and sentiment: String") {
    val r: Record["sentence" ~ String & "sentiment" ~ String] =
      ("sentence" ~ "i started feeling vulnerable") & ("sentiment" ~ "joy")

    assertEquals(r.sentence, "i started feeling vulnerable")
    assertEquals(r.sentiment, "joy")
  }

  // ── 2. Dot-syntax field access ────────────────────────────────────────────

  test("dot syntax accesses existing fields with correct types") {
    val r: Record["name" ~ String & "age" ~ Int] =
      ("name" ~ "Alice") & ("age" ~ 30)

    val name: String = r.name
    val age: Int     = r.age
    assertEquals(name, "Alice")
    assertEquals(age, 30)
  }

  // ── 3. Unknown-field rejection at compile time ────────────────────────────

  test("unknown field access fails to compile") {
    val errors = compileErrors("""
      val r: kyo.Record["name" ~ String] = "name" ~ "Alice"
      r.email
    """)
    assert(errors.nonEmpty, s"expected compile error for unknown field, got: $errors")
  }

  // ── 4. JSON decode into a typed output shape ──────────────────────────────

  test("decode {\"sentiment\":\"joy\"} into a typed output shape") {
    val result: Result[DecodeException, SentimentOutput] =
      Json.decode[SentimentOutput]("""{"sentiment":"joy"}""")

    result.fold(
      onSuccess = out => assertEquals(out.sentiment, "joy"),
      onFailure = e => fail(s"decode failed: ${e.getMessage}"),
      onPanic   = t => fail(s"panic: $t")
    )
  }

  // ── 5. Enum-like rejection ────────────────────────────────────────────────
  //
  // Finding: kyo-schema 1.0.0-RC2 encodes Scala enums as a discriminated
  // wrapper object — `Emotion.joy` becomes `{"joy":{}}`, not `"joy"`. Probe
  // in `EncodingProbe.scala` confirms this. This affects how LLM outputs
  // must be shaped (or transformed) before reaching the typed decoder.
  // Decision deferred to Phase 2 (Output Parsing And Coercion Contract).

  test("decode discriminated-enum form into typed output") {
    val result = Json.decode[EmotionOutput]("""{"sentiment":{"joy":{}}}""")
    result.fold(
      onSuccess = out => assertEquals(out.sentiment, Emotion.joy),
      onFailure = e => fail(s"decode failed: ${e.getMessage}"),
      onPanic   = t => fail(s"panic: $t")
    )
  }

  test("reject {\"sentiment\":{\"confused\":{}}} when sentiment is an enum") {
    val result = Json.decode[EmotionOutput]("""{"sentiment":{"confused":{}}}""")
    assert(result.isFailure, s"expected failure for invalid enum value, got: $result")
  }

  test("reject flat-string \"sentiment\":\"joy\" when sentiment is an enum") {
    // Confirms the format mismatch: an LLM that returns flat strings for
    // enum fields will fail to decode without intervening transformation.
    val result = Json.decode[EmotionOutput]("""{"sentiment":"joy"}""")
    assert(result.isFailure, s"expected failure for flat-string enum, got: $result")
  }

  // ── 6. Map kyo-schema decode failures into DspyError ─────────────────────

  /** Adapter boundary: lift a `Result[DecodeException, A]` into the dspy4s
    * `Either[DspyError, A]` style so the typed layer doesn't expose kyo's
    * error type across module boundaries. */
  private def toDspy[A](r: Result[DecodeException, A]): Either[DspyError, A] =
    r.fold(
      onSuccess = a => Right(a),
      onFailure = e => Left(ParseError(
        component = "kyo-schema",
        message   = Option(e.getMessage).getOrElse(e.toString),
        raw       = None
      )),
      onPanic   = t => Left(ParseError(
        component = "kyo-schema",
        message   = s"panic during decode: ${t.getClass.getSimpleName}: ${t.getMessage}",
        raw       = None
      ))
    )

  test("decode failures map cleanly into DspyError") {
    val bad: Result[DecodeException, EmotionOutput] =
      Json.decode[EmotionOutput]("""{"sentiment":{"confused":{}}}""")

    toDspy(bad) match
      case Left(err: ParseError) =>
        assertEquals(err.component, "kyo-schema")
        assert(err.message.nonEmpty, "expected non-empty error message")
      case Left(other) => fail(s"expected ParseError, got: $other")
      case Right(out)  => fail(s"expected failure, got success: $out")
  }

  test("decode successes map cleanly into Right") {
    val good: Result[DecodeException, EmotionOutput] =
      Json.decode[EmotionOutput]("""{"sentiment":{"joy":{}}}""")

    assertEquals(toDspy(good), Right(EmotionOutput(Emotion.joy)))
  }
