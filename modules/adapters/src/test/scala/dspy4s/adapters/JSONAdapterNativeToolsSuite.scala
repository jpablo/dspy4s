package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.ToolParameterSpec
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.:=
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.core.contracts.ToolCall
import munit.FunSuite
import zio.blocks.schema.DynamicValue

class JSONAdapterNativeToolsSuite extends FunSuite:

  private final class StubLm(fnCalling: Boolean) extends LanguageModel:
    override val id: String                      = "stub"
    override val mode: LmMode                      = LmMode.Chat
    override def supportsFunctionCalling: Boolean  = fnCalling
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("stub", "unused"))

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

  private def invocation: AdapterInvocation =
    AdapterInvocation(
      layout = layout,
      demos  = Vector.empty,
      inputs = Example(values = DynamicValues.record("question" := "capital of belgium?"), inputKeys = Set("question")),
      request = LmRequest(model = "stub"),
      tools  = tools
    )

  private def field(value: DynamicValue, key: String): DynamicValue =
    value match
      case r: DynamicValue.Record => DynamicValues.recordGet(r, key).getOrElse(fail(s"missing key '$key' in $r"))
      case other                  => fail(s"expected a record to read '$key' from, got $other")

  test("JSONAdapter native function-calling injects tools and omits the tool_calls key from the instruction") {
    val adapter = JSONAdapter(useNativeFunctionCalling = true)
    given RuntimeContext = RuntimeContext(lm = Some(StubLm(fnCalling = true)))

    val prompt = adapter.format(invocation).toOption.get
    assert(DynamicValues.recordGet(prompt.requestOptions, "tools").isDefined, "tools must be injected")
    val promptText = prompt.messages.flatMap(_.text).mkString("\n")
    assert(promptText.contains("answer"), "the real text key must still be requested")
    assert(!promptText.contains("tool_calls"), s"tool_calls must not be a requested JSON key:\n$promptText")
  }

  test("JSONAdapter parse fills the tool_calls field and defaults other fields on a tool turn") {
    val adapter = JSONAdapter(useNativeFunctionCalling = true)
    given RuntimeContext = RuntimeContext(lm = Some(StubLm(fnCalling = true)))

    val output = LmOutput(
      text = "",
      toolCalls = Vector(ToolCall("search", DynamicValues.record("query" := "capital of belgium")))
    )
    val parsed = adapter.parse(layout, output).toOption.get

    val calls = field(parsed.values, "tool_calls") match
      case DynamicValue.Sequence(els) => els.iterator.toVector
      case other                      => fail(s"expected a sequence for tool_calls, got $other")
    assertEquals(calls.size, 1)
    assertEquals(DynamicValues.renderText(field(calls.head, "name")), "search")
    assertEquals(DynamicValues.recordGet(parsed.values, "answer"), Some(DynamicValue.Null: DynamicValue))
  }
