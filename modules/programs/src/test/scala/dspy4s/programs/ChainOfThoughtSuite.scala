package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SettingKeys
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

class ChainOfThoughtSuite extends FunSuite:
  private object DummyAdapter extends Adapter:
    override val name: String = "dummy-cot-adapter"

    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("q")))))

    override def parse(signature: dspy4s.core.contracts.Signature, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      Right(
        ParsedOutput(
          values = Map(
            "reasoning" -> "find the number after 1",
            "answer" -> output.text
          )
        )
      )

  private object DummyLanguageModel extends LanguageModel:
    override val id: String = "dummy-cot-lm"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = "2"))))

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("chain of thought augments output signature with reasoning field") {
    val base = SignatureDsl.parse("question -> answer").toOption.get
    val program = ChainOfThought(base)
    val signature = program.signature.toOption.get

    assertEquals(signature.outputFields.map(_.name), Vector("reasoning", "answer"))
  }

  test("chain of thought executes through predict pipeline") {
    val base = SignatureDsl.parse("question -> answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> DummyLanguageModel,
          SettingKeys.adapter.name -> DummyAdapter
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = ChainOfThought(base).run(ProgramCall(inputs = Map("question" -> "What is 1+1?")))

      assert(result.isRight)
      val prediction = result.toOption.get
      assertEquals(prediction.values("answer"), "2")
      assertEquals(prediction.values("reasoning"), "find the number after 1")
    }
  }

  test("chain of thought fails when runtime adapter is missing") {
    val base = SignatureDsl.parse("question -> answer").toOption.get
    RuntimeEnvironment.withSettings(SettingsData(Map(SettingKeys.languageModel.name -> DummyLanguageModel))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = ChainOfThought(base).run(ProgramCall(inputs = Map("question" -> "x")))
      assert(result.isLeft)
      assert(result.left.toOption.get.isInstanceOf[ConfigurationError])
    }
  }
