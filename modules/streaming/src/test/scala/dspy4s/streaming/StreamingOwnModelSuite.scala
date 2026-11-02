package dspy4s.streaming

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{
  ClosableIterator,
  DspyError,
  FieldSpec,
  RuntimeContext,
  RuntimeError,
  SignatureLayout,
  TypeRef,
  :=
}
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.{
  LanguageModel,
  LmChunk,
  LmMode,
  LmOutput,
  LmRequest,
  LmResponse,
  StreamingLanguageModel
}
import dspy4s.programs.contracts.{DynamicModule, ProgramCall}
import dspy4s.programs.strategies.{DynamicPredict, Predict}
import dspy4s.signatures.Signature
import dspy4s.streaming.contracts.{ErrorEvent, PredictionEvent, StreamEvent, StreamListener, TokenEvent}
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Each model call of a program under `streamify` streams, as upstream `LM` reads `settings.send_stream` for each call.
  * The model can be the model of the context, the own model of a predictor, the model of a scope in the program, or a
  * model that the program calls with `LanguageModel.callAndRecordUsage`.
  */
class StreamingOwnModelSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val answerLayout = SignatureDsl.parse("question -> answer").toOption.get

  /** A streaming model that gives `text` as the value of `answer`, in one chunk. */
  private final class Model(override val id: String, text: String) extends StreamingLanguageModel:
    val calls: AtomicInteger  = AtomicInteger(0)
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "this model must stream"))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      val _ = calls.incrementAndGet()
      Iterator(LmChunk(text = s"[[ ## answer ## ]]\n$text\n\n[[ ## completed ## ]]", finishReason = Some("stop")))

  /** A model with no stream. */
  private final class Plain(text: String) extends LanguageModel:
    override val id: String   = "plain"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## answer ## ]]\n$text\n\n[[ ## completed ## ]]"))))

  private final class Program(name: String)(body: ProgramCall[DynamicValue.Record] => RuntimeContext ?=> Either[
    DspyError,
    DynamicValue.Record
  ]) extends DynamicModule:
    override val moduleName: String                                                                      = name
    override protected def forwardDynamic(input: ProgramCall[DynamicValue.Record])(using RuntimeContext) =
      body(input).map(values => RawPrediction(values))

  private def own(name: String, model: LanguageModel): DynamicPredict =
    DynamicPredict(layout = answerLayout, name = Some(name), lm = Some(model))

  private def tokens(events: Vector[StreamEvent]): Vector[(String, String)] =
    events.collect { case event: TokenEvent => (event.predictName, event.chunk) }

  private def hasPrediction(events: Vector[StreamEvent]): Boolean =
    events.exists(_.isInstanceOf[PredictionEvent])

  private val chat = RuntimeContext(adapter = Some(ChatAdapter()))

  private def input = rec("question" := "q")

  test("a predictor with its own model streams, with no model in the context") {
    given RuntimeContext = chat
    val model            = Model("own", "paris")
    val events           = Streamify.streamify(own("first", model), warningSink = _ => ()).apply(input).toVector
    assertEquals(tokens(events), Vector(("first", "paris")), events.toString)
    assert(hasPrediction(events), events.toString)
    assertEquals(model.calls.get(), 1)
  }

  test("Predict.withLm streams its own model, and the model of the context gets no call") {
    val ambient          = Model("ambient", "rome")
    val model            = Model("own", "paris")
    given RuntimeContext = RuntimeContext(lm = Some(ambient), adapter = Some(ChatAdapter()))
    val program          = Predict(Signature.fromString("question -> answer")).withLm(model)
    val events           = Streamify.streamify(program, warningSink = _ => ()).apply(input).toVector
    assertEquals(tokens(events).map(_._2), Vector("paris"), events.toString)
    assertEquals(ambient.calls.get(), 0)
    assertEquals(model.calls.get(), 1)
  }

  test("predictors with different models each stream under the name of the predictor") {
    val ambient          = Model("ambient", "rome")
    given RuntimeContext = RuntimeContext(lm = Some(ambient), adapter = Some(ChatAdapter()))
    val first            = own("first", Model("a", "paris"))
    val second           = DynamicPredict(layout = answerLayout, name = Some("second"))
    val third            = own("third", Model("c", "lima"))
    val program          = Program("three") { input =>
      for
        _    <- first(input)
        _    <- second(input)
        last <- third(input)
      yield last.raw.values
    }
    val events = Streamify.streamify(program, warningSink = _ => ()).apply(input).toVector
    assertEquals(tokens(events), Vector(("first", "paris"), ("second", "rome"), ("third", "lima")), events.toString)
  }

  test("a listener with no reuse goes silent after one field, also across two models") {
    given RuntimeContext = chat
    val first            = own("first", Model("a", "paris"))
    val second           = own("second", Model("b", "lima"))
    val program          = Program("two") { input =>
      first(input).flatMap(_ => second(input)).map(_.raw.values)
    }
    val events = Streamify
      .streamify(program, streamListeners = Vector(StreamListener("answer")), warningSink = _ => ())
      .apply(input)
      .toVector
    assertEquals(tokens(events), Vector(("first", "paris")), events.toString)
  }

  test("with includeFinalPrediction false, a token of an own model removes the prediction") {
    given RuntimeContext = chat
    val events           = Streamify
      .streamify(
        own("first", Model("own", "paris")),
        streamListeners = Vector(StreamListener("answer")),
        includeFinalPrediction = false,
        warningSink = _ => ()
      )
      .apply(input)
      .toVector
    assertEquals(tokens(events), Vector(("first", "paris")), events.toString)
    assert(!hasPrediction(events), events.toString)
  }

  test("a close stops the stream of an own model") {
    val closed = CountDownLatch(1)
    val given_ = AtomicInteger(0)
    val chunks = new ClosableIterator[LmChunk]:
      override def hasNext: Boolean = given_.get() < 1000
      override def next(): LmChunk  =
        val index = given_.incrementAndGet()
        Thread.sleep(2)
        LmChunk(
          text = if index == 1 then "[[ ## answer ## ]]\n" else s"t$index ",
          finishReason = Option.when(index == 1000)("stop")
        )
      override def close(): Unit = closed.countDown()
    val model = new StreamingLanguageModel:
      override val id: String                                                                    = "slow"
      override val mode: LmMode                                                                  = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        Left(RuntimeError("test", "this model must stream"))
      override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] = chunks

    given RuntimeContext = chat
    val stream           = Streamify.streamify(own("first", model), warningSink = _ => ()).apply(input)
    assert(stream.next().isInstanceOf[TokenEvent])
    stream.close()
    assert(closed.await(5, TimeUnit.SECONDS), "the stream of the own model is open after the close")
    assert(given_.get() < 1000, s"the own model gave ${given_.get()} chunks")
  }

  test("an own model with no stream gives the prediction and no token") {
    given RuntimeContext = chat
    val events           = Streamify.streamify(own("first", Plain("paris")), warningSink = _ => ()).apply(input).toVector
    assertEquals(tokens(events), Vector.empty, events.toString)
    assert(hasPrediction(events), events.toString)
  }

  test("the model of a scope in the program streams") {
    val ambient          = Model("ambient", "rome")
    val scoped           = Model("scoped", "paris")
    given RuntimeContext = RuntimeContext(lm = Some(ambient), adapter = Some(ChatAdapter()))
    val inner            = DynamicPredict(layout = answerLayout, name = Some("inner"))
    val program          = Program("scope") { input =>
      RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(scoped))) {
        given RuntimeContext = RuntimeEnvironment.current
        inner(input).map(_.raw.values)
      }
    }
    val events = Streamify.streamify(program, warningSink = _ => ()).apply(input).toVector
    assertEquals(tokens(events), Vector(("inner", "paris")), events.toString)
    assertEquals(ambient.calls.get(), 0)
  }

  test("a model that the program calls with callAndRecordUsage streams") {
    given RuntimeContext = chat
    val other            = Model("other", "paris")
    val program          = Program("direct") { _ =>
      LanguageModel
        .callAndRecordUsage(other, LmRequest(model = other.id))
        .map(response => rec("answer" := response.outputs.head.text))
    }
    val events = Streamify.streamify(program, warningSink = _ => ()).apply(input).toVector
    assert(tokens(events).map(_._2).mkString.contains("paris"), events.toString)
    assertEquals(other.calls.get(), 1)
  }

  test("a predictor with decision outputs and an own model fails before the request") {
    val layout: SignatureLayout = SignatureLayout
      .create(
        name = "Triage",
        inputFields = Vector(FieldSpec("ticket")),
        outputFields = Vector(FieldSpec("urgent", TypeRef.noul, description = Some("Is immediate action needed?")))
      )
      .fold(error => fail(error.message), identity)
    val model            = Model("own", "unused")
    given RuntimeContext = chat
    val program          = DynamicPredict(layout = layout, name = Some("triage"), lm = Some(model))
    val events           = Streamify.streamify(program, warningSink = _ => ()).apply(rec("ticket" := "t")).toVector
    val errors           = events.collect { case ErrorEvent(error, _) => error.message }
    assertEquals(errors, Vector("Streaming decision evidence is not supported."), events.toString)
    assertEquals(model.calls.get(), 0)
  }

  test("a nested streamify gives its tokens to the inner stream only") {
    val ambient          = Model("ambient", "paris")
    given RuntimeContext = RuntimeContext(lm = Some(ambient), adapter = Some(ChatAdapter()))
    val inner            = DynamicPredict(layout = answerLayout, name = Some("inner"))
    val innerTokens      = AtomicInteger(0)
    val program          = Program("outer") { input =>
      val events = Streamify.streamify(inner, warningSink = _ => ()).apply(input.input).toVector
      innerTokens.set(tokens(events).size)
      Right(rec("answer" := "done"))
    }
    val events = Streamify.streamify(program, warningSink = _ => ()).apply(input).toVector
    assertEquals(innerTokens.get(), 1)
    assertEquals(tokens(events), Vector.empty, events.toString)
  }
