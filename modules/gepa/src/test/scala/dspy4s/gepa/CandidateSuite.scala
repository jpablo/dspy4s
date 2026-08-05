package dspy4s.gepa

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
import dspy4s.programs.optimization.OptimizableTraversal
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.programs.optimization.OptimizableId
import munit.FunSuite

private final case class CandidatePair(first: DynamicPredict, second: DynamicPredict)
private object CandidatePair:
  given OptimizableTraversal[CandidatePair] = OptimizableTraversal.derived

private final case class LeftNested(pair: CandidatePair, third: DynamicPredict)
private object LeftNested:
  given OptimizableTraversal[LeftNested] = OptimizableTraversal.derived

private final case class RightNested(first: DynamicPredict, pair: CandidatePair)
private object RightNested:
  given OptimizableTraversal[RightNested] = OptimizableTraversal.derived

class CandidateSuite extends FunSuite:

  private given OptimizableTraversal[DynamicPredict] = summon[OptimizableTraversal[DynamicPredict]]

  private def predict(instruction: String): DynamicPredict =
    DynamicPredict(layout =
      SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some(instruction))
    )

  private def predict(instruction: Option[String]): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse("question -> answer").toOption.get.withInstructions(instruction))

  test("seed reads each leaf's current instruction keyed by stable optimizable ID") {
    assertEquals(Candidate.seed(predict("Answer the question.")), Map(OptimizableId(0) -> Some("Answer the question.")))
  }

  test("applyTo writes a candidate's instruction back onto the predictor") {
    val applied = Candidate.applyTo(
      predict("Answer the question."),
      Map(OptimizableId(0) -> Some("Be concise and precise."))
    )
    assertEquals(applied.layout.instructions, Some("Be concise and precise."))
  }

  test("applyTo leaves optimizables absent from the candidate untouched") {
    val p = predict("Original.")
    assertEquals(Candidate.applyTo(p, Map.empty).layout.instructions, Some("Original."))
  }

  test("applyTo(seed) preserves the exact optimizable state") {
    val p = predict("Round trip me.").copy(
      demos = Vector(Example.empty),
      config = DynamicValues.record("temperature" := 0.3)
    )
    val applied = Candidate.applyTo(p, Candidate.seed(p))
    assertEquals(
      summon[OptimizableTraversal[DynamicPredict]].read(applied),
      summon[OptimizableTraversal[DynamicPredict]].read(p)
    )
  }

  test("seed and applyTo distinguish absent from explicitly empty instructions") {
    val absent = predict(None)
    val empty  = predict(Some(""))

    assertEquals(Candidate.seed(absent), Map(OptimizableId(0) -> None))
    assertEquals(Candidate.seed(empty), Map(OptimizableId(0) -> Some("")))
    assertNotEquals(Candidate.seed(absent), Candidate.seed(empty))
    assertEquals(Candidate.applyTo(absent, Candidate.seed(absent)).layout.instructions, None)
    assertEquals(Candidate.applyTo(empty, Candidate.seed(empty)).layout.instructions, Some(""))
    assertEquals(Candidate.applyTo(predict("Original."), Map(OptimizableId(0) -> None)).layout.instructions, None)
    assertEquals(Candidate.applyTo(absent, Map(OptimizableId(0) -> Some(""))).layout.instructions, Some(""))
  }

  test("candidate identity survives structural reassociation even when display paths change") {
    val a     = predict("A")
    val b     = predict("B")
    val c     = predict("C")
    val left  = LeftNested(CandidatePair(a, b), c)
    val right = RightNested(a, CandidatePair(b, c))

    val leftEntries  = summon[OptimizableTraversal[LeftNested]].readIdentified(left)
    val rightEntries = summon[OptimizableTraversal[RightNested]].readIdentified(right)
    assertEquals(leftEntries.map(_.id), rightEntries.map(_.id))
    assertNotEquals(leftEntries.map(_.displayName), rightEntries.map(_.displayName))

    val edited  = Candidate.seed(left).map { case (id, instruction) => id -> instruction.map(_ + "!") }
    val applied = Candidate.applyTo(right, edited)
    assertEquals(
      summon[OptimizableTraversal[RightNested]].read(applied).map(_.instructions),
      Vector(Some("A!"), Some("B!"), Some("C!"))
    )
  }
