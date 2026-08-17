package dspy4s.optimize

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.programs.*
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

final class BootstrapFewShotSuite extends FunSuite:

  private final case class Question(question: String)
  private final case class Answer(answer: String)

  private val signature = Signature.derived[Question, Answer]("Answer")
  private val program   = Program.predictStable(ParameterId("answer"), signature).fromRecords(signature.inputShape)

  private val backend = new PredictionBackend:
    def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
      ZIO.fromEither(DynamicValues.requireString(request.inputs, "question", "bootstrap test")).map { question =>
        RawPrediction(DynamicValues.record("answer" := question.reverse))
      }

  private def run[A](effect: ZIO[PredictionBackend, DspyError, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(effect.provideEnvironment(ZEnvironment(backend)))
        .getOrThrowFiberFailure()
    }

  test("bootstrap few-shot uses effectful record execution and stable parameter IDs") {
    val trainset = Vector(
      Example(DynamicValues.record("question" := "abc", "answer" := "cba"), Set("question")),
      Example(DynamicValues.record("question" := "xyz", "answer" := "zyx"), Set("question"))
    )
    val report = run(BootstrapFewShot(
      program,
      trainset,
      config = BootstrapFewShotConfig(maxBootstrappedDemos = DemoCount(2))
    ))
    val binding = report.bestProgram.program.parameters.all.head

    assertEquals(binding.id, ParameterId("answer"))
    assertEquals(binding.value.demos.size, 2)
    assert(binding.value.demos.forall(_.augmented))
    assertEquals(
      binding.value.demos.map(demo => DynamicValues.requireString(demo.values, "answer", "test")),
      Vector(Right("cba"), Right("zyx"))
    )
  }
