package dspy4s.gepa

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.gepa.contracts.{FeedbackMetric, ScoreWithFeedback}
import dspy4s.programs.*
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

private final case class EngineQuestion(question: String)
private final case class EngineAnswer(answer: String)

final class GepaEngineSuite extends FunSuite:

  private val signature = Signature.derived[EngineQuestion, EngineAnswer]("Engine", "bad")
  private val program   = Program.predictStable(ParameterId("answer"), signature).fromRecords(signature.inputShape)
  private val example   = Example(DynamicValues.record("question" := "q", "answer" := "right"), Set("question"))

  private val backend = new PredictionBackend:
    def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
      val answer = if request.layout.instructions.contains("good") then "right" else "wrong"
      ZIO.succeed(RawPrediction(DynamicValues.record("answer" := answer)))

  private val metric = new FeedbackMetric:
    val name: String = "exact"

    def feedback(
        example                           : Example,
        prediction                        : RawPrediction,
        @annotation.unused events         : Vector[ProgramEvent],
        @annotation.unused component      : Option[ParameterId],
        @annotation.unused componentEvents: Vector[ProgramEvent]
    ): ZIO[Any, DspyError, ScoreWithFeedback] =
      ZIO.fromEither(for
        expected <- DynamicValues.requireString(example.labels, "answer", "metric")
        actual   <- prediction.asString("answer")
      yield ScoreWithFeedback(if expected == actual then 1.0 else 0.0, s"Expected $expected"))

  test("engine accepts an improved instruction from a visible reflection program") {
    val adapter   = new GepaAdapter(program, metric, parallelism = 1)
    val reflector = Program.lift[InstructionProposer.Input, InstructionProposer.Output](_ =>
      InstructionProposer.Output("good")
    )
    val engine = new GepaEngine(
      adapter,
      reflector,
      GepaConfig(
        maxMetricCalls = MetricCallCount(4),
        reflectionMinibatchSize = MinibatchSize(1),
        useMerge = false,
        parallelism = 1
      )
    )

    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(engine.optimize(Candidate.seed(program), Vector(example), Vector(example)).provideEnvironment(
          ZEnvironment(backend)
        ))
        .getOrThrowFiberFailure()
    }

    assertEquals(result.bestScore, 1.0)
    assertEquals(result.bestCandidate(ParameterId("answer")), Some("good"))
    assertEquals(result.numCandidates, GepaCandidateCount(2))
  }
