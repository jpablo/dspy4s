package dspy4s.programs

import zio.blocks.schema.Schema

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.{DspyError, RuntimeContext, SignatureLayout}
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, Message, MessageRole}
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Signature
import munit.FunSuite

// Top-level fixtures (Mirror derivation requires top-level types).
case class BlmInput(question: String) derives Schema
case class BlmOutput(answer: String) derives Schema

/** G-3 (bound LM): a per-module `lm` is used in preference to the ambient `RuntimeContext` LM. */
class BoundLmSuite extends FunSuite:

  private object EchoAdapter extends Adapter:
    override val name: String = "echo"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("hi")))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = rec("answer" := output.text)))

  private final class FixedLm(id0: String, reply: String) extends LanguageModel:
    override val id: String   = id0
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = reply))))

  private val ambientLm = new FixedLm("ambient", "AMBIENT")
  private val boundLm   = new FixedLm("bound", "BOUND")

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private def underAmbient[A](body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(ambientLm), adapter = Some(EchoAdapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      body
    }

  test("DynamicPredict: a bound lm overrides the ambient RuntimeContext lm") {
    val layout = SignatureDsl.parse("question -> answer").toOption.get
    val module = DynamicPredict(layout = layout, lm = Some(boundLm))
    val out = underAmbient(module.apply(ProgramCall(inputs = rec("question" := "x"))))
    assertEquals(lookupString(out.toOption.get.values, "answer"), "BOUND")
  }

  test("DynamicPredict: no bound lm falls back to the ambient RuntimeContext lm") {
    val layout = SignatureDsl.parse("question -> answer").toOption.get
    val module = DynamicPredict(layout = layout)
    val out = underAmbient(module.apply(ProgramCall(inputs = rec("question" := "x"))))
    assertEquals(lookupString(out.toOption.get.values, "answer"), "AMBIENT")
  }

  test("Predict[I,O]: withLm pins a bound lm used in preference to ambient") {
    val sig = Signature.derived[BlmInput, BlmOutput]("QA")
    val module = Predict(sig).withLm(boundLm)
    assertEquals(module.boundLm.map(_.id), Some("bound"))
    val out = underAmbient(module.apply(BlmInput("x")))
    assertEquals(out.toOption.get.output.answer, "BOUND")
  }

  test("Predict[I,O]: no bound lm falls back to ambient") {
    val sig = Signature.derived[BlmInput, BlmOutput]("QA")
    val module = Predict(sig)
    val out = underAmbient(module.apply(BlmInput("x")))
    assertEquals(out.toOption.get.output.answer, "AMBIENT")
  }
