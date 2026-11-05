package dspy4s.streaming

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{
  ClosableIterator,
  DspyError,
  LmStreamAssemblyError,
  RuntimeContext,
  RuntimeError,
  :=
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.{LmChunk, LmMode, LmOutput, LmRequest, LmResponse, StreamingLanguageModel}
import dspy4s.programs.contracts.{DynamicModule, ProgramCall}
import dspy4s.programs.strategies.{DynamicPredict, RLM}
import dspy4s.signatures.Signature
import dspy4s.streaming.contracts.{ErrorEvent, PredictionEvent, StreamEvent, StreamListener, TokenEvent}
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** The rules of upstream `dspy/streaming/` that the port did not follow. */
class StreamingUpstreamRulesSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val answerLayout = SignatureDsl.parse("question -> answer").toOption.get

  private val answerChunk: LmChunk =
    LmChunk(text = "[[ ## answer ## ]]\nparis\n\n[[ ## completed ## ]]", finishReason = Some("stop"))

  private final class Stream(chunks: () => Iterator[LmChunk]) extends StreamingLanguageModel:
    val calls: AtomicInteger  = AtomicInteger(0)
    override val id: String   = "stream"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "the non-stream path is not used"))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      val _ = calls.incrementAndGet()
      chunks()

  private def eventsOf(
      lm                    : StreamingLanguageModel,
      listeners             : Vector[StreamListener],
      includeFinalPrediction: Boolean
  ): Vector[StreamEvent] =
    given RuntimeContext = RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))
    val program          = DynamicPredict(layout = answerLayout)
    Streamify
      .streamify(
        program,
        streamListeners = listeners,
        includeFinalPrediction = includeFinalPrediction,
        warningSink = _ => ()
      )
      .apply(rec("question" := "q"))
      .toVector

  private def hasPrediction(events: Vector[StreamEvent]): Boolean =
    events.exists(_.isInstanceOf[PredictionEvent])

  // ── include_final_prediction_in_output_stream (`streamify.py`, lines 242-249) ─────────────────────────────────────

  test("with includeFinalPrediction false and no listeners, the stream still has the prediction") {
    val events = eventsOf(new Stream(() => Iterator(answerChunk)), Vector.empty, includeFinalPrediction = false)
    assert(hasPrediction(events), events.toString)
  }

  test("with includeFinalPrediction false, a listener that gets tokens removes the prediction") {
    val events = eventsOf(
      new Stream(() => Iterator(answerChunk)),
      Vector(StreamListener("answer")),
      includeFinalPrediction = false
    )
    assert(events.exists(_.isInstanceOf[TokenEvent]), events.toString)
    assert(!hasPrediction(events), events.toString)
  }

  test("with includeFinalPrediction false and a cache hit, the stream still has the prediction") {
    val cached = LmResponse(outputs = Vector(LmOutput(text = answerChunk.text)), cacheHit = true)
    val events = eventsOf(
      new Stream(() => Iterator(LmChunk.cached(cached))),
      Vector(StreamListener("answer")),
      includeFinalPrediction = false
    )
    assert(hasPrediction(events), events.toString)
  }

  test("with includeFinalPrediction false and no tokens for a listener, the stream still has the prediction") {
    val events = eventsOf(
      new Stream(() => Iterator(answerChunk)),
      Vector(StreamListener("answer", predictName = Some("other"))),
      includeFinalPrediction = false
    )
    assert(hasPrediction(events), events.toString)
  }

  // ── StreamListener (`streaming_listener.py`) ──────────────────────────────────────────────────────────────────────

  test("a listener does not fire again by default, as upstream allow_reuse=False") {
    assertEquals(StreamListener("answer").allowReuse, false)
  }

  test("a field in two predictors with no predictor name gives a warning, where upstream raises") {
    given RuntimeContext = RuntimeContext()
    val warnings         = ArrayBuffer.empty[String]
    val program          = RLM(baseSignature = Signature.fromString("q -> reasoning"))
    val _                = Streamify.streamify(
      program,
      streamListeners = Vector(StreamListener("reasoning")),
      warningSink = warnings.append
    )
    assert(warnings.exists(_.contains("is not unique in the program")), warnings.toString)
  }

  // ── A stream with no finish reason (`ManagedLanguageModel`, stream guard) ─────────────────────────────────────────

  private def cutStream = new Stream(() => Iterator(LmChunk(text = "par")))

  test("a call over a stream with no finish reason is a Left, not a partial answer") {
    given RuntimeContext = RuntimeContext()
    val wrapper          = StreamingLanguageModelWrapper(cutStream, StreamingQueue[StreamEvent]())
    val result           = wrapper.call(LmRequest(model = "stream"))
    assert(result.left.exists(_.isInstanceOf[LmStreamAssemblyError]), result.toString)
  }

  test("a stream with no finish reason ends with a failure chunk") {
    given RuntimeContext = RuntimeContext()
    val wrapper          = StreamingLanguageModelWrapper(cutStream, StreamingQueue[StreamEvent]())
    val chunks           = wrapper.stream(LmRequest(model = "stream")).toVector
    assertEquals(chunks.map(_.text).mkString, "par")
    assert(chunks.last.reportedFailure.exists(_.isInstanceOf[LmStreamAssemblyError]), chunks.toString)
  }

  // ── A consumer that stops (upstream cancels the task group of `_stream_program`) ──────────────────────────────────

  /** A long stream that records how many chunks it gave and its close. */
  private final class Slow extends ClosableIterator[LmChunk]:
    val given_ : AtomicInteger = AtomicInteger(0)
    val closed: CountDownLatch = CountDownLatch(1)

    override def hasNext: Boolean = given_.get() < 1000
    override def next(): LmChunk  =
      val index = given_.incrementAndGet()
      Thread.sleep(2)
      LmChunk(
        text = if index == 1 then "[[ ## answer ## ]]\n" else s"t$index ",
        finishReason = Option.when(index == 1000)("stop")
      )
    override def close(): Unit = closed.countDown()

  test("a close of the stream stops the model stream and the program") {
    val slow    = new Slow
    val lm      = new Stream(() => slow)
    val program = new DynamicModule:
      override val moduleName: String                                                                      = "two_steps"
      private val first                                                                                    = DynamicPredict(layout = answerLayout, name = Some("first"))
      private val second                                                                                   = DynamicPredict(layout = answerLayout, name = Some("second"))
      override protected def forwardDynamic(input: ProgramCall[DynamicValue.Record])(using RuntimeContext) =
        first(input).flatMap(_ => second(input)).map(_.raw)

    given RuntimeContext = RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))
    val stream           = Streamify.streamify(program, warningSink = _ => ()).apply(rec("question" := "q"))
    assert(stream.next().isInstanceOf[TokenEvent])
    stream.close()

    assert(slow.closed.await(5, TimeUnit.SECONDS), "the model stream is open after the close of the consumer")
    assert(slow.given_.get() < 1000, s"the model stream gave ${slow.given_.get()} chunks after the close")
    Thread.sleep(100)
    assertEquals(lm.calls.get(), 1, "the program made another model call after the close")
  }

  test("a close of the stream interrupts a program that waits, and the program gets no later step") {
    val waiting     = CountDownLatch(1)
    val interrupted = CountDownLatch(1)
    val program     = new DynamicModule:
      override val moduleName: String                                                                      = "waits"
      override protected def forwardDynamic(input: ProgramCall[DynamicValue.Record])(using RuntimeContext) =
        waiting.countDown()
        try Thread.sleep(60000)
        catch
          case error: InterruptedException =>
            interrupted.countDown()
            throw error
        Left(RuntimeError("test", "the wait ended with no interrupt"))

    given RuntimeContext = RuntimeContext()
    val stream           = Streamify.streamify(program, warningSink = _ => ()).apply(rec("question" := "q"))
    assert(waiting.await(5, TimeUnit.SECONDS))
    stream.close()
    assert(interrupted.await(5, TimeUnit.SECONDS), "the program still waits after the close of the consumer")
    assertEquals(stream.hasNext, false)
  }

  test("an interrupt that does not come from the consumer gives an error event") {
    val program = new DynamicModule:
      override val moduleName: String                                                                      = "interrupted"
      override protected def forwardDynamic(input: ProgramCall[DynamicValue.Record])(using RuntimeContext) =
        throw new InterruptedException("stop")

    given RuntimeContext = RuntimeContext()
    val events           = Streamify.streamify(program, warningSink = _ => ()).apply(rec("question" := "q")).toVector
    assert(events.exists(_.isInstanceOf[ErrorEvent]), events.toString)
  }
