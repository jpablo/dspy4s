package dspy4s.gepa

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.gepa.contracts.{FeedbackMetric, ScoreWithFeedback}
import dspy4s.programs.*
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

import java.util.concurrent.atomic.AtomicInteger

private final case class AdapterQuestion(question: String)
private final case class AdapterAnswer(answer: String)

final class GepaAdapterSuite extends FunSuite:

  private val signature = Signature.derived[AdapterQuestion, AdapterAnswer]("Answer")
  private val program   = Program.predictStable(ParameterId("answer"), signature).fromRecords(signature.inputShape)
  private val example   = Example(DynamicValues.record("question" := "abc", "answer" := "cba"), Set("question"))

  private val metric = new FeedbackMetric:
    val name: String = "exact"

    def feedback(
        example                           : Example,
        prediction                        : RawPrediction,
        @annotation.unused events         : Vector[ProgramEvent],
        component                         : Option[ParameterId],
        @annotation.unused componentEvents: Vector[ProgramEvent]
    ): ZIO[Any, DspyError, ScoreWithFeedback] =
      ZIO.fromEither(for
        expected <- DynamicValues.requireString(example.labels, "answer", "metric")
        actual   <- prediction.asString("answer")
      yield ScoreWithFeedback(if expected == actual then 1.0 else 0.0, s"component=$component"))

  private def countingBackend(calls: AtomicInteger): PredictionBackend =
    new PredictionBackend:
      def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        calls.incrementAndGet()
        ZIO.fromEither(DynamicValues.requireString(request.inputs, "question", "backend")).map { question =>
          RawPrediction(DynamicValues.record("answer" := question.reverse))
        }

  private def run[A](effect: ZIO[PredictionBackend, Nothing, A], backend: PredictionBackend): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect.provideEnvironment(ZEnvironment(backend))).getOrThrowFiberFailure()
    }

  test("adapter evaluates a candidate and captures parameter-tagged evidence") {
    val calls   = AtomicInteger(0)
    val adapter = new GepaAdapter(program, metric)
    val result  =
      run(adapter.evaluate(Vector(example), Candidate.seed(program), captureEvents = true), countingBackend(calls))
    val records = run(adapter.makeReflectiveDataset(result, Vector(ParameterId("answer"))), countingBackend(calls))

    assertEquals(result.scores, Vector(1.0))
    assertEquals(calls.get(), 1)
    assert(records(ParameterId("answer")).head.feedback.contains("answer"))
  }

  test("evaluation cache avoids repeated candidate-example calls") {
    val calls   = AtomicInteger(0)
    val adapter = new GepaAdapter(program, metric)
    val cache   = new GepaEvalCache(adapter)
    val seed    = Candidate.seed(program)
    val backend = countingBackend(calls)

    val first  = run(cache.scores(seed, Vector(example, example)), backend)
    val second = run(cache.scores(seed, Vector(example)), backend)

    assertEquals(first, Vector(1.0, 1.0) -> 1)
    assertEquals(second, Vector(1.0)     -> 0)
    assertEquals(calls.get(), 1)
  }
