package dspy4s.examples

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.RawPrediction
import dspy4s.programs.{ParameterId, PredictionBackend, PredictionRequest, Program, ProgramRunner}
import dspy4s.signatures.Signature
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

final case class Question(question: String)
final case class Answer(answer: String)

/** A typed program is immutable syntax. Effects enter only through ProgramRunner. */
@main def functionalQuickstart(): Unit =
  val signature = Signature.derived[Question, Answer]("Answer", "Answer the question in one short sentence.")
  val answer     = Program.predict(ParameterId("answer"), signature)
  val text       = answer >>> Program.lift[Answer, String](_.answer.trim)

  val backend = new PredictionBackend:
    def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
      ZIO.succeed(RawPrediction(DynamicValues.record("answer" := s"Received ${request.inputs.fields.size} field.")))

  val result = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe
      .run(ProgramRunner.run(text, Question("What is a functional program?")).provideEnvironment(
        ZEnvironment(backend)
      ))
      .getOrThrowFiberFailure()
  }

  println(result.output)
