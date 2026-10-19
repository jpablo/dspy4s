package dspy4s.streaming

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{
  :=,
  ClosableIterator,
  DspyError,
  DynamicValues,
  FieldSpec,
  RuntimeContext,
  SignatureLayout,
  TypeRef
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LmChunk, LmMode, LmOutput, LmRequest, LmResponse, StreamingLanguageModel}
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.streaming.contracts.{ErrorEvent, PredictionEvent, StreamEvent, StreamListener}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** A streamed call of a predictor with decision outputs (experimental; upstream `DecisionAdapter._prepare` raises
  * "Streaming decision evidence is not supported." when the predictor streams, see `Predict._should_stream`).
  */
class StreamingDecisionSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A streaming model that gives `reply` in one chunk and counts the calls. */
  private final class CountingLm(reply: String) extends StreamingLanguageModel:
    @volatile var calls: Int                                                                   = 0
    override val id: String                                                                    = "counting"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      calls += 1
      Right(LmResponse(outputs = Vector(LmOutput(text = reply))))
    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      calls += 1
      Iterator(LmChunk(text = reply, finishReason = Some("stop")))

  private val layout: SignatureLayout = SignatureLayout
    .create(
      name = "Triage",
      inputFields = Vector(FieldSpec("ticket")),
      outputFields = Vector(FieldSpec("urgent", TypeRef.noul, description = Some("Is immediate action needed?")))
    )
    .fold(error => fail(error.message), identity)

  private val reply = "[[ ## urgent ## ]]\n{\"noul\": 0.9}\n\n[[ ## completed ## ]]"

  private def streamed(lm: CountingLm, listeners: Vector[StreamListener]): Vector[StreamEvent] =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val events           = Streamify.streamify(
        program = DynamicPredict(layout = layout, name = Some("triage")),
        streamListeners = listeners,
        warningSink = _ => ()
      )(DynamicValues.record("ticket" := "Checkout fails."))
      collect(events)
    }

  private def collect(events: ClosableIterator[StreamEvent]): Vector[StreamEvent] =
    val builder = Vector.newBuilder[StreamEvent]
    while events.hasNext do builder += events.next()
    builder.result()

  test("a predictor with decision outputs that streams to a listener fails before the request") {
    val lm     = CountingLm(reply)
    val events = streamed(lm, Vector.empty)
    val errors = events.collect { case ErrorEvent(error, _) => error.message }
    assertEquals(errors, Vector("Streaming decision evidence is not supported."), events.toString)
    assertEquals(lm.calls, 0)
  }

  test("a predictor with decision outputs that no listener follows runs with evidence decoding") {
    val lm     = CountingLm(reply)
    val events = streamed(lm, Vector(StreamListener("answer", predictName = Some("other"))))
    val urgent = events.collectFirst { case PredictionEvent(prediction, _) =>
      DynamicValues.recordGet(prediction.values, "urgent")
    }.flatten
    val value = urgent.collect { case record: DynamicValue.Record => DynamicValues.recordGet(record, "value") }.flatten
    assertEquals(value, Some(DynamicValue.Primitive(PrimitiveValue.Boolean(true))), events.toString)
    assertEquals(lm.calls, 1)
  }
