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
  TypeRef,
  ValidationError
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{DecisionRequests, LanguageModel, LmMode, LmRequest, LmResponse}
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.streaming.contracts.{ErrorEvent, PredictionEvent, StreamEvent, StreamListener}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** A streamed call of a predictor with a System One model (experimental; upstream `DecisionAdapter._prepare` with
  * `system_one`). The stream wrapper forwards `supportsDecisionRequests`, so `Predict` sends a decision request through
  * it.
  */
class StreamingSystemOneSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A System One model that answers each Noul question with P(true) 0.9 and keeps the requests. */
  private final class FakeJev extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val id: String                                                                    = "jev-test"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsDecisionRequests: Boolean                                             = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests = requests :+ request
      request.decision.map(_.questions).toRight(ValidationError("not a decision request")).map { questions =>
        val answers = DynamicValues.recordKeys(questions).map(name =>
          name -> DynamicValues.record("type" := "noul", "noul" := 0.9)
        )
        DecisionRequests.response(DynamicValues.recordFromEntries(answers), None, Some(id))
      }

  private val layout: SignatureLayout = SignatureLayout
    .create(
      name = "Triage",
      inputFields = Vector(FieldSpec("ticket")),
      outputFields = Vector(FieldSpec("urgent", TypeRef.noul, description = Some("Is immediate action needed?")))
    )
    .fold(error => fail(error.message), identity)

  private def streamed(lm: LanguageModel, listeners: Vector[StreamListener]): Vector[StreamEvent] =
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

  test("a System One predictor that streams to a listener fails before the request") {
    val fake   = FakeJev()
    val events = streamed(ManagedLanguageModel(fake), Vector.empty)
    val errors = events.collect { case ErrorEvent(error, _) => error.message }
    assertEquals(errors, Vector("Streaming decision evidence is not supported."), events.toString)
    assertEquals(fake.requests.size, 0)
  }

  test("a System One predictor that no listener follows sends a decision request through the stream wrapper") {
    val fake   = FakeJev()
    val events = streamed(ManagedLanguageModel(fake), Vector(StreamListener("answer", predictName = Some("other"))))
    val urgent = events.collectFirst { case PredictionEvent(prediction, _) =>
      DynamicValues.recordGet(prediction.values, "urgent")
    }.flatten
    val value = urgent.collect { case record: DynamicValue.Record => DynamicValues.recordGet(record, "value") }.flatten
    assertEquals(value, Some(DynamicValue.Primitive(PrimitiveValue.Boolean(true))), events.toString)
    assertEquals(fake.requests.map(_.messages.size), Vector(0), "a decision request has no messages")
  }
