package dspy4s.streaming

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.{ChatAdapter, JSONAdapter, TwoStepAdapter}
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.{LmChunk, LmMode, LmOutput, LmRequest, LmResponse, StreamingLanguageModel}
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.streaming.contracts.{PredictionEvent, StreamEvent, StreamListener, TokenEvent}
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** A stream listener on a reasoning output gets the reasoning of the stream (upstream `Reasoning.parse_stream_chunk`,
  * `StreamListener.receive`, and the test of `dspy.Reasoning` in `tests/streaming/test_streaming.py`).
  */
class ReasoningStreamSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class Script(reasoning: Boolean, chunks: Vector[Vector[LmChunk]]) extends StreamingLanguageModel:
    val calls: AtomicInteger                                                                   = AtomicInteger(0)
    val requests: ArrayBuffer[LmRequest]                                                       = ArrayBuffer.empty
    override val id: String                                                                    = "script"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsReasoning: Boolean                                                    = reasoning
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = calls.incrementAndGet()
      Right(LmResponse(outputs = Vector(LmOutput(text = chunks.flatten.map(_.text).mkString))))
    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      requests += request
      chunks(calls.getAndIncrement()).iterator

  private def events(model: Script, adapter: Adapter, listeners: StreamListener*): Vector[StreamEvent] =
    eventsWith(model, adapter, includeFinalPrediction = true, listeners*)

  private def eventsWith(
      model                 : Script,
      adapter               : Adapter,
      includeFinalPrediction: Boolean,
      listeners             : StreamListener*
  ): Vector[StreamEvent] =
    val layout = SignatureDsl.parse("question -> reasoning: dspy.Reasoning, answer")
      .fold(error => fail(error.toString), identity)
    val program = DynamicPredict(layout = layout)
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model), adapter = Some(adapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream           = Streamify.streamify(
        program,
        streamListeners = listeners.toVector,
        includeFinalPrediction = includeFinalPrediction
      )(
        DynamicValues.record("question" := "Q?")
      )
      val found = ArrayBuffer.empty[StreamEvent]
      while stream.hasNext do found += stream.next()
      found.toVector
    }

  private def tokens(found: Vector[StreamEvent], field: String): Vector[String] =
    found.collect { case token: TokenEvent if token.fieldName == field => token.chunk }

  private def prediction(found: Vector[StreamEvent]): DynamicValue.Record =
    found.collectFirst { case event: PredictionEvent => event.prediction.values }
      .getOrElse(fail(s"no prediction: $found"))

  private def text(values: DynamicValue.Record, name: String): Option[String] =
    DynamicValues.recordGet(values, name).flatMap(DynamicValues.asString)

  private def reasoningChunks: Vector[LmChunk] =
    Vector(
      LmChunk(reasoning = "First, think. "),
      LmChunk(reasoning = "Then, consider. "),
      LmChunk(reasoning = "So, cross.")
    )

  private def answerChunks: Vector[LmChunk] =
    Vector(
      LmChunk(text = "[[ ## answer ## ]]\n"),
      LmChunk(text = "To get"),
      LmChunk(text = " there"),
      LmChunk(text = "\n\n[[ ## completed ## ]]", finishReason = Some("stop"))
    )

  test("the listeners of a native reasoning stream get the reasoning and the answer") {
    val model = Script(reasoning = true, Vector(reasoningChunks ++ answerChunks))
    val found = events(model, ChatAdapter(), StreamListener("reasoning"), StreamListener("answer"))
    assertEquals(tokens(found, "reasoning"), Vector("First, think. ", "Then, consider. ", "So, cross."))
    assertEquals(tokens(found, "answer").mkString.strip, "To get there")
    val values = prediction(found)
    assertEquals(text(values, "reasoning"), Some("First, think. Then, consider. So, cross."))
    assertEquals(text(values, "answer"), Some("To get there"))
    assertEquals(
      DynamicValues.recordGet(model.requests.head.options, "reasoning_effort"),
      Some(DynamicValues.string("low"))
    )
  }

  test("a listener on the answer alone gets no reasoning") {
    val model = Script(reasoning = true, Vector(reasoningChunks ++ answerChunks))
    val found = events(model, ChatAdapter(), StreamListener("answer"))
    assertEquals(tokens(found, "reasoning"), Vector.empty)
    assertEquals(tokens(found, "answer").mkString.strip, "To get there")
  }

  test("the listener of a text field gets the text on a model with no reasoning") {
    val chunks = Vector(
      LmChunk(text = "[[ ## reasoning ## ]]\n"),
      LmChunk(text = "written"),
      LmChunk(text = "\n\n[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]", finishReason = Some("stop"))
    )
    val model = Script(reasoning = false, Vector(chunks))
    val found = events(model, ChatAdapter(), StreamListener("reasoning"))
    assertEquals(tokens(found, "reasoning").mkString.strip, "written")
    assertEquals(text(prediction(found), "reasoning"), Some("written"))
  }

  test("a reasoning token to a listener stops the fallback") {
    val broken = reasoningChunks :+ LmChunk(text = "", finishReason = Some("stop"))
    val model  = Script(reasoning = true, Vector(broken, answerChunks))
    val found  = events(model, ChatAdapter(), StreamListener("reasoning"))
    assertEquals(model.calls.get, 1)
    assertEquals(tokens(found, "reasoning").size, 3)
  }

  test("a listener on the reasoning alone does not hide the prediction") {
    val model = Script(reasoning = true, Vector(reasoningChunks ++ answerChunks))
    val found = eventsWith(model, ChatAdapter(), includeFinalPrediction = false, StreamListener("reasoning"))
    assertEquals(tokens(found, "reasoning").size, 3)
    assertEquals(text(prediction(found), "answer"), Some("To get there"))
  }

  test("a listener that got a token of the answer hides the prediction") {
    val model = Script(reasoning = true, Vector(reasoningChunks ++ answerChunks))
    val found = eventsWith(
      model,
      ChatAdapter(),
      includeFinalPrediction = false,
      StreamListener("reasoning"),
      StreamListener("answer")
    )
    assertEquals(tokens(found, "answer").mkString.strip, "To get there")
    assert(!found.exists(_.isInstanceOf[PredictionEvent]), found.toString)
  }

  test("a stream with no listener names the field of the reasoning tokens") {
    val model = Script(reasoning = true, Vector(reasoningChunks ++ answerChunks))
    val found = events(model, ChatAdapter())
    assertEquals(tokens(found, "reasoning").size, 3)
  }

  test("the stream state follows the plan: the JSON form has no reasoning key") {
    val chunks = Vector(
      LmChunk(reasoning = "why"),
      LmChunk(text = """{"answer": "Pa"""),
      LmChunk(text = """ris"}""", finishReason = Some("stop"))
    )
    val model = Script(reasoning = true, Vector(chunks))
    val found = events(model, JSONAdapter(), StreamListener("answer"))
    assertEquals(tokens(found, "answer").mkString, "Paris")
    assertEquals(text(prediction(found), "reasoning"), Some("why"))
  }

  test("the reasoning of the extraction model of TwoStepAdapter gives no token when the main model has reasoning") {
    val main = Script(
      reasoning = true,
      Vector(reasoningChunks :+ LmChunk(text = "It is that way.", finishReason = Some("stop")))
    )
    val extraction = Script(
      reasoning = true,
      Vector(Vector(LmChunk(reasoning = "Extraction thought. ")) ++ answerChunks)
    )
    val found = events(main, TwoStepAdapter(extraction), StreamListener("reasoning"))
    assertEquals(tokens(found, "reasoning"), Vector("First, think. ", "Then, consider. ", "So, cross."))
    assertEquals(text(prediction(found), "reasoning"), Some("First, think. Then, consider. So, cross."))
    assertEquals(text(prediction(found), "answer"), Some("To get there"))
  }

  test(
    "the tokens of the reasoning field of TwoStepAdapter are the final value when only the extraction has reasoning"
  ) {
    val main = Script(
      reasoning = false,
      Vector(Vector(LmChunk(text = "It is that way.", finishReason = Some("stop"))))
    )
    val extraction = Script(
      reasoning = true,
      Vector(Vector(LmChunk(reasoning = "Extraction thought.")) ++ answerChunks)
    )
    val found = events(main, TwoStepAdapter(extraction), StreamListener("reasoning"))
    assertEquals(tokens(found, "reasoning"), Vector("Extraction thought."))
    assertEquals(text(prediction(found), "reasoning"), Some("Extraction thought."))
  }
