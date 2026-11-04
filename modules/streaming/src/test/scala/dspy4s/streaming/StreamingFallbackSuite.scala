package dspy4s.streaming

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.JSONAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.ClosableIterator
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
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
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.strategies.{DynamicPredict, Predict}
import dspy4s.signatures.Signature
import dspy4s.streaming.contracts.PredictionEvent
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.StreamListener
import dspy4s.streaming.contracts.TokenEvent
import munit.FunSuite
import zio.blocks.schema.Schema

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

// Top-level fixtures (Mirror derivation needs them outside the suite).
final case class FallbackQuestion(question: String) derives Schema
final case class FallbackTags(tags: List[String]) derives Schema

/** A call that streamed visible output gets no fallback adapter (upstream `dspy/adapters/chat_adapter.py` `__call__`:
  * "never after visible stream output").
  */
class StreamingFallbackSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Each call and each stream gives the same text, and counts as one call. */
  private final class CountingStreamingLm extends StreamingLanguageModel:
    val calls                                                                                  = AtomicInteger(0)
    private val text                                                                           = "I think the answer is Paris."
    override val id: String                                                                    = "counting-stream"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = calls.incrementAndGet()
      Right(LmResponse(outputs = Vector(LmOutput(text = text))))
    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      val _ = calls.incrementAndGet()
      Iterator(LmChunk(text = "I think "), LmChunk(text = "the answer is Paris.", finishReason = Some("stop")))

  /** An adapter whose parse always fails, with a JSON fallback. It has no streaming state, so each token is visible. */
  private object FailingAdapter extends Adapter:
    override val name: String                                                                                    = "failing"
    override def fallback: Option[Adapter]                                                                       = Some(JSONAdapter())
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("x")))))
    override def parse(invocation: AdapterInvocation, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      Left(ParseError("failing", "the format is wrong", raw = Some(output.text)))

  /** An adapter whose parse gives a value that a list output rejects, with a JSON fallback and no streaming state. */
  private object WrongValueAdapter extends Adapter:
    override val name: String                                                                                    = "wrong-value"
    override def fallback: Option[Adapter]                                                                       = Some(JSONAdapter())
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("x")))))
    override def parse(invocation: AdapterInvocation, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(DynamicValues.record("tags" := 1)))

  private val program: DynamicPredict = DynamicPredict(layout = SignatureDsl.parse("question -> answer").toOption.get)

  private val typedProgram = Predict(Signature.derived[FallbackQuestion, FallbackTags](name = "Tags"))

  test("a call with no stream gets the fallback") {
    val lm = new CountingStreamingLm
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(FailingAdapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      // The JSON fallback reads the plain text as the single output.
      val result = program(ProgramCall(DynamicValues.record("question" := "Capital of France?")))
      assert(result.isRight, result.toString)
    }
    assertEquals(lm.calls.get(), 2)
  }

  test("a call that streamed visible tokens gets no fallback") {
    val lm = new CountingStreamingLm
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(FailingAdapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream           = Streamify.streamify(program)(DynamicValues.record("question" := "Capital of France?"))
      val events           = ArrayBuffer.empty[StreamEvent]
      while stream.hasNext do events += stream.next()
      assert(events.exists(_.isInstanceOf[TokenEvent]), events.toString)
    }
    assertEquals(lm.calls.get(), 1)
  }

  test("a typed call with no stream gets the fallback when the typed decode rejects the reply") {
    val lm = new CountingStreamingLm
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(WrongValueAdapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      // The plain text of the second reply is no JSON list, so the JSON fallback also gives a parse error.
      val result = typedProgram(FallbackQuestion("Capital of France?"))
      assert(result.left.exists(_.isInstanceOf[ParseError]), result.toString)
    }
    assertEquals(lm.calls.get(), 2)
  }

  test("a typed call that streamed visible tokens gets no fallback when the typed decode rejects the reply") {
    val lm = new CountingStreamingLm
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(WrongValueAdapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream           = Streamify.streamify(typedProgram)(DynamicValues.record("question" := "Capital of France?"))
      val events           = ArrayBuffer.empty[StreamEvent]
      while stream.hasNext do events += stream.next()
      assert(events.exists(_.isInstanceOf[TokenEvent]), events.toString)
    }
    assertEquals(lm.calls.get(), 1)
  }

  /** Each stream gives the chunks of the next entry of `attempts`. */
  private final class ScriptedStreamLm(attempts: Vector[LmChunk]*) extends StreamingLanguageModel:
    val calls                                                                                  = AtomicInteger(0)
    override val id: String                                                                    = "scripted-stream"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(ParseError("test", "the plain path is not used"))
    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      attempts(calls.getAndIncrement()).iterator

  private def stop: LmChunk = LmChunk(finishReason = Some("stop"))

  private def jsonAnswer: Vector[LmChunk] =
    Vector(LmChunk(text = """{"answer": "Paris"}""", finishReason = Some("stop")))

  /** The events of a `streamify` call of the program with `lm` and a [[ChatAdapter]], which has a stream state. */
  private def streamed(
      lm       : StreamingLanguageModel,
      listeners: Vector[StreamListener] = Vector.empty
  ): Vector[StreamEvent] =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream           = Streamify.streamify(program, streamListeners = listeners)(DynamicValues.record("question" :=
        "Capital of France?"))
      val events = ArrayBuffer.empty[StreamEvent]
      while stream.hasNext do events += stream.next()
      events.toVector
    }

  test("a stream with no text and no tool call gives no token to the first attempt, and it gets the fallback") {
    val lm     = ScriptedStreamLm(Vector(stop), jsonAnswer)
    val events = streamed(lm)
    assertEquals(lm.calls.get(), 2)
    assert(events.collect { case token: TokenEvent => token.chunk }.mkString.contains("Paris"), events.toString)
  }

  test("a stream with only reasoning gets the fallback") {
    val lm = ScriptedStreamLm(Vector(LmChunk(reasoning = "why"), stop), jsonAnswer)
    val _  = streamed(lm)
    assertEquals(lm.calls.get(), 2)
  }

  /** Each stream request gets the lines of the next entry of `attempts`. */
  private final class ScriptedSse(attempts: Vector[String]*) extends HttpTransport:
    val streams                                                                                                        = AtomicInteger(0)
    override def sendJson(url: String, headers: Map[String, String], payload: String): Either[DspyError, HttpResponse] =
      Left(ParseError("test", "the plain path is not used"))
    override def streamSse(
        url    : String,
        headers: Map[String, String],
        payload: String
    ): Either[DspyError, HttpStreamResponse] =
      val source = attempts(streams.getAndIncrement()).iterator
      Right(HttpStreamResponse(
        status = 200,
        headers = Map.empty,
        dataLines = new ClosableIterator[String]:
          override def hasNext: Boolean = source.hasNext
          override def next(): String   = source.next()
          override def close(): Unit    = ()
      ))

  /** A first stream with only reasoning, in the two keys of the chat wire, then a stream with a JSON answer. */
  private def reasoningThenAnswer: ScriptedSse =
    ScriptedSse(
      Vector(
        """data: {"choices":[{"delta":{"reasoning":"why "}}]}""",
        """data: {"choices":[{"delta":{"reasoning_content":"so"}}]}""",
        """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        "data: [DONE]"
      ),
      Vector(
        """data: {"choices":[{"delta":{"content":"{\"answer\": \"Paris\"}"},"finish_reason":"stop"}]}""",
        "data: [DONE]"
      )
    )

  private def chatModel(transport: HttpTransport): ManagedLanguageModel =
    ManagedLanguageModel(delegate =
      OpenAiLanguageModel(
        model = "m",
        client = OpenAiClient(apiKey = "x", baseUrl = "https://api.test/v1", transport = transport)
      )
    )

  private def tokens(events: Vector[StreamEvent]): String =
    events.collect { case token: TokenEvent => token.chunk }.mkString

  test("a chat stream of the wire with only reasoning gets the fallback, and the reasoning gives no token") {
    val transport = reasoningThenAnswer
    val events    = streamed(chatModel(transport))
    assertEquals(transport.streams.get(), 2)
    assert(tokens(events).contains("Paris"), events.toString)
    assert(!tokens(events).contains("why"), events.toString)
    assert(events.exists(_.isInstanceOf[PredictionEvent]), events.toString)
  }

  test("a chat stream of the wire with only reasoning gets the fallback with a listener of the output field") {
    val transport = reasoningThenAnswer
    val events    = streamed(chatModel(transport), Vector(StreamListener(signatureFieldName = "answer")))
    assertEquals(transport.streams.get(), 2)
    assert(!tokens(events).contains("why"), events.toString)
    assert(events.exists(_.isInstanceOf[PredictionEvent]), events.toString)
  }

  test("a stream with only white space gives no token through a stream state, and it gets the fallback") {
    val lm = ScriptedStreamLm(Vector(LmChunk(text = "  \n "), stop), jsonAnswer)
    val _  = streamed(lm)
    assertEquals(lm.calls.get(), 2)
  }
