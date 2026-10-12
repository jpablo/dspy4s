package dspy4s.streaming

import dspy4s.adapters.JSONAdapter
import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
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

import java.util.concurrent.atomic.AtomicReference

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

  test("another failure of a streamed call keeps its component") {
    val lm = OpenAiLanguageModel(
      model = "gpt-test",
      client = OpenAiClient(apiKey = "test-key", transport = rejectingTransport(429, """{"error":"rate limit"}"""))
    )
    val wrapper = StreamingLanguageModelWrapper(lm, StreamingQueue[StreamEvent]())

    given RuntimeContext = RuntimeEnvironment.current
    wrapper.call(request) match
      case Left(RuntimeError(component, _)) => assertEquals(component, "openai_rate_limit")
      case other                            => fail(s"expected the provider RuntimeError, got $other")
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
        Left(ContextWindowExceededError(model = Some("gpt-test")))

    // `ManagedLanguageModel.stream` reports the failure of the `call` fallback as a chunk.
    val wrapper = StreamingLanguageModelWrapper(ManagedLanguageModel(delegate = failing), StreamingQueue[StreamEvent]())

    given RuntimeContext = RuntimeEnvironment.current
    assertEquals(wrapper.call(request), Left(ContextWindowExceededError(model = Some("gpt-test"))))
  }
