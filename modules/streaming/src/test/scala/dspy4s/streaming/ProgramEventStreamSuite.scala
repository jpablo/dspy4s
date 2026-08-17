package dspy4s.streaming

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.RawPrediction
import dspy4s.programs.*
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

final class ProgramEventStreamSuite extends FunSuite:

  private final case class Question(question: String)
  private final case class Answer(answer: String)

  private val signature = Signature.derived[Question, Answer]("Answer")
  private val program   = Program.predictStable(ParameterId("answer"), signature)

  private val backend = new PredictionBackend:
    def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
      ZIO.fromEither(DynamicValues.requireString(request.inputs, "question", "stream test")).map { question =>
        RawPrediction(DynamicValues.record("answer" := question.reverse))
      }

  test("the stream emits live program events followed by the typed result") {
    val items = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramEventStream
          .run(program, Question("abc"))
          .runCollect
          .provideEnvironment(ZEnvironment(backend)))
        .getOrThrowFiberFailure()
        .toVector
    }

    assertEquals(items.collect { case ProgramStreamItem.Event(event) => event }.size, 2)
    assertEquals(
      items.collect { case ProgramStreamItem.Result(prediction) => prediction.output },
      Vector(Answer("cba"))
    )
  }

  test("the stream emits backend chunks between prediction start and completion") {
    val streamingBackend = new PredictionBackend:
      def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        ZIO.succeed(RawPrediction(DynamicValues.record("answer" := "cba")))

      override def generateStreaming(
          request: PredictionRequest,
          emit   : PredictionChunk => ZIO[Any, Nothing, Unit]
      ): ZIO[Any, DspyError, RawPrediction] =
        emit(PredictionChunk("answer", "c")) *>
          emit(PredictionChunk("answer", "ba", isLast = true)) *>
          generate(request)

    val items = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramEventStream
          .run(program, Question("abc"))
          .runCollect
          .provideEnvironment(ZEnvironment(streamingBackend)))
        .getOrThrowFiberFailure()
        .toVector
    }
    val events = items.collect { case ProgramStreamItem.Event(event) => event }

    assertEquals(
      events.map {
        case _: ProgramEvent.Started     => "started"
        case _: ProgramEvent.OutputChunk => "chunk"
        case _: ProgramEvent.Completed   => "completed"
        case _: ProgramEvent.Failed      => "failed"
      },
      Vector("started", "chunk", "chunk", "completed")
    )
    assertEquals(
      events.collect { case ProgramEvent.OutputChunk(callId, parent, component, chunk, parameterId) =>
        (callId, parent, component, chunk, parameterId)
      },
      Vector(
        (0, None, "predict", PredictionChunk("answer", "c"), Some(ParameterId("answer"))),
        (0, None, "predict", PredictionChunk("answer", "ba", isLast = true), Some(ParameterId("answer")))
      )
    )
  }

  test("the stream preserves a non-prediction capability requirement") {
    val codeBackend = new CodeExecutionBackend:
      def execute(code: String): ZIO[Any, DspyError, CodeExecutionResult] =
        ZIO.succeed(CodeExecutionResult.Succeeded(s"executed: $code"))

    val items = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramEventStream
          .run(Program.executeCode, "2 + 2")
          .runCollect
          .provideEnvironment(ZEnvironment(codeBackend)))
        .getOrThrowFiberFailure()
        .toVector
    }

    assertEquals(
      items.collect { case ProgramStreamItem.Result(prediction) => prediction.output },
      Vector(CodeExecutionResult.Succeeded("executed: 2 + 2"))
    )
  }
