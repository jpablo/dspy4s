package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.ToolChoice
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

class ChatAdapterNativeToolsSuite extends FunSuite:

  /** Minimal LM stub: the adapter only reads `supportsFunctionCalling`; `call` is never exercised here. */
  private final class StubLm(fnCalling: Boolean) extends LanguageModel:
    override val id: String                     = "stub"
    override val mode: LmMode                    = LmMode.Chat
    override def supportsFunctionCalling: Boolean = fnCalling
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("stub", "unused in adapter tests"))

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

  private def invocation(withTools: Boolean = true): AdapterInvocation =
    AdapterInvocation(
      layout = layout,
      demos  = Vector.empty,
      inputs = Example(values = DynamicValues.record("question" := "capital of belgium?"), inputKeys = Set("question")),
      request = LmRequest(model = "stub"),
      tools  = if withTools then tools else Vector.empty
    )

  private def field(value: DynamicValue, key: String): DynamicValue =
    value match
      case r: DynamicValue.Record => DynamicValues.recordGet(r, key).getOrElse(fail(s"missing key '$key' in $r"))
      case other                  => fail(s"expected a record to read '$key' from, got $other")

  test("native function-calling injects tools into requestOptions and omits the tool_calls field from the prompt") {
    val adapter = ChatAdapter(useNativeFunctionCalling = true)
    given RuntimeContext = RuntimeContext(lm = Some(StubLm(fnCalling = true)))

    val prompt = adapter.format(invocation()).toOption.get

    assert(DynamicValues.recordGet(prompt.requestOptions, "tools").isDefined, "tools must be injected into requestOptions")
    val promptText = prompt.messages.flatMap(_.text).mkString("\n")
    assert(promptText.contains("answer"), "the real text output field must still be requested")
    assert(!promptText.contains("tool_calls"), s"the tool_calls field must not be rendered as a text field:\n$promptText")
  }

  test("native function-calling injects a string tool_choice when configured, and omits it otherwise") {
    given RuntimeContext = RuntimeContext(lm = Some(StubLm(fnCalling = true)))

    val withChoice = ChatAdapter(useNativeFunctionCalling = true, toolChoice = Some(ToolChoice.Required))
      .format(invocation()).toOption.get
    assertEquals(
      DynamicValues.recordGet(withChoice.requestOptions, "tool_choice").map(DynamicValues.renderText),
      Some("required")
    )

    val withoutChoice = ChatAdapter(useNativeFunctionCalling = true).format(invocation()).toOption.get
    assert(DynamicValues.recordGet(withoutChoice.requestOptions, "tool_choice").isEmpty,
      "tool_choice must be absent when not configured")
  }

  test("native function-calling forces a specific function via the object tool_choice form") {
    given RuntimeContext = RuntimeContext(lm = Some(StubLm(fnCalling = true)))

    val prompt = ChatAdapter(useNativeFunctionCalling = true, toolChoice = Some(ToolChoice.Function("search")))
      .format(invocation()).toOption.get
    val choice = DynamicValues.recordGet(prompt.requestOptions, "tool_choice").getOrElse(fail("tool_choice missing"))
    assertEquals(DynamicValues.renderText(field(choice, "type")), "function")
    assertEquals(DynamicValues.renderText(field(field(choice, "function"), "name")), "search")
  }

  test("native function-calling is suppressed when the LM does not support function calling") {
    val adapter = ChatAdapter(useNativeFunctionCalling = true)
    given RuntimeContext = RuntimeContext(lm = Some(StubLm(fnCalling = false)))

    val prompt = adapter.format(invocation()).toOption.get
    assert(DynamicValues.recordGet(prompt.requestOptions, "tools").isEmpty, "tools must NOT be injected without capability")
  }

  test("parse fills the tool_calls field from structured tool_calls and defaults other missing fields on a tool turn") {
    val adapter = ChatAdapter(useNativeFunctionCalling = true)
    given RuntimeContext = RuntimeContext(lm = Some(StubLm(fnCalling = true)))

    // A tool-call turn: the model returned only tool_calls, no answer text.
    val output = LmOutput(
      text = "",
      toolCalls = Vector(ToolCall("search", DynamicValues.record("query" := "capital of belgium")))
    )
    val parsed = adapter.parse(layout, output).toOption.get

    // tool_calls field is populated from the structured calls (a sequence of {name, args}).
    val calls = field(parsed.values, "tool_calls") match
      case DynamicValue.Sequence(els) => els.iterator.toVector
      case other                      => fail(s"expected a sequence for tool_calls, got $other")
    assertEquals(calls.size, 1)
    assertEquals(DynamicValues.renderText(field(calls.head, "name")), "search")
    assertEquals(DynamicValues.renderText(field(field(calls.head, "args"), "query")), "capital of belgium")

    // The missing `answer` text field defaults to Null rather than erroring (a tool turn has no final answer yet).
    assertEquals(DynamicValues.recordGet(parsed.values, "answer"), Some(DynamicValue.Null: DynamicValue))
  }
