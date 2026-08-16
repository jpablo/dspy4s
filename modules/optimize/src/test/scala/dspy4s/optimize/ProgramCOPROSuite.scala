package dspy4s.optimize

import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeError, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.evaluate.ProgramMetric
import dspy4s.programs.plan.*
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

import scala.collection.mutable.ArrayBuffer

final class ProgramCOPROSuite extends FunSuite:

  private final case class Question(question: String)
  private final case class Answer(answer: String)

  private val answerId  = ParameterId("answer")
  private val signature = Signature.derived[Question, Answer]("Answer", instructions = "bad instruction")
  private val student   = Program.predict(answerId, signature).fromRecords(signature.inputShape)
  private val dataset   = Vector(
    Example(DynamicValues.record("question" := "abc", "answer" := "cba"), Set("question")),
    Example(DynamicValues.record("question" := "xyz", "answer" := "zyx"), Set("question"))
  )

  private val metric = new ProgramMetric:
    val name: String = "exact"

    def score(
        example                  : Example,
        prediction               : RawPrediction,
        @annotation.unused events: Vector[ProgramEvent]
    ): ZIO[Any, DspyError, Double] =
      ZIO.fromEither(for
        expected <- DynamicValues.requireString(example.values, "answer", "expected")
        actual   <- DynamicValues.requireString(prediction.values, "answer", "actual")
      yield if actual == expected then 1.0 else 0.0)

  private val backend = new PredictionBackend:
    def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
      ZIO.fromEither(DynamicValues.requireString(request.inputs, "question", "copro test")).map { question =>
        val answer =
          if request.layout.instructions.exists(_.contains("good instruction")) then question.reverse
          else "wrong"
        RawPrediction(DynamicValues.record("answer" := answer))
      }

  private def run(
      proposer: ProgramWithEnv[ProgramCOPRO.ProposalInput, ProgramCOPRO.Proposal, Any]
  ) = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe
      .run(
        ProgramCOPRO(
          student,
          dataset,
          proposer,
          config = ProgramCOPROConfig(metric = metric, breadth = CoproBreadth(2), depth = RoundCount(1), seed = 9L)
        ).provideEnvironment(ZEnvironment(backend))
      )
      .getOrThrowFiberFailure()
  }

  test("program COPRO proposes, scores, and selects instructions by stable parameter ID") {
    val inputs   = ArrayBuffer.empty[ProgramCOPRO.ProposalInput]
    val proposer = Program.lift[ProgramCOPRO.ProposalInput, ProgramCOPRO.Proposal] { input =>
      inputs += input
      ProgramCOPRO.Proposal("good instruction")
    }
    val report = run(proposer)

    assertEquals(
      report.bestProgram.program.parameters.get(answerId).flatMap(_.instructions),
      Some("good instruction")
    )
    assertEquals(report.metadata("best_score"), 100.0)
    assertEquals(report.metadata("num_candidates"), 2)
    assertEquals(report.metadata("num_proposal_failures"), 0)
    assertEquals(report.candidates.map(_.score), Vector(100.0, 0.0))
    assert(report.candidates.forall(_.evaluation.nonEmpty))
    assertEquals(inputs.map(_.parameterId).toVector, Vector("answer"))
    assertEquals(inputs.map(_.round).toVector, Vector(0))
  }

  test("program COPRO records a proposal failure and retains the scored baseline") {
    val proposer = Program.liftEither[ProgramCOPRO.ProposalInput, ProgramCOPRO.Proposal](_ =>
      Left(RuntimeError("proposal", "unavailable"))
    )
    val report = run(proposer)

    assertEquals(
      report.bestProgram.program.parameters.get(answerId).flatMap(_.instructions),
      Some("bad instruction")
    )
    assertEquals(report.metadata("num_proposal_failures"), 1)
    assertEquals(report.metadata("num_candidates"), 1)
    assertEquals(report.candidates.head.metadata("parameter_id"), "answer")
  }

  test("program COPRO retains the current instruction when a proposal has an equal score") {
    val proposer = Program.lift[ProgramCOPRO.ProposalInput, ProgramCOPRO.Proposal](_ =>
      ProgramCOPRO.Proposal("equally bad")
    )
    val report = run(proposer)

    assertEquals(
      report.bestProgram.program.parameters.get(answerId).flatMap(_.instructions),
      Some("bad instruction")
    )
    assertEquals(report.candidates.map(_.score), Vector(0.0, 0.0))
  }
