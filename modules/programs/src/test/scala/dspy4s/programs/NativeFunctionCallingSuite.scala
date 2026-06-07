package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.ToolParameterSpec
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.ToolCall
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

class NativeFunctionCallingSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  /** Records the options of the request it received and returns a tool-only response. */
  private final class RecordingLm extends LanguageModel:
    @volatile var lastOptions: Option[DynamicValue.Record] = None
    override val id: String                       = "rec"
    override val mode: LmMode                       = LmMode.Chat
    override def supportsFunctionCalling: Boolean   = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      lastOptions = Some(request.options)
      Right(LmResponse(outputs = Vector(LmOutput(
        text      = "",
        toolCalls = Vector(ToolCall("search", DynamicValues.record("query" := "capital of belgium")))
      ))))

  private val layout: SignatureLayout =
    SignatureLayout.create(
      name = "Search",
      fields = Vector(
        FieldSpec("question", FieldRole.Input),
        FieldSpec("answer", FieldRole.Output),
        FieldSpec("tool_calls", FieldRole.Output, typeRef = TypeRef.toolCalls)
      )
    ).toOption.get

  private val tools: Vector[ToolSpec] = Vector(
    ToolSpec("search", Some("Search the web"),
      Vector(ToolParameterSpec("query", TypeRef.string, Some("the query"), required = true)))
  )

  test("DynamicPredict drives native function-calling end to end through ChatAdapter") {
    val lm      = new RecordingLm
    val predict = DynamicPredict(layout = layout, tools = tools)

    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter(useNativeFunctionCalling = true)))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = predict.apply(ProgramCall(inputs = DynamicValues.record("question" := "capital of belgium?")))

      assert(result.isRight, s"expected success, got: $result")
      val pred = result.toOption.get

      // The injected tools reached the provider request (end-to-end through PredictEngine + mergeOptions).
      assert(
        lm.lastOptions.exists(opts => DynamicValues.recordGet(opts, "tools").isDefined),
        s"tools missing from request options: ${lm.lastOptions}"
      )

      // The tool_calls output field was populated from the provider's structured tool_calls.
      val calls = pred.get("tool_calls").getOrElse(fail("missing tool_calls field")) match
        case DynamicValue.Sequence(els) => els.iterator.toVector
        case other                      => fail(s"expected a sequence for tool_calls, got $other")
      assertEquals(calls.size, 1)
    }
  }

  test("typed Predict also threads tools to the adapter (tools field on the typed path)") {
    val lm = new RecordingLm
    // A lenient (Map-shaped) typed signature with a tool_calls output field.
    val signature = Signature.fromStringDynamic("question -> answer, tool_calls: tool_calls").toOption.get
    val predict   = Predict(signature = signature, tools = tools)

    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter(useNativeFunctionCalling = true)))
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = predict.apply(DynamicValues.record("question" := "capital of belgium?"))

      assert(result.isRight, s"expected success, got: $result")
      assert(
        lm.lastOptions.exists(opts => DynamicValues.recordGet(opts, "tools").isDefined),
        s"tools missing from request options on the typed path: ${lm.lastOptions}"
      )
    }
  }
