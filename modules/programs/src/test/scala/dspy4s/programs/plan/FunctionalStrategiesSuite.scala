package dspy4s.programs.plan

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.RawPrediction
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

final class FunctionalStrategiesSuite extends FunSuite:

  private final case class Question(question: String)
  private final case class Answer(answer: String)

  test("chain of thought is one signature transformation and one prediction node") {
    val base = Signature.derived[Question, Answer]("Answer", instructions = "reason first")
    val program = ChainOfThought(ParameterId("answer"), base)
    val backend = new PredictionBackend:
      def generate(@annotation.unused request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        ZIO.succeed(RawPrediction(DynamicValues.record(
          "reasoning" := "the evidence",
          "answer"    := "the result"
        )))

    val prediction = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.run(program, Question("why?")).provideEnvironment(ZEnvironment(backend)))
        .getOrThrowFiberFailure()
    }

    assertEquals(prediction.output.reasoning, "the evidence")
    assertEquals(prediction.output.answer, "the result")
    assertEquals(program.parameters.all.map(_.id), Vector(ParameterId("answer")))
    assertEquals(ProgramGraph.from(program).nodes.map(_.kind), Vector("predict"))
  }

