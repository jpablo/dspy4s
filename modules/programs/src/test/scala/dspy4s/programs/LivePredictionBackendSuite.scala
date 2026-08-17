package dspy4s.programs

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.{DspyError, DynamicValues, LmUsage, RuntimeContext, SignatureLayout, :=}
import dspy4s.lm.contracts.{
  LanguageModel, LmChunk, LmMode, LmOutput, LmRequest, LmResponse, Message, MessageRole, StreamingLanguageModel
}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment}

final class LivePredictionBackendSuite extends FunSuite:

  private final case class Question(question: String) derives CanEqual
  private final case class Answer(answer: String) derives CanEqual

  private object PromptAdapter extends Adapter:
    val name: String = "prompt-adapter"

    def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      DynamicValues.requireString(invocation.inputs.values, "question", name).map { question =>
        val instructions = invocation.layout.instructions.getOrElse("missing")
        FormattedPrompt(Vector(Message(MessageRole.User, text = Some(s"$question [$instructions]"))))
      }

    def parse(
        @annotation.unused layout: SignatureLayout,
        output                   : LmOutput
    )(using RuntimeContext): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(DynamicValues.record("answer" := output.text)))

  private object EchoModel extends LanguageModel:
    val id: String   = "echo-model"
    val mode: LmMode = LmMode.Chat

    def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val text = request.messages.headOption.flatMap(_.text).getOrElse("missing message")
      Right(LmResponse(
        outputs = Vector(LmOutput(text)),
        usage = Some(LmUsage(totalTokens = 3, promptTokens = 2, completionTokens = 1))
      ))

  test("the live backend runs the existing adapter and model through the effect boundary") {
    val signature = Signature.derived[Question, Answer]("Answer", instructions = "be direct")
    val program   = Program.predictStable(ParameterId("answer"), signature)
    val backend   = new LivePredictionBackend(EchoModel, PromptAdapter, RuntimeContext())

    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner
          .runJournaled(program, Question("Why?"))
          .provideEnvironment(ZEnvironment(backend: PredictionBackend)))
        .getOrThrowFiberFailure()
    }

    assertEquals(execution.outcome.map(_.output), Right(Answer("Why? [be direct]")))
    assertEquals(execution.outcome.toOption.flatMap(_.raw.lmUsage).map(_.totalTokens), Some(3L))

  }

  test("the live backend streams chunks and assembles the final prediction") {
    var ordinaryCalls  = 0
    val streamingModel = new StreamingLanguageModel:
      val id: String   = "streaming-echo"
      val mode: LmMode = LmMode.Chat

      def call(@annotation.unused request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        ordinaryCalls += 1
        Right(LmResponse(Vector(LmOutput("ordinary"))))

      def stream(@annotation.unused request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
        Iterator(
          LmChunk(text = "hel"),
          LmChunk(text = "lo"),
          LmChunk(finishReason = Some("stop"), usage = Some(LmUsage(3, 2, 1)))
        )

    val signature = Signature.derived[Question, Answer]("Answer")
    val program   = Program.predictStable(ParameterId("answer"), signature)
    val backend   = new LivePredictionBackend(streamingModel, PromptAdapter, RuntimeContext())
    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner
          .runJournaled(program, Question("Why?"))
          .provideEnvironment(ZEnvironment(backend: PredictionBackend)))
        .getOrThrowFiberFailure()
    }

    assertEquals(execution.outcome.map(_.output), Right(Answer("hello")))
    assertEquals(ordinaryCalls, 0)
    assertEquals(
      execution.events.collect { case ProgramEvent.OutputChunk(_, _, _, chunk, _) => chunk },
      Vector(
        PredictionChunk("", "hel"),
        PredictionChunk("", "lo", isLast = true)
      )
    )
    assertEquals(execution.outcome.toOption.flatMap(_.raw.lmUsage).map(_.totalTokens), Some(3L))

    val untraced = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner
          .runJournaled(program, Question("Why?"), RunOptions(traceEnabled = false))
          .provideEnvironment(ZEnvironment(backend: PredictionBackend)))
        .getOrThrowFiberFailure()
    }
    assertEquals(untraced.outcome.map(_.output), Right(Answer("ordinary")))
    assertEquals(untraced.events, Vector.empty)
    assertEquals(ordinaryCalls, 1)
  }
