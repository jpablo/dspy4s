package dspy4s.streaming

import dspy4s.adapters.JSONAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
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
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.TokenEvent
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

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
    override def parse(layout: SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      Left(ParseError("failing", "the format is wrong", raw = Some(output.text)))

  private val program: DynamicPredict = DynamicPredict(layout = SignatureDsl.parse("question -> answer").toOption.get)

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
