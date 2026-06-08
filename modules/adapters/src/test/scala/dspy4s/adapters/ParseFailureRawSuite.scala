package dspy4s.adapters

import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.lm.contracts.LmOutput
import munit.FunSuite

/** G-12 P-b: a parse failure must carry the raw (unparseable) model response in `ParseError.raw`, so GEPA's
  * reflective evaluation can show the reflection LM what the model actually produced. */
class ParseFailureRawSuite extends FunSuite:

  // Two output fields, so the single-output text fallback does NOT apply and a missing field is a real parse error.
  private val layout: SignatureLayout = SignatureLayout.parse("question -> answer, confidence").toOption.get

  private def rawOf(adapter: dspy4s.adapters.contracts.Adapter, text: String): Option[String] =
    given RuntimeContext = RuntimeContext()
    adapter.parse(layout, LmOutput(text = text)) match
      case Left(ParseError(_, _, raw)) => raw
      case other                       => fail(s"expected a Left(ParseError), got: $other")

  test("ChatAdapter.parse carries the raw response on a missing-field failure") {
    assertEquals(rawOf(ChatAdapter(), "Paris is the answer"), Some("Paris is the answer"))
  }

  test("JSONAdapter.parse carries the raw response on a missing-field failure") {
    // Valid JSON object, but missing `confidence`.
    val text = """{"answer": "Paris"}"""
    assertEquals(rawOf(JSONAdapter(allowTextFallbackForSingleOutput = false), text), Some(text))
  }

  test("XMLAdapter.parse carries the raw response on a missing-field failure") {
    val text = "<outputs><answer>Paris</answer></outputs>"
    assertEquals(rawOf(XMLAdapter(allowTextFallbackForSingleOutput = false), text), Some(text))
  }
