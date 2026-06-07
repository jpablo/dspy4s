package dspy4s.programs

import zio.blocks.schema.Schema

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.{DspyError, RuntimeContext, SignatureLayout}
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, LmUsage, Message, MessageRole}
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Signature
import munit.FunSuite

// Top-level fixtures (Mirror derivation requires top-level types).
case class MCQAInput(question: String) derives Schema
case class MCQAOutput(answer: String, score: Double) derives Schema

/** G-3: module-level `config` merged under the per-call config (per-call keys win). */
class ModuleConfigSuite extends FunSuite:

  private object EchoAdapter extends Adapter:
    override val name: String = "echo"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("hi")))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = rec("answer" := output.text, "score" := 0.5)))

  /** Records every `LmRequest` it receives so the test can inspect `options`. */
  private final class CapturingLm(val sink: scala.collection.mutable.ArrayBuffer[LmRequest]) extends LanguageModel:
    override val id: String   = "capturing-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      sink += request
      Right(LmResponse(
        outputs = Vector(LmOutput(text = "Paris", metadata = DynamicValues.record("score" := 0.5))),
        usage   = Some(LmUsage(totalTokens = 1, promptTokens = 1, completionTokens = 0))
      ))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit  = RuntimeEnvironment.resetForTests()

  private def withCapture(test: (RuntimeContext ?=> Unit)): Vector[LmRequest] =
    val sink = scala.collection.mutable.ArrayBuffer.empty[LmRequest]
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(CapturingLm(sink)), adapter = Some(EchoAdapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      test
    }
    sink.toVector

  // ── DynamicPredict ────────────────────────────────────────────────────────

  test("DynamicPredict: module config supplies LM options when per-call config is empty") {
    val layout = SignatureDsl.parse("question -> answer, score").toOption.get
    val module = DynamicPredict(layout, config = DynamicValues.record("temperature" := 0.7))
    val reqs = withCapture {
      val _ = module.apply(ProgramCall(inputs = rec("question" := "x")))
    }
    assertEquals(reqs.size, 1)
    val opts = DynamicValues.recordToMap(reqs.head.options)
    assertEquals(opts.get("temperature"), Some(0.7: Any))
  }

  test("DynamicPredict: per-call config overrides module config per-key (call wins, module default preserved)") {
    val layout = SignatureDsl.parse("question -> answer, score").toOption.get
    val module = DynamicPredict(layout, config = DynamicValues.record("temperature" := 0.7, "top_p" := 0.9))
    val reqs = withCapture {
      val _ = module.apply(ProgramCall(
        inputs = rec("question" := "x"),
        config = DynamicValues.record("temperature" := 0.2)
      ))
    }
    assertEquals(reqs.size, 1)
    val opts = DynamicValues.recordToMap(reqs.head.options)
    assertEquals(opts.get("temperature"), Some(0.2: Any)) // call wins
    assertEquals(opts.get("top_p"),       Some(0.9: Any)) // module default preserved
  }

  test("DynamicPredict: empty module config == per-call config (parity)") {
    val layout = SignatureDsl.parse("question -> answer, score").toOption.get
    val module = DynamicPredict(layout)
    val callConfig = DynamicValues.record("temperature" := 0.42, "max_tokens" := 64)
    val reqs = withCapture {
      val _ = module.apply(ProgramCall(inputs = rec("question" := "x"), config = callConfig))
    }
    assertEquals(reqs.size, 1)
    assertEquals(DynamicValues.recordToMap(reqs.head.options), DynamicValues.recordToMap(callConfig))
  }

  // ── typed Predict[I, O] ─────────────────────────────────────────────────────

  test("Predict[I,O]: module config supplies LM options when per-call config is empty") {
    val sig = Signature.derived[MCQAInput, MCQAOutput]("QA")
    val module = Predict(sig, config = DynamicValues.record("temperature" := 0.7))
    val reqs = withCapture {
      val _ = module.apply(MCQAInput("x"))
    }
    assertEquals(reqs.size, 1)
    val opts = DynamicValues.recordToMap(reqs.head.options)
    assertEquals(opts.get("temperature"), Some(0.7: Any))
  }

  test("Predict[I,O]: per-call config overrides module config per-key (call wins, module default preserved)") {
    val sig = Signature.derived[MCQAInput, MCQAOutput]("QA")
    val module = Predict(sig, config = DynamicValues.record("temperature" := 0.7, "top_p" := 0.9))
    val reqs = withCapture {
      val _ = module.apply(MCQAInput("x"), config = DynamicValues.record("temperature" := 0.2))
    }
    assertEquals(reqs.size, 1)
    val opts = DynamicValues.recordToMap(reqs.head.options)
    assertEquals(opts.get("temperature"), Some(0.2: Any)) // call wins
    assertEquals(opts.get("top_p"),       Some(0.9: Any)) // module default preserved
  }

  test("Predict[I,O]: empty module config == per-call config (parity)") {
    val sig = Signature.derived[MCQAInput, MCQAOutput]("QA")
    val module = Predict(sig)
    val callConfig = DynamicValues.record("temperature" := 0.42, "max_tokens" := 64)
    val reqs = withCapture {
      val _ = module.apply(MCQAInput("x"), config = callConfig)
    }
    assertEquals(reqs.size, 1)
    assertEquals(DynamicValues.recordToMap(reqs.head.options), DynamicValues.recordToMap(callConfig))
  }
