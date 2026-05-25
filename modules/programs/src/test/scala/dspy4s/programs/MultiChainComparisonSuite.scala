package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.SettingsData
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite

/** Direct dspy4s port of Python DSPy's
  * `tests/predict/test_multi_chain_comparison.py`. */
class MultiChainComparisonSuite extends FunSuite:

  /** Scripted Adapter that ignores the LM output and returns a fixed
    * `(rationale, answer)` map. Mirrors the role of Python's `DummyLM`
    * returning a canned `{"rationale": ..., "answer": ...}`. */
  private object ScriptedAdapter extends Adapter:
    override val name: String = "scripted-mcc-adapter"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("q")))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = Map("rationale" -> "my rationale", "answer" -> "blue")))

  private object DummyLm extends LanguageModel:
    override val id: String = "dummy-mcc-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = "irrelevant"))))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit = RuntimeEnvironment.resetForTests()

  test("MultiChainComparison: augments signature with M reasoning_attempt inputs and a rationale output") {
    val base = SignatureDsl.parse("question -> answer").toOption.get
    val mcc = MultiChainComparison(baseSignature = base, m = 3)
    val sig = mcc.augmentedSignature

    val inputNames = sig.inputFields.map(_.name)
    assert(inputNames.contains("question"), inputNames.mkString(","))
    assert(inputNames.contains("reasoning_attempt_1"))
    assert(inputNames.contains("reasoning_attempt_2"))
    assert(inputNames.contains("reasoning_attempt_3"))

    val outputNames = sig.outputFields.map(_.name)
    // `rationale` was prepended; `answer` carries through.
    assertEquals(outputNames.head, "rationale")
    assert(outputNames.contains("answer"))
  }

  test("MultiChainComparison: runs the augmented predict and returns the corrected reasoning + answer") {
    // Mirror the Python test:
    //   compare_answers = dspy.MultiChainComparison(BasicQA)
    //   completions = [{rationale, answer}, ...]
    //   final_pred = compare_answers(completions, question=question)
    //   assert final_pred.rationale == "my rationale"
    //   assert final_pred.answer == "blue"
    val base = SignatureDsl.parse("question -> answer").toOption.get
    val mcc = MultiChainComparison(baseSignature = base, m = 3)

    val completions = Vector(
      DynamicPrediction(values = Map(
        "rationale" -> "I recall that during clear days, the sky often appears this color.",
        "answer" -> "blue"
      )),
      DynamicPrediction(values = Map(
        "rationale" -> "Based on common knowledge, I believe the sky is typically seen as this color.",
        "answer" -> "green"
      )),
      DynamicPrediction(values = Map(
        "rationale" -> "From images and depictions in media, the sky is frequently represented with this hue.",
        "answer" -> "blue"
      ))
    )

    RuntimeEnvironment.withSettings(
      SettingsData(Map(
        SettingKeys.languageModel.name -> DummyLm,
        SettingKeys.adapter.name -> ScriptedAdapter
      ))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val call = ProgramCall(inputs = Map("question" -> "What is the color of the sky?"))
      val result = mcc.runWithAttempts(call, completions.toVector)
      assert(result.isRight, s"runWithAttempts failed: ${result.left.toOption.map(_.message).getOrElse("?")}")
      val pred = result.toOption.get
      assertEquals(pred.values("rationale"), "my rationale")
      assertEquals(pred.values("answer"), "blue")
    }
  }

  test("MultiChainComparison: rejects an attempt count that doesn't match M") {
    val base = SignatureDsl.parse("question -> answer").toOption.get
    val mcc = MultiChainComparison(baseSignature = base, m = 3)

    RuntimeEnvironment.withSettings(
      SettingsData(Map(
        SettingKeys.languageModel.name -> DummyLm,
        SettingKeys.adapter.name -> ScriptedAdapter
      ))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val call = ProgramCall(inputs = Map("question" -> "?"))
      val result = mcc.runWithAttempts(call, Vector(DynamicPrediction(values = Map.empty)))
      assert(result.isLeft)
      assert(result.left.toOption.get.message.contains("doesn't match the configured m"), result.left.toOption.get.message)
    }
  }
