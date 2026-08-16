package dspy4s.optimize

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.evaluate.ProgramMetric
import dspy4s.programs.plan.*
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

final class ProgramBootstrapRandomSearchSuite extends FunSuite:

  private final case class Question(question: String)
  private final case class Answer(answer: String)

  private val signature = Signature.derived[Question, Answer]("Answer")
  private val program   = Program.predict(ParameterId("answer"), signature).fromRecords(signature.inputShape)

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
      ZIO.fromEither(DynamicValues.requireString(request.inputs, "question", "random search test")).map { question =>
        val trained = request.demos.nonEmpty || request.rolloutId.nonEmpty
        val answer  = if trained then question.reverse else "wrong"
        RawPrediction(DynamicValues.record("answer" := answer))
      }

  private val trainset = Vector(
    Example(DynamicValues.record("question" := "abc", "answer" := "cba"), Set("question")),
    Example(DynamicValues.record("question" := "xyz", "answer" := "zyx"), Set("question"))
  )

  private def run(
      config: ProgramBootstrapRandomSearchConfig
  ): dspy4s.optimize.contracts.OptimizationReport[RecordProgram[Question, Answer]] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          ProgramBootstrapRandomSearch(program, trainset, config = config)
            .provideEnvironment(ZEnvironment(backend))
        )
        .getOrThrowFiberFailure()
    }

  test("random search builds, scores, and selects record-program candidates without runtime globals") {
    val report = run(ProgramBootstrapRandomSearchConfig(
      metric = metric,
      numCandidates = SearchCandidateCount(2),
      maxBootstrappedDemos = DemoCount(1),
      maxLabeledDemos = DemoCount(1),
      seed = 7L
    ))

    assertEquals(report.metadata("best_seed"), -2)
    assertEquals(report.metadata("best_score"), 100.0)
    assertEquals(report.metadata("num_candidates"), 5)
    assertEquals(report.metadata("num_skipped"), 0)
    assertEquals(
      report.candidates.map(_.metadata("seed").asInstanceOf[Int]).toSet,
      Set(-3, -2, -1, 0, 1)
    )
    assert(report.candidates.forall(_.evaluation.nonEmpty))
    assertEquals(report.candidates.map(_.score), Vector(100.0, 100.0, 100.0, 100.0, 0.0))

    val selectedDemos = report.bestProgram.program.parameters.all.head.value.demos
    assertEquals(selectedDemos.size, 1)
    assert(!selectedDemos.head.augmented)
  }

  test("random search stops evaluation at the first candidate that reaches the score target") {
    val report = run(ProgramBootstrapRandomSearchConfig(
      metric = metric,
      numCandidates = SearchCandidateCount(2),
      maxBootstrappedDemos = DemoCount(1),
      maxLabeledDemos = DemoCount(1),
      stopAtScore = Some(100.0),
      seed = 7L
    ))

    assertEquals(report.metadata("stopped_early"), true)
    assertEquals(report.metadata("best_seed"), -2)
    assertEquals(report.metadata("num_candidates"), 2)
    assertEquals(report.candidates.map(_.metadata("seed").asInstanceOf[Int]), Vector(-2, -3))
  }
