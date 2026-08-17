package dspy4s.gepa

import dspy4s.programs.{ParameterId, Program}
import dspy4s.signatures.Signature
import munit.FunSuite

private final case class CandidateQuestion(question: String)
private final case class CandidateAnswer(answer: String)

final class CandidateSuite extends FunSuite:

  private val signature = Signature.derived[CandidateQuestion, CandidateAnswer]("Candidate", "Original")
  private val program   = Program.predictStable(ParameterId("answer"), signature)

  test("seed reads instructions from stable parameter IDs") {
    assertEquals(Candidate.seed(program), Map(ParameterId("answer") -> Some("Original")))
  }

  test("applyTo changes parameters without changing program structure") {
    val updated = Candidate.applyTo(program, Map(ParameterId("answer") -> Some("Improved"))).toOption.get

    assertEquals(updated.parameters.get(ParameterId("answer")).flatMap(_.instructions), Some("Improved"))
    assertEquals(program.parameters.get(ParameterId("answer")).flatMap(_.instructions), Some("Original"))
  }

  test("applyTo preserves unspecified slots") {
    val composed = program &&& Program.predictStable(ParameterId("second"), signature)
    val updated  = Candidate.applyTo(composed, Map(ParameterId("answer") -> None)).toOption.get

    assertEquals(updated.parameters.get(ParameterId("answer")).flatMap(_.instructions), None)
    assertEquals(updated.parameters.get(ParameterId("second")).flatMap(_.instructions), Some("Original"))
  }
