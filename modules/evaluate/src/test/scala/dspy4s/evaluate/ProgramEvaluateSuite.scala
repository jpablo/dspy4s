package dspy4s.evaluate

import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeError, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.programs.plan.*
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

final class ProgramEvaluateSuite extends FunSuite:

  private final case class Question(question: String)
  private final case class Answer(answer: String)

  private val signature = Signature.derived[Question, Answer]("Answer")
  private val program   = Program.predict(ParameterId("answer"), signature).fromRecords(signature.inputShape)

  private val backend = new PredictionBackend:
    def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
      ZIO.fromEither(DynamicValues.requireString(request.inputs, "question", "test")).map { question =>
        RawPrediction(DynamicValues.record("answer" := question.reverse))
      }

  private val exact = new ProgramMetric:
    val name: String = "exact"

    def score(
        example   : Example,
        prediction: RawPrediction,
        @annotation.unused events: Vector[ProgramEvent]
    ): ZIO[Any, DspyError, Double] =
      ZIO.fromEither(for
        expected <- DynamicValues.requireString(example.labels, "answer", "metric")
        actual   <- prediction.asString("answer")
      yield if expected == actual then 1.0 else 0.0)

  private def run[A](effect: ZIO[PredictionBackend, Nothing, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(effect.provideEnvironment(ZEnvironment(backend)))
        .getOrThrowFiberFailure()
    }

  test("effectful evaluation runs record programs in parallel and preserves dataset order") {
    val dataset = Vector(
      Example(DynamicValues.record("question" := "abc", "answer" := "cba"), Set("question")),
      Example(DynamicValues.record("question" := "xyz", "answer" := "zyx"), Set("question"))
    )

    val result = run(ProgramEvaluate(program, dataset, exact, ProgramEvaluateOptions(parallelism = 2)))

    assertEquals(result.score, 100.0)
    assertEquals(result.results.map(_.example), dataset)
  }

  test("program failure becomes an explicit scored row") {
    val failingBackend = new PredictionBackend:
      def generate(@annotation.unused request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        ZIO.fail(RuntimeError("expected", "failure"))
    val dataset = Vector(Example(DynamicValues.record("question" := "abc", "answer" := "cba"), Set("question")))
    val options = ProgramEvaluateOptions(failureScore = -1.0, includeErrors = true)
    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramEvaluate(program, dataset, exact, options).provideEnvironment(ZEnvironment(failingBackend)))
        .getOrThrowFiberFailure()
    }

    assertEquals(result.score, -100.0)
    assertEquals(result.results.head.error, Some("[runtime_error] failure"))
  }
