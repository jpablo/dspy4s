package dspy4s.streaming

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ModuleStartEvent
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
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.streaming.contracts.PredictionEvent
import dspy4s.streaming.contracts.StreamEvent
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

/** `Streamify.streamify` runs the program with the context that it gets (UPGRADE-3.2.1-to-3.4.0, decision D8; PORT_GAPS
  * G-50). In this test, the installed context has no language model, no adapter, and no callback.
  */
class StreamifyGivenContextSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class OneChunkLm(text: String) extends StreamingLanguageModel:
    override val id: String                                                                    = "one-chunk"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = text))))
    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      Iterator(LmChunk(text = text, finishReason = Some("stop")))

  /** Gives the text of the model as the value of each output field. */
  private object PassthroughAdapter extends Adapter:
    override val name: String                                                                                    = "passthrough"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("x")))))
    override def parse(signature: SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = rec(signature.outputFields.map(_.name := output.text)*)))

  private final class Recorder extends CallbackHandler:
    private val buffer                                                     = ArrayBuffer.empty[CallbackEvent]
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
      val _ = buffer.synchronized(buffer += event)
    def events: Vector[CallbackEvent] = buffer.synchronized(buffer.toVector)

  test("the stream runs the program with the adapter and the callbacks of the given context") {
    val recorder         = Recorder()
    given RuntimeContext =
      RuntimeContext(
        lm = Some(OneChunkLm("Paris")),
        adapter = Some(PassthroughAdapter),
        callbacks = Vector(recorder)
      )
    val program = DynamicPredict(layout = SignatureDsl.parse("question -> answer").toOption.get)

    val stream = Streamify.streamify(program).apply(rec("question" := "Capital of France?"))
    val events = ArrayBuffer.empty[StreamEvent]
    while stream.hasNext do events += stream.next()

    val answers = events.collect { case event: PredictionEvent => lookupString(event.prediction.values, "answer") }
    assertEquals(answers.toVector, Vector("Paris"))
    assertEquals(recorder.events.collect { case start: ModuleStartEvent => start.moduleName }, Vector("predict"))
  }
