package dspy4s.programs.plan

import dspy4s.core.contracts.{DspyError, DynamicValues, ValidationError, :=}
import dspy4s.core.data.RawPrediction
import dspy4s.programs.contracts.Prediction
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

import scala.collection.mutable.ArrayBuffer

final class FunctionalRefineSuite extends FunSuite:

  private final case class Question(question: String)
  private final case class Answer(answer: String)

  private val answerId  = ParameterId("answer")
  private val signature = Signature.derived[Question, Answer]("Answer", instructions = "answer the question")
  private val task      = Program.predict(answerId, signature)

  private def run(program: Program[Question, Answer], backend: PredictionBackend) = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe
      .run(ProgramRunner.run(program, Question("abc")).provideEnvironment(ZEnvironment(backend)))
      .getOrThrowFiberFailure()
  }

  private val reward = (input: Question, prediction: Prediction[Answer]) =>
    Right(if prediction.output.answer == input.question.reverse then 1.0 else 0.0)

  test("functional Refine applies typed stable-ID advice and returns the accepted attempt evidence") {
    val requests = ArrayBuffer.empty[PredictionRequest]
    val backend = new PredictionBackend:
      def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        requests += request
        val answer = if request.layout.instructions.exists(_.toLowerCase.contains("reverse")) then "cba" else "wrong"
        ZIO.succeed(RawPrediction(DynamicValues.record("answer" := answer)))

    val critic = Program.lift[Refine.Attempt[Question, Answer], Refine.Advice] { attempt =>
      assertEquals(attempt.number, 1)
      assertEquals(attempt.score, 0.0)
      Refine.Advice(Map(answerId -> "Reverse the input text."))
    }
    val refined = Refine(task, critic, maxAttempts = 3, threshold = 1.0)(reward)
    val result  = run(refined, backend)

    assertEquals(result.output, Answer("cba"))
    assertEquals(DynamicValues.requireString(result.raw.values, "answer", "refine test"), Right("cba"))
    assertEquals(requests.size, 2)
    assert(!requests.head.layout.instructions.exists(_.contains("Feedback for this attempt")))
    assert(requests(1).layout.instructions.exists(_.contains("Reverse the input text.")))
    assertEquals(task.parameters.get(answerId).flatMap(_.instructions), Some("answer the question"))
    val kinds = ProgramGraph.from(refined).nodes.map(_.kind)
    assert(kinds.contains("from_evidence"))
    assert(kinds.contains("local_parameters"))
    assert(kinds.contains("iterate"))
  }

  test("functional Refine keeps the earliest attempt on equal scores") {
    val backend = new PredictionBackend:
      def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        ZIO.succeed(RawPrediction(DynamicValues.record("answer" := s"attempt-${request.rolloutId.getOrElse(-1)}")))
    val critic = Program.lift[Refine.Attempt[Question, Answer], Refine.Advice](_ => Refine.Advice(Map.empty))
    val refined = Refine(task, critic, maxAttempts = 2, threshold = 1.0)((_, _) => Right(0.0))
    val result  = run(refined, backend)

    assertEquals(result.output, Answer("attempt-0"))
    assertEquals(DynamicValues.requireString(result.raw.values, "answer", "refine tie"), Right("attempt-0"))
  }

  test("functional Refine rejects advice for an unknown parameter ID") {
    val backend = new PredictionBackend:
      def generate(@annotation.unused request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        ZIO.succeed(RawPrediction(DynamicValues.record("answer" := "wrong")))
    val critic = Program.lift[Refine.Attempt[Question, Answer], Refine.Advice](_ =>
      Refine.Advice(Map(ParameterId("unknown") -> "advice"))
    )
    val refined = Refine(task, critic, maxAttempts = 2, threshold = 1.0)((_, _) => Right(0.0))

    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.run(refined, Question("abc")).either.provideEnvironment(ZEnvironment(backend)))
        .getOrThrowFiberFailure()
    }
    assert(result match
      case Left(_: ValidationError) => true
      case _                        => false)
  }
