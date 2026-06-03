package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.typed.{InputField, OutputField, Signature, Spec}
import munit.FunSuite
import zio.blocks.schema.DynamicValue

// Top-level spec trait (Mirror derivation requires a top-level type).
trait MccQaSpec extends Spec:
  def question: InputField[String]
  def answer:   OutputField[String]

/** Typed port of Python DSPy's `tests/predict/test_multi_chain_comparison.py`. */
class MultiChainComparisonSuite extends FunSuite:

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  /** Scripted Adapter that ignores the LM output and returns a fixed `(rationale, answer)`. Mirrors Python's
    * `DummyLM` returning a canned `{"rationale": ..., "answer": ...}`. */
  private object ScriptedAdapter extends Adapter:
    override val name: String = "scripted-mcc-adapter"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("q")))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = rec("rationale" := "my rationale", "answer" := "blue")))

  private object DummyLm extends LanguageModel:
    override val id: String   = "dummy-mcc-lm"
    override val mode: LmMode  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = "irrelevant"))))

  private def settings: RuntimeContext = RuntimeContext(lm = Some(DummyLm), adapter = Some(ScriptedAdapter))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  test("MultiChainComparison: augments the layout with M reasoning_attempt inputs and a rationale output") {
    val mcc = MultiChainComparison(baseSignature = Signature.of[MccQaSpec], m = 3)
    val sig = mcc.augmentedSignatureLayout

    val inputNames = sig.inputFields.map(_.name)
    assert(inputNames.contains("question"), inputNames.mkString(","))
    assert(inputNames.contains("reasoning_attempt_1"))
    assert(inputNames.contains("reasoning_attempt_2"))
    assert(inputNames.contains("reasoning_attempt_3"))

    val outputNames = sig.outputFields.map(_.name)
    assertEquals(outputNames.head, "rationale") // prepended
    assert(outputNames.contains("answer"))       // carries through
  }

  test("MultiChainComparison: runs the augmented predict and returns the corrected reasoning + answer") {
    val mcc = MultiChainComparison(baseSignature = Signature.of[MccQaSpec], m = 3)

    val completions = Vector(
      DynamicPrediction(values = rec(
        "rationale" := "I recall that during clear days, the sky often appears this color.",
        "answer"    := "blue"
      )),
      DynamicPrediction(values = rec(
        "rationale" := "Based on common knowledge, I believe the sky is typically seen as this color.",
        "answer"    := "green"
      )),
      DynamicPrediction(values = rec(
        "rationale" := "From images and depictions in media, the sky is frequently represented with this hue.",
        "answer"    := "blue"
      ))
    )

    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = mcc.compare((question = "What is the color of the sky?"), completions)
      assert(result.isRight, s"compare failed: ${result.left.toOption.map(_.message).getOrElse("?")}")
      val pred = result.toOption.get
      // typed dot-access on the augmented named tuple
      assertEquals(pred.output.rationale, "my rationale")
      assertEquals(pred.output.answer,    "blue")
    }
  }

  test("MultiChainComparison: rejects an attempt count that doesn't match M") {
    val mcc = MultiChainComparison(baseSignature = Signature.of[MccQaSpec], m = 3)
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = mcc.compare((question = "?"), Vector(DynamicPrediction.empty))
      assert(result.isLeft)
      assert(
        result.left.toOption.get.message.contains("doesn't match the configured m"),
        result.left.toOption.get.message
      )
    }
  }
