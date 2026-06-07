package dspy4s.programs

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, SignatureLayout}
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, LmUsage, Message, MessageRole}
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite

import java.io.{ByteArrayOutputStream, PrintStream}

/** Mirrors upstream dspy 3.2.1 `predict.py` `_forward_preprocess`: passing input kwargs whose keys are not
  * in the signature's `input_fields` is tolerated (the extras are ignored, not an error), but a warning is
  * emitted naming the unexpected keys and the expected input fields. */
class ExtraInputWarningSuite extends FunSuite:

  private object EchoQuestionAdapter extends Adapter:
    override val name: String = "echo-question"

    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("hi")))))

    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = rec("answer" := output.text, "score" := 0.5)))

  private object FixedLm extends LanguageModel:
    override val id: String   = "fixed-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(
        outputs = Vector(LmOutput(text = "Paris", metadata = DynamicValues.record("score" := 0.95))),
        usage   = Some(LmUsage(totalTokens = 1, promptTokens = 1, completionTokens = 0))
      ))

  private val defaultSettings: RuntimeContext = RuntimeContext(lm = Some(FixedLm), adapter = Some(EchoQuestionAdapter))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private def captureErr(thunk: => Unit): String =
    val buffer = new ByteArrayOutputStream()
    Console.withErr(new PrintStream(buffer))(thunk)
    buffer.toString("UTF-8")

  test("DynamicPredict warns when inputs contain keys not declared as input fields") {
    val sig = SignatureDsl.parse("question -> answer, score").toOption.get
    val err = captureErr {
      RuntimeEnvironment.withSettings(defaultSettings) {
        given RuntimeContext = RuntimeEnvironment.current
        val result = DynamicPredict(sig).apply(
          ProgramCall(inputs = rec("question" := "x", "bogus" := "y", "extra" := "z"))
        )
        assert(result.isRight, s"extra inputs must NOT fail the call, got: $result")
        assertEquals(lookupString(result.toOption.get.values, "answer"), "Paris")
      }
    }
    assert(err.toLowerCase.contains("warn"), s"expected a warning to be emitted, stderr was: [$err]")
    assert(err.contains("bogus"), s"warning should name the unexpected key 'bogus', stderr was: [$err]")
    assert(err.contains("extra"), s"warning should name the unexpected key 'extra', stderr was: [$err]")
    assert(err.contains("question"), s"warning should name the expected input field 'question', stderr was: [$err]")
  }

  test("DynamicPredict emits no warning when all input keys are declared") {
    val sig = SignatureDsl.parse("question -> answer, score").toOption.get
    val err = captureErr {
      RuntimeEnvironment.withSettings(defaultSettings) {
        given RuntimeContext = RuntimeEnvironment.current
        val result = DynamicPredict(sig).apply(ProgramCall(inputs = rec("question" := "x")))
        assert(result.isRight)
      }
    }
    assertEquals(err.trim, "", s"expected no warning, stderr was: [$err]")
  }
