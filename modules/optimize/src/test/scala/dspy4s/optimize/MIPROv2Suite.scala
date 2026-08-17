package dspy4s.optimize

import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeError, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.evaluate.Metric
import dspy4s.programs.*
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

import scala.collection.mutable.ArrayBuffer

final class MIPROv2Suite extends FunSuite:

  private final case class Question(question: String)
  private final case class Answer(answer: String)

  private val answerId  = ParameterId("answer")
  private val signature = Signature.derived[Question, Answer]("Answer", instructions = "bad instruction")
  private val student   = Program.predict(answerId, signature).fromRecords(signature.inputShape)
  private val dataset   = Vector(
    Example(DynamicValues.record("question" := "abc", "answer" := "cba"), Set("question")),
    Example(DynamicValues.record("question" := "xyz", "answer" := "zyx"), Set("question"))
  )

  private val metric = new Metric:
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
      ZIO.fromEither(DynamicValues.requireString(request.inputs, "question", "mipro test")).map { question =>
        val answer =
          if request.layout.instructions.exists(_.contains("good instruction")) then question.reverse
          else "wrong"
        RawPrediction(DynamicValues.record("answer" := answer))
      }

  private val config = MIPROv2Config(
    metric = metric,
    numCandidates = CandidateCount(1),
    numTrials = TrialCount(20),
    maxBootstrappedDemos = DemoCount(0),
    maxLabeledDemos = DemoCount(0),
    seed = 7L
  )

  private def run(
      proposer: ProgramWithEnv[MIPROv2.ProposalInput, MIPROv2.Proposal, Any]
  ) =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          MIPROv2(student, dataset, proposer, valset = Some(dataset), config = config)
            .provideEnvironment(ZEnvironment(backend))
        )
        .getOrThrowFiberFailure()
    }

  test("program MIPROv2 selects a proposed instruction by stable parameter ID") {
    val inputs   = ArrayBuffer.empty[MIPROv2.ProposalInput]
    val proposer = Program.lift[MIPROv2.ProposalInput, MIPROv2.Proposal] { input =>
      inputs += input
      MIPROv2.Proposal("good instruction")
    }
    val report = run(proposer)

    assertEquals(
      report.bestProgram.program.parameters.get(answerId).flatMap(_.instructions),
      Some("good instruction")
    )
    assertEquals(report.metadata("best_score"), 100.0)
    assertEquals(report.metadata("num_candidates"), 21)
    assertEquals(report.metadata("num_demo_candidates"), 1)
    assertEquals(report.metadata("num_bootstrap_failures"), 0)
    assertEquals(report.metadata("num_proposal_failures"), 0)
    assertEquals(inputs.map(_.parameterId).toVector, Vector("answer"))
    assert(report.candidates.forall(_.evaluation.nonEmpty))
  }

  test("program MIPROv2 records proposal failures and keeps the baseline") {
    val proposer = Program.liftEither[MIPROv2.ProposalInput, MIPROv2.Proposal](_ =>
      Left(RuntimeError("proposal", "unavailable"))
    )
    val report = run(proposer)

    assertEquals(
      report.bestProgram.program.parameters.get(answerId).flatMap(_.instructions),
      Some("bad instruction")
    )
    assertEquals(report.metadata("num_proposal_failures"), 1)
    assertEquals(report.metadata("best_score"), 0.0)
    assertEquals(report.candidates.head.metadata("baseline"), true)
  }

  test("program MIPROv2 creates the same trial plans for the same seed") {
    val proposer = Program.lift[MIPROv2.ProposalInput, MIPROv2.Proposal](_ =>
      MIPROv2.Proposal("good instruction")
    )
    val first  = run(proposer)
    val second = run(proposer)

    assertEquals(first.candidates.map(_.metadata), second.candidates.map(_.metadata))
    assertEquals(first.candidates.map(_.score), second.candidates.map(_.score))
    assertEquals(first.bestProgram.program.parameters, second.bestProgram.program.parameters)
  }
