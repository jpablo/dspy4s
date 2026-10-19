package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterDemos
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.ToolChoice
import dspy4s.adapters.contracts.ToolParameterSpec
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ToolCall
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.:=
import dspy4s.core.data.Example
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue

/** The output rules of the adapters (PORT_GAPS G-29, items A1 and A2; decision D5; upstream `dspy/adapters/utils.py`
  * `apply_output_field_defaults` (ba8a64958), `dspy/adapters/chat_adapter.py` `parse` and `__call__`,
  * `dspy/adapters/json_adapter.py` `parse`, and `dspy/adapters/base.py` `format_demos` and `_call_preprocess`).
  *
  * The prompt text of a dspy4s adapter is not equal to the upstream text, so the tests assert content.
  */
class AdapterOutputRulesSuite extends FunSuite:

  private final class StubLm(functionCalling: Boolean) extends LanguageModel:
    override val id: String                                                                    = "stub"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsFunctionCalling: Boolean                                              = functionCalling
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("stub", "unused in adapter tests"))

  private def contextWith(functionCalling: Boolean): RuntimeContext =
    RuntimeContext(lm = Some(StubLm(functionCalling)))

  private given RuntimeContext = contextWith(functionCalling = false)

  private def layoutOf(inputs: Vector[FieldSpec], outputs: Vector[FieldSpec]): SignatureLayout =
    SignatureLayout.create(name = "QA", inputFields = inputs, outputFields = outputs).toOption.get

  /** `question -> answer, note`, with `note` as given. */
  private def withNote(note: FieldSpec): SignatureLayout =
    layoutOf(Vector(FieldSpec("question")), Vector(FieldSpec("answer"), note))

  private val optionalNote: FieldSpec  = FieldSpec("note", optional = true)
  private val defaultNote: FieldSpec   = FieldSpec("note", defaultValue = Some(DynamicValues.fromAny("none")))
  private val requiredNote: FieldSpec  = FieldSpec("note")
  private val optionalCount: FieldSpec = FieldSpec("count", typeRef = TypeRef.int, optional = true)

  private val textAdapters: Vector[(String, Adapter)] =
    Vector("ChatAdapter" -> ChatAdapter(), "JSONAdapter" -> JSONAdapter(), "XMLAdapter" -> XMLAdapter())

  /** The text of a response that has the field `answer` only, in the form of each adapter. */
  private val answerOnly: Map[String, String] = Map(
    "ChatAdapter" -> "[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]",
    "JSONAdapter" -> """{"answer": "Paris"}""",
    "XMLAdapter"  -> "<answer>Paris</answer>"
  )

  private def parse(adapter: Adapter, layout: SignatureLayout, text: String)(using
      RuntimeContext
  ): Either[DspyError, DynamicValue.Record] =
    adapter.parse(layout, LmOutput(text = text)).map(_.values)

  private def value(record: DynamicValue.Record, name: String): Option[DynamicValue] =
    DynamicValues.recordGet(record, name)

  private def string(text: String): DynamicValue = DynamicValues.fromAny(text)

  private def isParseError(result: Either[DspyError, ?]): Boolean =
    result match
      case Left(_: ParseError) => true
      case _                   => false

  // ── A1: optional and default outputs ────────────────────────────────────────────────────────────────────────

  test("a missing optional output is a null, in each text adapter") {
    textAdapters.foreach { (label, adapter) =>
      val parsed = parse(adapter, withNote(optionalNote), answerOnly(label))
      assertEquals(parsed.map(value(_, "answer")), Right(Some(string("Paris"))), label)
      assertEquals(parsed.map(value(_, "note")), Right(Some(DynamicValue.Null: DynamicValue)), label)
    }
  }

  test("a missing output with a default takes the default, also when the field is optional") {
    textAdapters.foreach { (label, adapter) =>
      assertEquals(
        parse(adapter, withNote(defaultNote), answerOnly(label)).map(value(_, "note")),
        Right(Some(string("none"))),
        label
      )
      assertEquals(
        parse(adapter, withNote(defaultNote.copy(optional = true)), answerOnly(label)).map(value(_, "note")),
        Right(Some(string("none"))),
        label
      )
    }
  }

  test("a missing required output is a parse error with the raw response") {
    textAdapters.foreach { (label, adapter) =>
      val result = parse(adapter, withNote(requiredNote), answerOnly(label))
      result match
        case Left(error: ParseError) =>
          assert(error.message.contains("note"), s"$label: ${error.message}")
          assertEquals(error.raw, Some(answerOnly(label)), label)
        case other => fail(s"$label: expected a ParseError, got $other")
    }
  }

  test("a present optional output keeps its value, and its type still applies") {
    val layout = layoutOf(Vector(FieldSpec("question")), Vector(FieldSpec("answer"), optionalCount))
    assertEquals(
      parse(ChatAdapter(), layout, "[[ ## answer ## ]]\nParis\n\n[[ ## count ## ]]\n3\n\n[[ ## completed ## ]]")
        .map(value(_, "count")),
      Right(Some(DynamicValues.fromAny(3)))
    )
    assertEquals(
      parse(JSONAdapter(), layout, """{"answer": "Paris", "count": 3}""").map(value(_, "count")),
      Right(Some(DynamicValues.fromAny(3)))
    )
  }

  // Upstream `parse_value`: an explicit null of an optional field is a null, not text.
  test("an explicit JSON null of an optional output is a null, and a required string output keeps the text") {
    val text = """{"answer": "Paris", "note": null}"""
    assertEquals(
      parse(JSONAdapter(), withNote(optionalNote), text).map(value(_, "note")),
      Right(Some(DynamicValue.Null: DynamicValue))
    )
    assertEquals(parse(JSONAdapter(), withNote(requiredNote), text).map(value(_, "note")), Right(Some(string("null"))))
  }

  test("in the chat format, the text null of an optional output that is not a string is a null") {
    val layout = layoutOf(Vector(FieldSpec("question")), Vector(FieldSpec("answer"), optionalCount, optionalNote))
    val text   = "[[ ## answer ## ]]\nParis\n\n[[ ## count ## ]]\nnull\n\n[[ ## note ## ]]\nnull\n\n[[ ## completed ## ]]"
    val parsed = parse(ChatAdapter(), layout, text)
    assertEquals(parsed.map(value(_, "count")), Right(Some(DynamicValue.Null: DynamicValue)))
    // An optional string output keeps its text, as upstream (`str | None` validates the text as a string).
    assertEquals(parsed.map(value(_, "note")), Right(Some(string("null"))))
  }

  // ── Coercion errors and duplicate headers ───────────────────────────────────────────────────────────────────

  test("a value that does not fit the type of its field is a parse error with the field name and the raw text") {
    val layout   = layoutOf(Vector(FieldSpec("question")), Vector(FieldSpec("count", typeRef = TypeRef.int)))
    val chatText = "[[ ## count ## ]]\nmany\n\n[[ ## completed ## ]]"
    parse(ChatAdapter(), layout, chatText) match
      case Left(error: ParseError) =>
        assert(error.message.contains("count"), error.message)
        assertEquals(error.raw, Some(chatText))
      case other => fail(s"expected a ParseError, got $other")

    val jsonText = """{"count": "many"}"""
    parse(JSONAdapter(), layout, jsonText) match
      case Left(error: ParseError) =>
        assert(error.message.contains("count"), error.message)
        assertEquals(error.raw, Some(jsonText))
      case other => fail(s"expected a ParseError, got $other")
  }

  test("the first header of a field wins in the chat format") {
    val layout = withNote(optionalNote)
    val text   = "[[ ## answer ## ]]\nParis\n\n[[ ## answer ## ]]\nLyon\n\n[[ ## note ## ]]\nok\n\n[[ ## completed ## ]]"
    assertEquals(parse(ChatAdapter(), layout, text).map(value(_, "answer")), Right(Some(string("Paris"))))
    assertEquals(parse(ChatAdapter(), layout, text).map(value(_, "note")), Right(Some(string("ok"))))
  }

  // ── A2: the adapter of the fallback ─────────────────────────────────────────────────────────────────────────

  test("a chat adapter falls back to a JSON adapter with its native function-calling options") {
    assertEquals(ChatAdapter().fallback, Some(JSONAdapter()))
    val native = ChatAdapter(
      useNativeFunctionCalling = true,
      parallelToolCalls = Some(true),
      toolChoice = Some(ToolChoice.Required)
    )
    assertEquals(
      native.fallback,
      Some(JSONAdapter(
        useNativeFunctionCalling = true,
        parallelToolCalls = Some(true),
        toolChoice = Some(ToolChoice.Required)
      ))
    )
    assertEquals(ChatAdapter(useJsonAdapterFallback = false).fallback, None)
    assertEquals(JSONAdapter().fallback, None)
    assertEquals(XMLAdapter().fallback, None)
  }

  // ── Incomplete demos ────────────────────────────────────────────────────────────────────────────────────────

  /** `question, context -> answer, reason`. */
  private lazy val demoLayout: SignatureLayout =
    layoutOf(Vector(FieldSpec("question"), FieldSpec("context")), Vector(FieldSpec("answer"), FieldSpec("reason")))

  private def demo(entries: (String, DynamicValue)*): Example =
    Example(values = DynamicValues.record(entries*), inputKeys = Set("question", "context"))

  private lazy val complete: Example = demo(
    "question" := "Q-complete",
    "context"  := "C-complete",
    "answer"   := "A-complete",
    "reason"   := "R-complete"
  )
  private lazy val incomplete: Example = demo("question" := "Q-incomplete", "answer" := "A-incomplete")
  private lazy val withNull: Example   = demo(
    "question" := "Q-null",
    "context"  := "C-null",
    "answer"   := "A-null",
    "reason"   -> DynamicValue.Null
  )
  private lazy val inputOnly: Example  = demo("question" := "Q-input-only", "context" := "C-input-only")
  private lazy val outputOnly: Example = demo("answer" := "A-output-only")

  private def demoMessages(adapter: Adapter, demos: Vector[Example]): Vector[Message] =
    val invocation = AdapterInvocation(
      layout = demoLayout,
      demos = demos,
      inputs = Example(DynamicValues.record("question" := "Q-live", "context" := "C-live"), Set("question", "context")),
      request = LmRequest(model = "stub")
    )
    adapter.format(invocation).fold(error => fail(s"format failed: $error"), _.messages)

  private lazy val demoAdapters: Vector[(String, Adapter)] = textAdapters :+
    ("TwoStepAdapter" -> TwoStepAdapter(extractionModel = StubLm(functionCalling = false)))

  test("an incomplete demo comes first, with the upstream prefix and the upstream text for a missing output") {
    demoAdapters.foreach { (label, adapter) =>
      // Upstream `TwoStepAdapter.format_assistant_message_content` shows the outputs that the demo has only.
      val twoStep  = label == "TwoStepAdapter"
      val messages = demoMessages(adapter, Vector(complete, incomplete))
      // System, two turns for each demo, and the live request.
      assertEquals(messages.map(_.role).count(_ == MessageRole.Assistant), 2, label)
      val texts = messages.map(_.text.getOrElse(""))
      val first = texts.indexWhere(_.contains("Q-incomplete"))
      val last  = texts.indexWhere(_.contains("Q-complete"))
      assert(first >= 0 && last > first, s"$label: the incomplete demo must come first: $texts")
      assert(texts(first).contains(AdapterDemos.IncompletePrefix), s"$label: ${texts(first)}")
      assert(!texts(last).contains(AdapterDemos.IncompletePrefix), s"$label: ${texts(last)}")
      val reply = texts(first + 1)
      assert(reply.contains("A-incomplete"), s"$label: $reply")
      assertEquals(reply.contains(AdapterDemos.NotSupplied.trim), !twoStep, s"$label: $reply")
    }
  }

  test("a demo with a null value is incomplete, and a demo with no input or no output is dropped") {
    demoAdapters.foreach { (label, adapter) =>
      val texts = demoMessages(adapter, Vector(withNull, inputOnly, outputOnly)).map(_.text.getOrElse(""))
      assert(texts.exists(t => t.contains("Q-null") && t.contains(AdapterDemos.IncompletePrefix)), s"$label: $texts")
      assert(!texts.exists(_.contains("Q-input-only")), s"$label: $texts")
      assert(!texts.exists(_.contains("A-output-only")), s"$label: $texts")
    }
  }

  test("a demo whose only output key has a null value is incomplete, as upstream counts the keys") {
    // Upstream `format_demos`: `has_output = any(k in demo for k in signature.output_fields)` counts a key with None.
    val nullOutput = demo(
      "question" := "Q-null-output",
      "context"  := "C-null-output",
      "answer"   -> DynamicValue.Null
    )
    demoAdapters.foreach { (label, adapter) =>
      val texts = demoMessages(adapter, Vector(nullOutput)).map(_.text.getOrElse(""))
      assert(
        texts.exists(t => t.contains("Q-null-output") && t.contains(AdapterDemos.IncompletePrefix)),
        s"$label: $texts"
      )
    }
  }

  test("a field default does not fill a demo") {
    val layout = layoutOf(
      Vector(FieldSpec("question")),
      Vector(FieldSpec("answer"), FieldSpec("reason", defaultValue = Some(DynamicValues.fromAny("DEFAULT-REASON"))))
    )
    val invocation = AdapterInvocation(
      layout = layout,
      demos = Vector(Example(DynamicValues.record("question" := "Q-demo", "answer" := "A-demo"), Set("question"))),
      inputs = Example(DynamicValues.record("question" := "Q-live"), Set("question")),
      request = LmRequest(model = "stub")
    )
    val texts = ChatAdapter().format(invocation).fold(error => fail(s"format failed: $error"), _.messages)
      .map(_.text.getOrElse(""))
    assert(!texts.exists(_.contains("DEFAULT-REASON")), texts.toString)
    assert(texts.exists(_.contains(AdapterDemos.NotSupplied.trim)), texts.toString)
  }

  // ── tool_calls as a text field when native function calling is off ──────────────────────────────────────────

  private lazy val agentLayout: SignatureLayout = layoutOf(
    Vector(FieldSpec("question")),
    Vector(FieldSpec("next_thought"), FieldSpec("tool_calls", typeRef = TypeRef.toolCalls))
  )

  private lazy val tools: Vector[ToolSpec] = Vector(
    ToolSpec(
      "search",
      Some("Search for documents."),
      Vector(ToolParameterSpec("query", TypeRef.string, None, required = true))
    )
  )

  private lazy val search: ToolCall = ToolCall(name = "search", args = DynamicValues.record("query" := "cats"))

  private def agentText(adapter: Adapter, functionCalling: Boolean): String =
    given RuntimeContext = contextWith(functionCalling)
    val invocation       = AdapterInvocation(
      layout = agentLayout,
      demos = Vector.empty,
      inputs = Example(DynamicValues.record("question" := "Find cats."), Set("question")),
      request = LmRequest(model = "stub"),
      tools = tools
    )
    adapter.format(invocation).fold(error => fail(s"format failed: $error"), _.messages)
      .flatMap(_.text).mkString("\n")

  test("with native function calling off, the prompt asks for tool_calls as text") {
    Vector(
      "ChatAdapter"                -> agentText(ChatAdapter(), functionCalling = true),
      "JSONAdapter"                -> agentText(JSONAdapter(), functionCalling = true),
      "ChatAdapter, no LM support" -> agentText(ChatAdapter(useNativeFunctionCalling = true), functionCalling = false)
    ).foreach { (label, text) =>
      assert(text.contains("tool_calls"), s"$label: $text")
    }
    // With native function calling active, the provider gives the tool calls, so the prompt does not ask for them.
    val native = agentText(ChatAdapter(useNativeFunctionCalling = true), functionCalling = true)
    assert(!native.contains("[[ ## tool_calls ## ]]"), native)
  }

  test("with native function calling off, the adapters read tool_calls from the text") {
    val expected = Right(Some(DynamicValue.Sequence(Chunk(search.toRecord)): DynamicValue))
    val chat     = """[[ ## next_thought ## ]]
        |I search.
        |
        |[[ ## tool_calls ## ]]
        |[{"name": "search", "args": {"query": "cats"}}]
        |
        |[[ ## completed ## ]]""".stripMargin
    assertEquals(parse(ChatAdapter(), agentLayout, chat).map(value(_, "tool_calls")), expected)
    val json = """{"next_thought": "I search.", "tool_calls": [{"name": "search", "args": {"query": "cats"}}]}"""
    assertEquals(parse(JSONAdapter(), agentLayout, json).map(value(_, "tool_calls")), expected)
  }

  test("with native function calling off, a missing or bad tool_calls is a parse error") {
    val missing = "[[ ## next_thought ## ]]\nI search.\n\n[[ ## completed ## ]]"
    assert(isParseError(parse(ChatAdapter(), agentLayout, missing)))
    val bad = "[[ ## next_thought ## ]]\nI search.\n\n[[ ## tool_calls ## ]]\nsearch for cats\n\n[[ ## completed ## ]]"
    assert(isParseError(parse(ChatAdapter(), agentLayout, bad)))
  }

  test("with native function calling active, the provider tool calls fill tool_calls, as before") {
    given RuntimeContext = contextWith(functionCalling = true)
    val output           =
      LmOutput(text = "[[ ## next_thought ## ]]\nI search.\n\n[[ ## completed ## ]]", toolCalls = Vector(search))
    assertEquals(
      ChatAdapter(useNativeFunctionCalling = true).parse(agentLayout, output).map(parsed =>
        value(parsed.values, "tool_calls")
      ),
      Right(Some(DynamicValue.Sequence(Chunk(search.toRecord)): DynamicValue))
    )
  }
