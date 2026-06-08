package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.ToolParameterSpec
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.core.contracts.ToolCall
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.schema.Schema

final case class Ask(question: String) derives Schema
final case class Plan(toolCalls: Vector[ToolCall]) derives Schema

class TypedToolCallsSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  /** A capable LM that returns a single native tool call (no text content). */
  private final class ToolLm extends LanguageModel:
    override val id: String                      = "tool-lm"
    override val mode: LmMode                     = LmMode.Chat
    override def supportsFunctionCalling: Boolean = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(
        text      = "",
        toolCalls = Vector(ToolCall("search", DynamicValues.record("query" := "belgium")))
      ))))

  test("typed Predict decodes native tool_calls into a Vector[ToolCall] output field") {
    val signature = Signature.derived[Ask, Plan]("QA").markToolCalls("toolCalls")
    val tools = Vector(
      ToolSpec("search", Some("Search the web"),
        Vector(ToolParameterSpec("query", TypeRef.string, Some("the query"), required = true)))
    )
    val predict = Predict(signature = signature, tools = tools)

    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(new ToolLm), adapter = Some(ChatAdapter(useNativeFunctionCalling = true)))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = predict.apply(Ask("capital of belgium?"))

      assert(result.isRight, s"expected success, got: $result")
      val plan = result.toOption.get.output
      assertEquals(plan.toolCalls.map(_.name), Vector("search"))
      assertEquals(
        plan.toolCalls.headOption.map(c => DynamicValues.renderText(c.args)),
        Some(DynamicValues.renderText(DynamicValues.record("query" := "belgium")))
      )
    }
  }
