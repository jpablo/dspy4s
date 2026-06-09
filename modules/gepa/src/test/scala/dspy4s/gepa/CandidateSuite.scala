package dspy4s.gepa

import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.Predictors
import dspy4s.programs.DynamicPredict
import munit.FunSuite

class CandidateSuite extends FunSuite:

  private given Predictors[DynamicPredict] = summon[Predictors[DynamicPredict]]

  private def predict(instruction: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some(instruction)))

  test("seed reads each predictor's current instruction keyed by component name") {
    assertEquals(Candidate.seed(predict("Answer the question.")), Map("self" -> "Answer the question."))
  }

  test("applyTo writes a candidate's instruction back onto the predictor") {
    val applied = Candidate.applyTo(predict("Answer the question."), Map("self" -> "Be concise and precise."))
    assertEquals(applied.layout.instructions, Some("Be concise and precise."))
  }

  test("applyTo leaves predictors absent from the candidate untouched") {
    val p = predict("Original.")
    assertEquals(Candidate.applyTo(p, Map.empty).layout.instructions, Some("Original."))
  }

  test("applyTo(seed) round-trips the instruction") {
    val p = predict("Round trip me.")
    assertEquals(Candidate.applyTo(p, Candidate.seed(p)).layout.instructions, p.layout.instructions)
  }
