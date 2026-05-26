package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ModuleEndEvent
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.BasePredictProgram
import dspy4s.programs.runtime.SettingsProgramRuntime
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

class ProgramRuntimeSuite extends FunSuite:
  private object DummyLanguageModel extends LanguageModel:
    override val id: String = "dummy-lm"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = "ok"))))

  private object DummyAdapter extends Adapter:
    override val name: String = "dummy-adapter"

    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(
        FormattedPrompt(
          messages = Vector(Message(role = MessageRole.User, text = Some("test"))),
          metadata = Map.empty
        )
      )

    override def parse(signature: dspy4s.core.contracts.SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = Map("text" -> output.text)))

  private object RuntimeResolver extends SettingsProgramRuntime

  private final class EchoProgram extends BasePredictProgram("echo"):
    override protected def execute(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      Right(DynamicPrediction(values = call.inputs.updated("answer", "ok")))

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("resolve model and adapter from settings") {
    given RuntimeContext = RuntimeContext(
      lm = Some(DummyLanguageModel),
      adapter = Some(DummyAdapter)
    )

    assertEquals(RuntimeResolver.resolveModel, Right(DummyLanguageModel))
    assertEquals(RuntimeResolver.resolveAdapter, Right(DummyAdapter))
  }

  test("resolve model fails with wrong setting type") {
    given RuntimeContext = RuntimeContext(lm = Some(new dspy4s.core.contracts.LanguageModelRef {}))

    val result = RuntimeResolver.resolveModel
    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[ConfigurationError])
  }

  test("base predict program emits callbacks and appends trace/history") {
    val events = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    RuntimeEnvironment.withCallbacks(Vector(callback)) {
      given RuntimeContext = RuntimeEnvironment.current
      val program = EchoProgram()
      val output = program.run(ProgramCall(inputs = Map("question" -> "hello")))

      assert(output.isRight)
      assertEquals(output.toOption.get.values("answer"), "ok")
      assert(events.head.isInstanceOf[ModuleStartEvent])
      assert(events.last.isInstanceOf[ModuleEndEvent])
      assertEquals(RuntimeEnvironment.current.trace.size, 1)
      assertEquals(RuntimeEnvironment.current.history.size, 1)
    }
  }

  test("base predict program respects traceEnabled=false") {
    given RuntimeContext = RuntimeEnvironment.current
    val program = EchoProgram()
    val output = program.run(ProgramCall(inputs = Map("question" -> "hello"), traceEnabled = false))

    assert(output.isRight)
    assertEquals(RuntimeEnvironment.current.trace.size, 0)
    assertEquals(RuntimeEnvironment.current.history.size, 0)
  }
