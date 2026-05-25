package dspy4s.streaming

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.Settings
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.programs.DynamicPredict
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.streaming.contracts.ErrorEvent
import dspy4s.streaming.contracts.PredictionEvent
import dspy4s.streaming.contracts.StatusEvent
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.TokenEvent
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

class StreamifySuite extends FunSuite:

  private final class ScriptedStreamingLm(chunks: Vector[LmChunk]) extends StreamingLanguageModel:
    val calls = AtomicInteger(0)
    override val id: String = "scripted-stream"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      calls.incrementAndGet()
      val text = chunks.map(_.text).mkString
      Right(LmResponse(outputs = Vector(LmOutput(text = text))))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      calls.incrementAndGet()
      chunks.iterator

  private object PassthroughAdapter extends Adapter:
    override val name: String = "passthrough"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("x")))))
    override def parse(signature: dspy4s.core.contracts.SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = signature.outputFields.map(_.name -> output.text).toMap))

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("streamify emits token events and final prediction for streaming LM") {
    val chunks = Vector(
      LmChunk(text = "To "),
      LmChunk(text = "get "),
      LmChunk(text = "there"),
      LmChunk(text = ".", finishReason = Some("stop"))
    )
    val lm = new ScriptedStreamingLm(chunks)
    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val program = DynamicPredict(layout = signature)

    RuntimeEnvironment.withSettings(
      Settings(
        Map(
          SettingKeys.languageModel.name -> lm,
          SettingKeys.adapter.name -> PassthroughAdapter
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(program)(Map("question" -> "x"))

      val events = ArrayBuffer.empty[StreamEvent]
      while stream.hasNext do events += stream.next()

      val tokenEvents = events.collect { case e: TokenEvent => e }
      val predictionEvents = events.collect { case e: PredictionEvent => e }

      assertEquals(predictionEvents.size, 1)
      assertEquals(predictionEvents.head.prediction.values("answer"), "To get there.")
      assert(tokenEvents.size >= 1)
      assertEquals(tokenEvents.map(_.chunk).mkString, "To get there.")
      assertEquals(tokenEvents.last.isLastChunk, true)
    }
  }

  test("streamify emits status events for lm calls with custom provider") {
    val lm = new ScriptedStreamingLm(Vector(LmChunk(text = "final", finishReason = Some("stop"))))
    val signature = SignatureDsl.parse("question -> answer").toOption.get

    RuntimeEnvironment.withSettings(
      Settings(
        Map(
          SettingKeys.languageModel.name -> lm,
          SettingKeys.adapter.name -> PassthroughAdapter
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = DynamicPredict(layout = signature),
        statusMessageProvider = Some(new StatusMessageProvider:
          override def lmStart(modelId: String, inputs: Map[String, Any]): Option[String] =
            Some(s"Calling $modelId...")
        )
      )(Map("question" -> "x"))

      val statuses = ArrayBuffer.empty[StatusEvent]
      while stream.hasNext do
        stream.next() match
          case e: StatusEvent => statuses += e
          case _              => ()
      assert(statuses.exists(_.message == "Calling scripted-stream..."))
    }
  }

  test("streamify works with non-streaming LM — only status and prediction events") {
    val nonStreaming = new LanguageModel:
      override val id: String = "non-streaming"
      override val mode: LmMode = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        Right(LmResponse(outputs = Vector(LmOutput(text = "complete answer"))))

    val signature = SignatureDsl.parse("question -> answer").toOption.get
    val receivedStatus = new AtomicInteger(0)
    val provider = new StatusMessageProvider:
      override def moduleStart(instanceName: String, inputs: Map[String, Any]): Option[String] =
        receivedStatus.incrementAndGet()
        Some("starting")

    RuntimeEnvironment.withSettings(
      Settings(
        Map(
          SettingKeys.languageModel.name -> nonStreaming,
          SettingKeys.adapter.name -> PassthroughAdapter
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = DynamicPredict(layout = signature),
        statusMessageProvider = Some(provider)
      )(Map("question" -> "x"))

      val events = ArrayBuffer.empty[StreamEvent]
      while stream.hasNext do events += stream.next()

      val statuses = events.collect { case e: StatusEvent => e }
      val predictions = events.collect { case e: PredictionEvent => e }
      val tokens = events.collect { case e: TokenEvent => e }

      assertEquals(predictions.size, 1)
      assertEquals(predictions.head.prediction.values("answer"), "complete answer")
      assert(statuses.nonEmpty)
      assertEquals(tokens.size, 0)
    }
  }

  test("streamify emits error event when program fails") {
    val failing = new PredictProgram:
      override val moduleName: String = "failing"
      override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
        Left(dspy4s.core.contracts.RuntimeError("test", "program failed"))

    given RuntimeContext = RuntimeEnvironment.current
    val stream = Streamify.streamify(program = failing)(Map.empty)

    val events = ArrayBuffer.empty[StreamEvent]
    while stream.hasNext do events += stream.next()

    val errors = events.collect { case e: ErrorEvent => e }
    assertEquals(errors.size, 1)
  }

  test("streamify can be called multiple times producing independent streams") {
    val lm = new ScriptedStreamingLm(Vector(LmChunk(text = "a", finishReason = Some("stop"))))
    val signature = SignatureDsl.parse("q -> a").toOption.get

    RuntimeEnvironment.withSettings(
      Settings(
        Map(
          SettingKeys.languageModel.name -> lm,
          SettingKeys.adapter.name -> PassthroughAdapter
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val streamFn = Streamify.streamify(DynamicPredict(layout = signature))

      val first = ArrayBuffer.empty[StreamEvent]
      val iter1 = streamFn(Map("q" -> "1"))
      while iter1.hasNext do first += iter1.next()
      iter1.close()

      val second = ArrayBuffer.empty[StreamEvent]
      val iter2 = streamFn(Map("q" -> "2"))
      while iter2.hasNext do second += iter2.next()
      iter2.close()

      val firstPredictions = first.collect { case e: PredictionEvent => e.prediction.values("a") }
      val secondPredictions = second.collect { case e: PredictionEvent => e.prediction.values("a") }
      assertEquals(firstPredictions.size, 1)
      assertEquals(secondPredictions.size, 1)
    }
  }
