package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
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
import dspy4s.lm.contracts.LmUsage
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.lm.contracts.ToolCall
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

class PredictSuite extends FunSuite:
  private object DummyAdapter extends Adapter:
    override val name: String = "dummy-adapter"

    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(
        FormattedPrompt(
          messages = Vector(
            Message(
              role = MessageRole.User,
              text = Some(invocation.inputs.values.get("question").map(_.toString).getOrElse(""))
            )
          )
        )
      )

    override def parse(signature: dspy4s.core.contracts.Signature, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      Right(
        ParsedOutput(
          values = Map(
            "answer" -> output.text,
            "score" -> output.metadata.getOrElse("score", 0.0)
          )
        )
      )

  private object DummyLanguageModel extends LanguageModel:
    override val id: String = "dummy-lm"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(
        LmResponse(
          outputs = Vector(
            LmOutput(text = "Paris", metadata = Map("score" -> 0.95)),
            LmOutput(text = "Lyon", metadata = Map("score" -> 0.33))
          ),
          usage = Some(LmUsage(totalTokens = 12, promptTokens = 7, completionTokens = 5))
        )
      )

  private object DummyToolCallLanguageModel extends LanguageModel:
    override val id: String = "dummy-lm-tools"
    override val mode: LmMode = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(
        LmResponse(
          outputs = Vector(
            LmOutput(
              text = "Need tool",
              metadata = Map("score" -> 0.5),
              toolCalls = Vector(ToolCall(name = "search", args = Map("query" -> "capital of belgium")))
            )
          )
        )
      )

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("predict runs adapter and lm pipeline with callbacks and completions") {
    val signature = SignatureDsl.parse("question -> answer, score").toOption.get
    val events = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> DummyLanguageModel,
          SettingKeys.adapter.name -> DummyAdapter
        )
      )
    ) {
      RuntimeEnvironment.withCallbacks(Vector(callback)) {
        given RuntimeContext = RuntimeEnvironment.current
        val result = Predict(signature).run(ProgramCall(inputs = Map("question" -> "Capital of France?")))

        assert(result.isRight)
        val prediction = result.toOption.get
        assertEquals(prediction.values("answer"), "Paris")
        assertEquals(prediction.asDouble("score"), Right(0.95))
        assertEquals(prediction.completions.get.size, 2)
        assertEquals(prediction.lmUsage.flatMap(_.get("total_tokens")), Some(12L))
        assertEquals(RuntimeEnvironment.current.trace.size, 1)
        assertEquals(RuntimeEnvironment.current.history.size, 1)
      }
    }

    assertEquals(
      events.map(_.getClass.getSimpleName).toVector,
      Vector(
        "ModuleStartEvent",
        "AdapterStartEvent",
        "AdapterEndEvent",
        "LmStartEvent",
        "LmEndEvent",
        "AdapterStartEvent",
        "AdapterEndEvent",
        "AdapterStartEvent",
        "AdapterEndEvent",
        "ModuleEndEvent"
      )
    )
  }

  test("predict fails with configuration error when adapter is missing") {
    val signature = SignatureDsl.parse("question -> answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> DummyLanguageModel
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = Predict(signature).run(ProgramCall(inputs = Map("question" -> "x")))

      assert(result.isLeft)
      assert(result.left.toOption.get.isInstanceOf[ConfigurationError])
    }
  }

  test("predict surfaces lm tool calls in prediction values") {
    val signature = SignatureDsl.parse("question -> answer, score").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> DummyToolCallLanguageModel,
          SettingKeys.adapter.name -> DummyAdapter
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = Predict(signature).run(ProgramCall(inputs = Map("question" -> "x")))

      assert(result.isRight)
      val toolCalls = result.toOption.get.values("tool_calls").asInstanceOf[Vector[Map[String, Any]]]
      assertEquals(toolCalls.size, 1)
      assertEquals(toolCalls.head("name"), "search")
      assertEquals(toolCalls.head("args"), Map("query" -> "capital of belgium"))
    }
  }
