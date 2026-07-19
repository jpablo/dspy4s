package dspy4s.gepa

import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.Predictors
import dspy4s.programs.DynamicPredict
import dspy4s.programs.PredictorId
import munit.FunSuite

private final case class CandidatePair(first: DynamicPredict, second: DynamicPredict)
private object CandidatePair:
  given Predictors[CandidatePair] = Predictors.derived

private final case class LeftNested(pair: CandidatePair, third: DynamicPredict)
private object LeftNested:
  given Predictors[LeftNested] = Predictors.derived

private final case class RightNested(first: DynamicPredict, pair: CandidatePair)
private object RightNested:
  given Predictors[RightNested] = Predictors.derived

class CandidateSuite extends FunSuite:

  private given Predictors[DynamicPredict] = summon[Predictors[DynamicPredict]]

  private def predict(instruction: String): DynamicPredict =
    DynamicPredict(layout =
      SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some(instruction))
    )

  test("seed reads each predictor's current instruction keyed by stable predictor ID") {
    assertEquals(Candidate.seed(predict("Answer the question.")), Map(PredictorId(0) -> "Answer the question."))
  }

  test("applyTo writes a candidate's instruction back onto the predictor") {
    val applied = Candidate.applyTo(
      predict("Answer the question."),
      Map(PredictorId(0) -> "Be concise and precise.")
    )
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

  test("candidate identity survives structural reassociation even when display paths change") {
    val a     = predict("A")
    val b     = predict("B")
    val c     = predict("C")
    val left  = LeftNested(CandidatePair(a, b), c)
    val right = RightNested(a, CandidatePair(b, c))

    val leftEntries  = summon[Predictors[LeftNested]].readIdentified(left)
    val rightEntries = summon[Predictors[RightNested]].readIdentified(right)
    assertEquals(leftEntries.map(_.id), rightEntries.map(_.id))
    assertNotEquals(leftEntries.map(_.displayName), rightEntries.map(_.displayName))

    val edited  = Candidate.seed(left).map { case (id, instruction) => id -> s"$instruction!" }
    val applied = Candidate.applyTo(right, edited)
    assertEquals(
      summon[Predictors[RightNested]].read(applied).map(_.layout.instructions),
      Vector(Some("A!"), Some("B!"), Some("C!"))
    )
  }
