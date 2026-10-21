package dspy4s.streaming

import dspy4s.adapters.JSONAdapter
import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LmErrorDetails
import dspy4s.core.contracts.LmRateLimitError
import dspy4s.core.contracts.LmUnexpectedError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.lm.providers.HttpResponse
import dspy4s.lm.providers.HttpStreamResponse
import dspy4s.lm.providers.HttpTransport
import dspy4s.lm.providers.OpenAiClient
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import dspy4s.streaming.contracts.StreamEvent
import munit.FunSuite
import zio.blocks.schema.Schema

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import scala.util.Success
import scala.util.Try

private final case class ContractInput(question: String) derives Schema
private final case class ContractOutput(answer: String) derives Schema

/** The stream wrapper replaces the active language model during `streamify`. It must not change what the adapters and
  * the programs see: the capability flags of the delegate, and the type of each failure (PORT_GAPS G-25).
  */
class StreamingWrapperContractSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class CapableLm extends StreamingLanguageModel:
    override val id: String                       = "capable"
    override val mode: LmMode                     = LmMode.Chat
    override val supportsFunctionCalling: Boolean = true
    override val supportsResponseSchema: Boolean  = true
    override val supportsReasoning: Boolean       = true

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = "ok"))))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      Iterator.single(LmChunk(text = "ok", finishReason = Some("stop")))

  /** A model that supports a response schema and records each request that it streams. */
  private final class RecordingSchemaLm extends StreamingLanguageModel:
    val requests: AtomicReference[Vector[LmRequest]] = AtomicReference(Vector.empty)
    override val id: String                          = "recording"
    override val mode: LmMode                        = LmMode.Chat
    override val supportsResponseSchema: Boolean     = true

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "the non-stream path is not used"))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      val _ = requests.updateAndGet(_ :+ request)
      Iterator.single(LmChunk(text = """{"answer": "42"}""", finishReason = Some("stop")))

  /** A stream model that replays the given chunks. */
  private final class ScriptedLm(chunks: Vector[LmChunk]) extends StreamingLanguageModel:
    override val id: String   = "scripted"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "the non-stream path is not used"))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] = chunks.iterator

  /** A stream model whose `stream` runs `open`. */
  private final class Opening(open: () => Iterator[LmChunk]) extends StreamingLanguageModel:
    override val id: String   = "opening"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("test", "the non-stream path is not used"))

    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] = open()

  /** A stream that gives one chunk, then throws `failure` at the next read. It records its close. */
  private final class Breaking(failure: () => Throwable) extends ClosableIterator[LmChunk]:
    @volatile var closed          = false
    private var sent              = false
    override def hasNext: Boolean = true
    override def next(): LmChunk  =
      if sent then throw failure()
      sent = true
      LmChunk(text = "Hi")
    override def close(): Unit = closed = true

  /** A stream that gives one chunk, then throws `failure` at the next `hasNext`. It records its close. */
  private final class BreakingCheck(failure: () => Throwable) extends ClosableIterator[LmChunk]:
    @volatile var closed          = false
    private var sent              = false
    override def hasNext: Boolean = if sent then throw failure() else true
    override def next(): LmChunk  =
      sent = true
      LmChunk(text = "Hi")
    override def close(): Unit = closed = true

  /** The joined text of the chunks before the failure chunk at the end of `chunks`, and the error of that chunk. */
  private def textAndFailure(chunks: Try[Vector[LmChunk]]): (String, Option[DspyError]) =
    chunks match
      case Success(items) if items.lastOption.exists(_.reportedFailure.isDefined) =>
        (items.init.map(_.text).mkString, items.lastOption.flatMap(_.reportedFailure))
      case other => fail(s"expected the chunks of the stream and a failure chunk at the end, got $other")

  private final class Lines(items: Vector[String]) extends ClosableIterator[String]:
    private var index             = 0
    override def hasNext: Boolean = index < items.size
    override def next(): String   =
      val item = items(index)
      index += 1
      item
    override def close(): Unit = ()

  /** A transport that rejects each stream request with the given status and body. */
  private def rejectingTransport(status: Int, body: String): HttpTransport =
    new HttpTransport:
      override def sendJson(
          url    : String,
          headers: Map[String, String],
          payload: String
      ): Either[DspyError, HttpResponse] =
        Left(RuntimeError("test", "the non-stream path is not used"))

      override def streamSse(
          url    : String,
          headers: Map[String, String],
          payload: String
      ): Either[DspyError, HttpStreamResponse] =
        Right(HttpStreamResponse(status, Map.empty, new Lines(Vector(body))))

  private val request = LmRequest(
    model = "gpt-test",
    messages = Vector(Message(role = MessageRole.User, text = Some("hello")))
  )

  test("the stream wrapper forwards the capability flags of its delegate") {
    val wrapper = StreamingLanguageModelWrapper(new CapableLm, StreamingQueue[StreamEvent]())

    assert(wrapper.supportsFunctionCalling, "supportsFunctionCalling")
    assert(wrapper.supportsResponseSchema, "supportsResponseSchema")
    assert(wrapper.supportsReasoning, "supportsReasoning")
  }

  test("the stream wrapper does not close its delegate, because it does not own the model of the configuration") {
    val closes = AtomicInteger(0)
    val lm     = new StreamingLanguageModel:
      override val id: String                                                                    = "owned-elsewhere"
      override val mode: LmMode                                                                  = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        Right(LmResponse(outputs = Vector(LmOutput(text = "ok"))))
      override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
        Iterator.single(LmChunk(text = "ok", finishReason = Some("stop")))
      override def close(): Unit =
        val _ = closes.incrementAndGet()

    StreamingLanguageModelWrapper(lm, StreamingQueue[StreamEvent]()).close()

    assertEquals(closes.get(), 0)
  }

  test("under streamify, the JSON adapter still sends response_format to a model that supports it") {
    val lm      = new RecordingSchemaLm
    val program = Predict(Signature.derived[ContractInput, ContractOutput]("Contract"))

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(JSONAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream           = Streamify.streamify(program)(rec("question" := "x"))
      while stream.hasNext do
        val _ = stream.next()
    }

    val requests = lm.requests.get()
    assertEquals(requests.size, 1)
    val options = requests.head.options
    assert(DynamicValues.recordGet(options, "response_format").isDefined, s"no response_format in: $options")
  }

  test("a context-window failure of a streamed call keeps its type") {
    val body =
      """{"error":{"message":"This model's maximum context length is 8192 tokens.","code":"context_length_exceeded"}}"""
    val lm = OpenAiLanguageModel(
      model = "gpt-test",
      client = OpenAiClient(apiKey = "test-key", transport = rejectingTransport(400, body))
    )
    val wrapper = StreamingLanguageModelWrapper(lm, StreamingQueue[StreamEvent]())

    given RuntimeContext = RuntimeEnvironment.current
    wrapper.call(request) match
      case Left(_: ContextWindowExceededError) => ()
      case other                               => fail(s"expected a ContextWindowExceededError, got $other")
  }

  test("another failure of a streamed call keeps its class") {
    val lm = OpenAiLanguageModel(
      model = "gpt-test",
      client = OpenAiClient(apiKey = "test-key", transport = rejectingTransport(429, """{"error":"rate limit"}"""))
    )
    val wrapper = StreamingLanguageModelWrapper(lm, StreamingQueue[StreamEvent]())

    given RuntimeContext = RuntimeEnvironment.current
    wrapper.call(request) match
      case Left(error: LmRateLimitError) => assertEquals(error.details.status, Some(429))
      case other                         => fail(s"expected LmRateLimitError, got $other")
  }

  test("the stream wrapper forwards the default options of its delegate") {
    val defaults = DynamicValues.record("temperature" := 0.0)
    val lm       = OpenAiLanguageModel(
      model = "gpt-test",
      client = OpenAiClient(apiKey = "test-key", transport = rejectingTransport(500, "")),
      defaultOptions = defaults
    )
    val wrapper = StreamingLanguageModelWrapper(lm, StreamingQueue[StreamEvent]())

    assertEquals(wrapper.defaultOptions, defaults)
  }

  test("a failure chunk without a typed error still gives the streaming_lm error") {
    val lm      = new ScriptedLm(Vector(LmChunk(finishReason = Some("error"), raw = Some(Map("error" -> "boom")))))
    val wrapper = StreamingLanguageModelWrapper(lm, StreamingQueue[StreamEvent]())

    given RuntimeContext = RuntimeEnvironment.current
    assertEquals(wrapper.call(request), Left(RuntimeError("streaming_lm", "boom")))
  }

  test("a failure of a non-stream model behind ManagedLanguageModel keeps its type") {
    val failing = new LanguageModel:
      override val id: String   = "gpt-test"
      override val mode: LmMode = LmMode.Chat

      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        Left(ContextWindowExceededError(details = LmErrorDetails(model = Some("gpt-test"))))

    // `ManagedLanguageModel.stream` reports the failure of the `call` fallback as a chunk.
    val wrapper = StreamingLanguageModelWrapper(ManagedLanguageModel(delegate = failing), StreamingQueue[StreamEvent]())

    given RuntimeContext = RuntimeEnvironment.current
    assertEquals(
      wrapper.call(request),
      Left(ContextWindowExceededError(details = LmErrorDetails(model = Some("gpt-test"))))
    )
  }

  // ── A delegate that throws (the rule of ManagedLanguageModel, upstream `error_boundary(..., unexpected=True)`) ──

  test("a delegate stream that throws during a read gives an LmUnexpectedError, and the stream closes") {
    val source  = Breaking(() => IllegalStateException("the stream broke"))
    val wrapper = StreamingLanguageModelWrapper(Opening(() => source), StreamingQueue[StreamEvent]())

    given RuntimeContext = RuntimeEnvironment.current
    wrapper.call(request) match
      case Left(error: LmUnexpectedError) =>
        assertEquals(error.message, "the stream broke")
        assertEquals(error.details.model, Some("gpt-test"))
      case other => fail(s"expected an LmUnexpectedError, got $other")
    assert(source.closed, "the wrapper must close the stream of the delegate")
  }

  test("a delegate that throws when it opens the stream gives an LmUnexpectedError") {
    val wrapper = StreamingLanguageModelWrapper(
      Opening(() => throw IllegalStateException("no stream")),
      StreamingQueue[StreamEvent]()
    )

    given RuntimeContext = RuntimeEnvironment.current
    Try(wrapper.call(request)) match
      case Success(Left(error: LmUnexpectedError)) =>
        assertEquals(error.message, "no stream")
        assertEquals(error.details.model, Some("gpt-test"))
      case other => fail(s"expected an LmUnexpectedError, got $other")
  }

  test("a close of the delegate stream that throws does not change the result, as in ManagedLanguageModel") {
    val source = new ClosableIterator[LmChunk]:
      private var sent              = false
      override def hasNext: Boolean = !sent
      override def next(): LmChunk  =
        sent = true
        LmChunk(text = "ok", finishReason = Some("stop"))
      override def close(): Unit = throw IllegalStateException("the close failed")
    val wrapper = StreamingLanguageModelWrapper(Opening(() => source), StreamingQueue[StreamEvent]())

    given RuntimeContext = RuntimeEnvironment.current
    assertEquals(Try(wrapper.call(request)).map(_.map(_.outputs.map(_.text))), Success(Right(Vector("ok"))))
  }

  test("a fatal error of the delegate stream goes up to the caller, and the stream closes") {
    val source  = Breaking(() => InterruptedException("stop"))
    val wrapper = StreamingLanguageModelWrapper(Opening(() => source), StreamingQueue[StreamEvent]())

    given RuntimeContext = RuntimeEnvironment.current
    // `intercept` catches only non-fatal errors, so the test catches the fatal error itself.
    val thrown =
      try
        val _ = wrapper.call(request)
        None
      catch case error: InterruptedException => Some(error.getMessage)
    assertEquals(thrown, Some("stop"))
    assert(source.closed, "the wrapper must close the stream of the delegate")
  }

  // ── The same rule on the stream path: the failure is a failure chunk, the last chunk of the stream ──

  test("on the stream path, a delegate stream that throws during a read gives a failure chunk, and the stream closes") {
    val reads  = Breaking(() => IllegalStateException("the stream broke"))
    val checks = BreakingCheck(() => IllegalStateException("the check broke"))
    Vector(reads -> "the stream broke", checks -> "the check broke").foreach { (source, message) =>
      val wrapper          = StreamingLanguageModelWrapper(Opening(() => source), StreamingQueue[StreamEvent]())
      given RuntimeContext = RuntimeEnvironment.current
      textAndFailure(Try(wrapper.stream(request).toVector)) match
        case ("Hi", Some(error: LmUnexpectedError)) =>
          assertEquals(error.message, message)
          assertEquals(error.details.model, Some("gpt-test"))
        case other => fail(s"expected the chunk Hi and an LmUnexpectedError, got $other")
    }
    assert(reads.closed, "the wrapper must close the stream of the delegate after a read that throws")
    assert(checks.closed, "the wrapper must close the stream of the delegate after a check that throws")
  }

  test("on the stream path, a delegate that throws when it opens the stream gives one failure chunk") {
    val wrapper = StreamingLanguageModelWrapper(
      Opening(() => throw IllegalStateException("no stream")),
      StreamingQueue[StreamEvent]()
    )

    given RuntimeContext = RuntimeEnvironment.current
    textAndFailure(Try(wrapper.stream(request).toVector)) match
      case ("", Some(error: LmUnexpectedError)) =>
        assertEquals(error.message, "no stream")
        assertEquals(error.details.model, Some("gpt-test"))
      case other => fail(s"expected only an LmUnexpectedError, got $other")
  }

  test("on the stream path, a fatal error of the delegate stream goes up to the caller") {
    val source  = Breaking(() => InterruptedException("stop"))
    val wrapper = StreamingLanguageModelWrapper(Opening(() => source), StreamingQueue[StreamEvent]())

    given RuntimeContext = RuntimeEnvironment.current
    // `intercept` catches only non-fatal errors, so the test catches the fatal error itself.
    val thrown =
      try
        val _ = wrapper.stream(request).toVector
        None
      catch case error: InterruptedException => Some(error.getMessage)
    assertEquals(thrown, Some("stop"))
  }

  test("on the stream path, a next after the end of the stream throws NoSuchElementException, as for each iterator") {
    val wrapper = StreamingLanguageModelWrapper(
      ScriptedLm(Vector(LmChunk(text = "ok", finishReason = Some("stop")))),
      StreamingQueue[StreamEvent]()
    )

    given RuntimeContext = RuntimeEnvironment.current
    val stream           = wrapper.stream(request)
    assertEquals(stream.next().text, "ok")
    val _ = intercept[NoSuchElementException](stream.next())
  }
