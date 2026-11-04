package dspy4s.adapters

import dspy4s.adapters.contracts.{
  Adapter, AdapterDemos, AdapterInvocation, DelegatingAdapter, NativeFunctionCalling, NativeTools, ParsedOutput,
  ToolSpec
}
import dspy4s.core.contracts.{
  ConfigurationError, DspyError, DynamicValues, FieldSpec, History, RuntimeContext, RuntimeError, SignatureLayout,
  ToolCall, TypeRef, ValidationError, :=
}
import dspy4s.core.data.Example
import dspy4s.core.decisions.{DecisionEvidence, DecisionPlan}
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, Message}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

/** The tools of a predictor must reach the model. Upstream has them in an input field of the type `list[dspy.Tool]`, so
  * the prompt shows them when the request has no provider tools (`_call_preprocess` in `dspy/adapters/base.py`). It
  * raises a `ValueError` for a tool calls output with no tools input when native function calling is on.
  */
class ToolsInPromptSuite extends FunSuite:

  private final class StubLm(functionCalling: Boolean) extends LanguageModel:
    override val id: String                                                                    = "stub"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsFunctionCalling: Boolean                                              = functionCalling
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("stub", "unused"))

  private def layoutOf(inputs: FieldSpec*): SignatureLayout =
    SignatureLayout.create(
      name = "Manual",
      inputFields = FieldSpec("question") +: inputs.toVector,
      outputFields = Vector(FieldSpec("outputs", typeRef = TypeRef.toolCalls))
    ).fold(error => fail(error.toString), identity)

  private def plainLayout: SignatureLayout =
    SignatureLayout.create(
      name = "Plain",
      inputFields = Vector(FieldSpec("question")),
      outputFields = Vector(FieldSpec("answer"))
    ).fold(error => fail(error.toString), identity)

  private def weather: ToolSpec =
    ToolSpec(
      "weather",
      Some("Get weather information for a city."),
      DynamicValues.record(
        "type"       := "object",
        "properties" -> DynamicValues.record("city" -> DynamicValues.record("type" := "string")),
        "required"   := List("city")
      )
    )

  private def invocationOf(layout: SignatureLayout, tools: Vector[ToolSpec] = Vector(weather)): AdapterInvocation =
    AdapterInvocation(
      layout = layout,
      demos = Vector.empty,
      inputs = Example(values = DynamicValues.record("question" := "Weather?"), inputKeys = Set("question")),
      request = LmRequest(model = "stub"),
      tools = tools
    )

  private def contextOf(functionCalling: Boolean): RuntimeContext =
    RuntimeContext(lm = Some(StubLm(functionCalling)))

  private def promptText(adapter: Adapter, invocation: AdapterInvocation)(using RuntimeContext): String =
    adapter.format(invocation).fold(error => fail(error.toString), _.messages.flatMap(_.text).mkString("\n"))

  private def textAdapters: Vector[Adapter] =
    Vector(
      ChatAdapter(),
      ChatAdapter(native = NativeTools(enabled = true)),
      JSONAdapter(),
      JSONAdapter(native = NativeTools.Off),
      XMLAdapter()
    )

  test("with no provider tools, the prompt shows each tool of the predictor as an input, with its arguments") {
    given RuntimeContext = contextOf(functionCalling = false)
    textAdapters.foreach { adapter =>
      val prompt = promptText(adapter, invocationOf(layoutOf()))
      assert(prompt.contains("`tools`"), s"${adapter.name}: $prompt")
      assert(
        prompt.contains("weather, whose description is <desc>Get weather information"),
        s"${adapter.name}: $prompt"
      )
      // The description also has the word "city", so the test reads the arguments in the form of the JSON list.
      assert(prompt.contains("""It takes arguments {\"city\": {\"type\": \"string\"}}."""), s"${adapter.name}: $prompt")
    }
  }

  test("with an adapter that has native function calling off, the prompt shows the tools on each model") {
    given RuntimeContext = contextOf(functionCalling = true)
    Vector(ChatAdapter(), JSONAdapter(native = NativeTools.Off), XMLAdapter()).foreach { adapter =>
      val prompt = promptText(adapter, invocationOf(layoutOf()))
      assert(prompt.contains("weather, whose description is"), s"${adapter.name}: $prompt")
    }
  }

  test("with provider tools, the request has the tools and the prompt does not show them") {
    given RuntimeContext = contextOf(functionCalling = true)
    val adapter          = ChatAdapter(native = NativeTools(enabled = true))
    val prompt           = adapter.format(invocationOf(layoutOf())).fold(error => fail(error.toString), identity)
    assert(DynamicValues.recordGet(prompt.requestOptions, "tools").isDefined, prompt.toString)
    assert(!prompt.messages.flatMap(_.text).mkString.contains("whose description is"), prompt.toString)
  }

  test("a layout with its own tools input keeps that one input") {
    given RuntimeContext = contextOf(functionCalling = false)
    val invocation       = invocationOf(layoutOf(FieldSpec("tools", typeRef = TypeRef.tools))).copy(
      inputs = Example(
        values = DynamicValues.record("question" := "Weather?", "tools" := List("own text")),
        inputKeys = Set("question", "tools")
      )
    )
    val prompt = promptText(ChatAdapter(), invocation)
    assert(prompt.contains("own text"), prompt)
    assert(!prompt.contains("whose description is"), prompt)
  }

  test("a layout with an other input of the name tools keeps that input") {
    given RuntimeContext = contextOf(functionCalling = false)
    val invocation       = invocationOf(layoutOf(FieldSpec("tools"))).copy(
      inputs = Example(
        values = DynamicValues.record("question" := "Weather?", "tools" := "hammer"),
        inputKeys = Set("question", "tools")
      )
    )
    val prompt = promptText(ChatAdapter(), invocation)
    assert(prompt.contains("hammer"), prompt)
    assert(!prompt.contains("whose description is"), prompt)
  }

  test("a layout with no tool calls field does not show the tools, because the model has no field for a call") {
    given RuntimeContext = contextOf(functionCalling = false)
    assert(!promptText(ChatAdapter(), invocationOf(plainLayout)).contains("whose description is"))
  }

  test("the parse of the reply does not depend on the tools input") {
    given RuntimeContext = contextOf(functionCalling = false)
    val parsed           = ChatAdapter().parse(
      invocationOf(layoutOf()),
      dspy4s.lm.contracts.LmOutput(text =
        "[[ ## outputs ## ]]\n{\"tool_calls\": [{\"name\": \"weather\", \"args\": {\"city\": \"Paris\"}}]}\n\n[[ ## completed ## ]]"
      )
    )
    assert(parsed.isRight, parsed.toString)
  }

  test("a tool calls output with no tools is an error when native function calling is on, as upstream") {
    given RuntimeContext = contextOf(functionCalling = true)
    Vector(ChatAdapter(native = NativeTools(enabled = true)), JSONAdapter()).foreach { adapter =>
      adapter.format(invocationOf(layoutOf(), tools = Vector.empty)) match
        case Left(error: ConfigurationError) =>
          assert(error.message.contains("did not provide any tools"), error.message)
        case other => fail(s"${adapter.name}: expected a configuration error, got $other")
    }
  }

  test("a tool calls output with no tools is not an error when native function calling is off, as upstream") {
    given RuntimeContext = contextOf(functionCalling = true)
    assert(ChatAdapter().format(invocationOf(layoutOf(), tools = Vector.empty)).isRight)
    assert(XMLAdapter().format(invocationOf(layoutOf(), tools = Vector.empty)).isRight)
  }

  test("a tools input is enough for a tool calls output when native function calling is on") {
    given RuntimeContext = contextOf(functionCalling = false)
    val invocation       = invocationOf(layoutOf(FieldSpec("tools", typeRef = TypeRef.tools)), tools = Vector.empty).copy(
      inputs = Example(
        values = DynamicValues.record("question" := "Weather?", "tools" := List("own text")),
        inputKeys = Set("question", "tools")
      )
    )
    assert(ChatAdapter(native = NativeTools(enabled = true)).format(invocation).isRight)
  }

  // ── The form of the tools input ──

  private def nativeOn: NativeTools = NativeTools(enabled = true)

  private def messagesOf(adapter: Adapter, invocation: AdapterInvocation)(using RuntimeContext): Vector[Message] =
    adapter.format(invocation).fold(error => fail(s"${adapter.name}: $error"), _.messages)

  private def textOf(message: Message): String = message.text.getOrElse("")

  test("the tools input has the form of an input of the layout with the same texts") {
    given RuntimeContext = contextOf(functionCalling = false)
    val own              = invocationOf(layoutOf(FieldSpec("tools", typeRef = TypeRef.tools)), tools = Vector.empty).copy(
      inputs = Example(
        values = DynamicValues.record("question" := "Weather?", "tools" := List(weather.text)),
        inputKeys = Set("question", "tools")
      )
    )
    textAdapters.foreach(adapter =>
      assertEquals(messagesOf(adapter, invocationOf(layoutOf())), messagesOf(adapter, own), adapter.name)
    )
  }

  test("the tools input is the last input, its value is a JSON list, and the default instructions name it") {
    given RuntimeContext = contextOf(functionCalling = false)
    val messages         = messagesOf(ChatAdapter(), invocationOf(layoutOf()))
    val system           = textOf(messages.head)
    assert(system.contains("1. `question` (str): \n2. `tools` (list[Tool]):\n"), system)
    assert(system.contains("[[ ## question ## ]]\n{question}\n\n[[ ## tools ## ]]\n{tools}"), system)
    assert(system.contains("Given the fields `question`, `tools`, produce the fields `outputs`."), system)
    val user     = textOf(messages.last)
    val expected = "[[ ## question ## ]]\nWeather?\n\n[[ ## tools ## ]]\n" +
      """["weather, whose description is <desc>Get weather information for a city.</desc>. """ +
      """It takes arguments {\"city\": {\"type\": \"string\"}}."]"""
    assert(user.startsWith(expected), user)
  }

  test("the texts of the tools come in the order of the tool specs") {
    given RuntimeContext = contextOf(functionCalling = false)
    val bare             = ToolSpec("bare", None, DynamicValue.Record.empty)
    val user             = textOf(messagesOf(ChatAdapter(), invocationOf(layoutOf(), Vector(weather, bare))).last)
    assert(user.contains("""}}.", "bare. It takes arguments {}."]"""), user)
  }

  test("the text of a tool spec: no description part with no description, and {} with no properties") {
    assertEquals(ToolSpec("bare", None, DynamicValue.Record.empty).text, "bare. It takes arguments {}.")
    assertEquals(ToolSpec("bare", Some(""), DynamicValue.Record.empty).text, "bare. It takes arguments {}.")
    assertEquals(
      ToolSpec("two", Some("One.\nTwo."), DynamicValue.Record.empty).text,
      "two, whose description is <desc>One.  Two.</desc>. It takes arguments {}."
    )
  }

  test("an output with the name tools stays an output, and the prompt gets no input with that name") {
    given RuntimeContext = contextOf(functionCalling = false)
    val layout           = SignatureLayout.create(
      name = "Manual",
      inputFields = Vector(FieldSpec("question")),
      outputFields = Vector(FieldSpec("tools", typeRef = TypeRef.toolCalls))
    ).fold(error => fail(error.toString), identity)
    val prompt = promptText(ChatAdapter(), invocationOf(layout))
    assert(prompt.contains("1. `tools` (ToolCalls)"), prompt)
    assert(!prompt.contains("whose description is"), prompt)
  }

  test("with provider tools, the prompt hides a tools input of the layout") {
    given RuntimeContext = contextOf(functionCalling = true)
    val invocation       = invocationOf(layoutOf(FieldSpec("tools", typeRef = TypeRef.tools))).copy(
      inputs = Example(
        values = DynamicValues.record("question" := "Weather?", "tools" := List("own text")),
        inputKeys = Set("question", "tools")
      )
    )
    val prompt = ChatAdapter(native = nativeOn).format(invocation).fold(error => fail(error.toString), identity)
    assert(DynamicValues.recordGet(prompt.requestOptions, "tools").isDefined, prompt.toString)
    assert(!prompt.messages.flatMap(_.text).mkString.contains("own text"), prompt.toString)
  }

  // ── The error ──

  test("the error reads the flag, not the model, and names the field and the two ways to give tools") {
    Vector(true, false).foreach { functionCalling =>
      given RuntimeContext = contextOf(functionCalling)
      ChatAdapter(native = nativeOn).format(invocationOf(layoutOf(), tools = Vector.empty)) match
        case Left(error: ConfigurationError) =>
          assert(error.message.contains("output field outputs"), error.message)
          assert(error.message.contains("did not provide any tools"), error.message)
          assert(error.message.contains("`tools` (`TypeRef.tools`)"), error.message)
          assert(error.message.contains("the `tools` of the predictor"), error.message)
        case other => fail(s"expected a configuration error, got $other")
    }
  }

  test("an input of the name tools with an other type does not count as tools for the error") {
    given RuntimeContext = contextOf(functionCalling = true)
    val invocation       = invocationOf(layoutOf(FieldSpec("tools")), tools = Vector.empty).copy(
      inputs = Example(
        values = DynamicValues.record("question" := "Weather?", "tools" := "hammer"),
        inputKeys = Set("question", "tools")
      )
    )
    assert(ChatAdapter(native = nativeOn).format(invocation).isLeft)
  }

  test("a layout with no tool calls field gets no error for no tools") {
    given RuntimeContext = contextOf(functionCalling = true)
    assert(ChatAdapter(native = nativeOn).format(invocationOf(plainLayout, tools = Vector.empty)).isRight)
    assert(JSONAdapter().format(invocationOf(plainLayout, tools = Vector.empty)).isRight)
  }

  // ── The response format of the JSON adapter ──

  private final class SchemaLm(functionCalling: Boolean) extends LanguageModel:
    override val id: String                                                                    = "schema"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsFunctionCalling: Boolean                                              = functionCalling
    override def supportsResponseSchema: Boolean                                               = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("schema", "unused"))

  test("a text tool calls field keeps JSON mode, and the request has no provider tools") {
    Vector(true -> JSONAdapter(native = NativeTools.Off), false -> JSONAdapter()).foreach {
      (functionCalling, adapter) =>
        given RuntimeContext = RuntimeContext(lm = Some(SchemaLm(functionCalling)))
        val prompt           = adapter.format(invocationOf(layoutOf())).fold(error => fail(error.toString), identity)
        assertEquals(
          DynamicValues.recordGet(prompt.requestOptions, "response_format"),
          Some[DynamicValue](DynamicValues.record("type" := "json_object"))
        )
        assert(DynamicValues.recordGet(prompt.requestOptions, "tools").isEmpty, prompt.toString)
        assert(prompt.removedOptions.contains("tools"), prompt.toString)
        assert(prompt.messages.flatMap(_.text).mkString.contains("whose description is"), prompt.toString)
    }
  }

  // ── Demos and the replay of a history ──

  private def outputsValue: DynamicValue =
    NativeFunctionCalling.encodeToolCalls(Vector(ToolCall("weather", DynamicValues.record("city" := "Oslo"))))

  private def demo: Example =
    Example(
      values = DynamicValues.record("question" := "Demo?", "outputs" -> outputsValue),
      inputKeys = Set("question")
    )

  test("a demo with no tools value is an incomplete demo when the prompt shows the tools, as upstream") {
    given RuntimeContext = contextOf(functionCalling = false)
    textAdapters.foreach { adapter =>
      val messages = messagesOf(adapter, invocationOf(layoutOf()).copy(demos = Vector(demo)))
      assertEquals(messages.size, 4, adapter.name)
      val user = textOf(messages(1))
      assert(user.startsWith(AdapterDemos.IncompletePrefix), s"${adapter.name}: $user")
      assert(user.contains("Demo?"), s"${adapter.name}: $user")
      assert(!user.contains("whose description is"), s"${adapter.name}: $user")
      assert(textOf(messages(2)).contains("Oslo"), s"${adapter.name}: ${textOf(messages(2))}")
      assert(textOf(messages.last).contains("whose description is"), adapter.name)
    }
  }

  test("a demo with no tools value is a complete demo when the request has provider tools") {
    given RuntimeContext = contextOf(functionCalling = true)
    val messages         = messagesOf(ChatAdapter(native = nativeOn), invocationOf(layoutOf()).copy(demos = Vector(demo)))
    assertEquals(messages.size, 4)
    assert(!textOf(messages(1)).startsWith(AdapterDemos.IncompletePrefix), textOf(messages(1)))
  }

  test("a replayed turn of a history does not repeat the tools") {
    given RuntimeContext = contextOf(functionCalling = false)
    val history          = History(Vector(DynamicValues.record("question" := "Earlier?", "outputs" -> outputsValue)))
    val invocation       = invocationOf(layoutOf(FieldSpec("history", typeRef = TypeRef.history))).copy(
      inputs = Example(
        values = DynamicValues.record(
          "question" := "Weather?",
          "history"  -> summon[Schema[History]].toDynamicValue(history)
        ),
        inputKeys = Set("question", "history")
      )
    )
    textAdapters.foreach { adapter =>
      val messages = messagesOf(adapter, invocation)
      assertEquals(messages.size, 4, adapter.name)
      assert(textOf(messages(1)).contains("Earlier?"), s"${adapter.name}: ${textOf(messages(1))}")
      assert(!textOf(messages(1)).contains("whose description is"), s"${adapter.name}: ${textOf(messages(1))}")
      assert(textOf(messages.last).contains("whose description is"), adapter.name)
    }
  }

  // ── A whole call: the fallback attempt, and the wrappers ──

  private final class Script(functionCalling: Boolean) extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val id: String                                                                    = "script"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsFunctionCalling: Boolean                                              = functionCalling
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      synchronized { requests = requests :+ request }
      Right(LmResponse(outputs = Vector(LmOutput("This reply has no field."))))

  test("the fallback attempt shows the tools too, with no provider tools") {
    Vector(
      false -> ChatAdapter(native = nativeOn),
      true  -> ChatAdapter(),
      true  -> XMLAdapter()
    ).foreach { (functionCalling, adapter) =>
      val lm               = Script(functionCalling)
      given RuntimeContext = RuntimeContext(lm = Some(lm))
      assert(AdapterCall.run(adapter, lm, invocationOf(layoutOf())).isLeft, adapter.name)
      assertEquals(lm.requests.size, 2, adapter.name)
      lm.requests.foreach { request =>
        assert(request.messages.flatMap(_.text).mkString.contains("whose description is"), adapter.name)
        assert(DynamicValues.recordGet(request.options, "tools").isEmpty, adapter.name)
      }
    }
  }

  test("the error of the format step comes before a model call, and gets no fallback") {
    val lm               = Script(true)
    given RuntimeContext = RuntimeContext(lm = Some(lm))
    AdapterCall.run(ChatAdapter(native = nativeOn), lm, invocationOf(layoutOf(), tools = Vector.empty)) match
      case Left(_: ConfigurationError) => assertEquals(lm.requests.size, 0)
      case other                       => fail(s"expected a configuration error, got $other")
  }

  test("a decision adapter has the rules of its inner adapter") {
    given RuntimeContext = contextOf(functionCalling = false)
    val layout           = SignatureLayout.create(
      name = "Probe",
      inputFields = Vector(FieldSpec("question")),
      outputFields = Vector(
        FieldSpec("urgent", TypeRef.noul, description = Some("Is it urgent?")),
        FieldSpec("outputs", typeRef = TypeRef.toolCalls)
      )
    ).fold(error => fail(error.toString), identity)
    val plan    = DecisionPlan.of(layout, Map.empty).fold(error => fail(error.message), identity)
    val adapter = DecisionAdapter(ChatAdapter(native = nativeOn), plan)
    assert(promptText(adapter, invocationOf(layout)).contains("whose description is"))
    assert(adapter.format(invocationOf(layout, tools = Vector.empty)).isLeft)
  }

  // ── A tools input when native function calling is active ──

  private def ownToolsInvocation: AdapterInvocation =
    invocationOf(layoutOf(FieldSpec("tools", typeRef = TypeRef.tools)), tools = Vector.empty).copy(
      inputs = Example(
        values = DynamicValues.record("question" := "Weather?", "tools" := List("own text")),
        inputKeys = Set("question", "tools")
      )
    )

  test("a tools input with no tool specs is an error when native function calling is active: it holds only text") {
    given RuntimeContext = contextOf(functionCalling = true)
    Vector(ChatAdapter(native = nativeOn), JSONAdapter()).foreach { adapter =>
      adapter.format(ownToolsInvocation) match
        case Left(error: ConfigurationError) =>
          assert(error.message.contains("output field outputs"), error.message)
          assert(error.message.contains("holds only text"), error.message)
          assert(error.message.contains("the `tools` of the predictor"), error.message)
          assert(error.message.contains("native function calling off"), error.message)
        case other => fail(s"${adapter.name}: expected a configuration error, got $other")
    }
  }

  test("with native function calling active, the error for no tools does not advise a tools input as it is") {
    given RuntimeContext = contextOf(functionCalling = true)
    ChatAdapter(native = nativeOn).format(invocationOf(layoutOf(), tools = Vector.empty)) match
      case Left(error: ConfigurationError) =>
        assert(error.message.contains("did not provide any tools"), error.message)
        assert(error.message.contains("the `tools` of the predictor"), error.message)
        assert(error.message.contains("native function calling off"), error.message)
        assert(!error.message.contains("by adding an input field"), error.message)
      case other => fail(s"expected a configuration error, got $other")
  }

  // ── A tool turn that the check of the caller rejects ──

  private final class ToolTurnLm(reply: LmOutput) extends LanguageModel:
    @volatile var requests: Int                                                                = 0
    override val id: String                                                                    = "tool-turn"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsFunctionCalling: Boolean                                              = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      synchronized { requests += 1 }
      Right(LmResponse(outputs = Vector(reply)))

  private final case class WithEvidence(delegate: Adapter) extends DelegatingAdapter:
    override protected def wrap(next: Adapter): Adapter = WithEvidence(next)
    override def parse(invocation: AdapterInvocation, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      delegate.parse(invocation, output).map(_.copy(evidence = Vector("urgent" -> DecisionEvidence.Noul(0.5))))

  test("a tool turn that the check rejects gets the calls, each default, and a null, and it keeps the evidence") {
    val call   = ToolCall("weather", DynamicValues.record("city" := "Oslo"))
    val layout = SignatureLayout.create(
      name = "Manual",
      inputFields = Vector(FieldSpec("question")),
      outputFields = Vector(
        FieldSpec("tags"),
        FieldSpec("limit", typeRef = TypeRef.int, defaultValue = Some(DynamicValues.fromAny(7))),
        FieldSpec("outputs", typeRef = TypeRef.toolCalls)
      )
    ).fold(error => fail(error.toString), identity)
    val lm                             = ToolTurnLm(LmOutput(text = "[[ ## tags ## ]]\nbad\n\n[[ ## limit ## ]]\n3", toolCalls = Vector(call)))
    given RuntimeContext               = RuntimeContext(lm = Some(lm))
    val check: AdapterCall.OutputCheck = values =>
      Either.cond(DynamicValues.recordGet(values, "tags").contains(DynamicValue.Null), (), ValidationError("bad tags"))
    val parsed = AdapterCall.run(WithEvidence(ChatAdapter(native = nativeOn)), lm, invocationOf(layout), check)
      .fold(error => fail(error.toString), _.parsed)
    assertEquals(
      parsed.map(_.values),
      Vector(DynamicValues.recordFromEntries(Vector(
        "tags"    -> DynamicValue.Null,
        "limit"   -> DynamicValues.fromAny(7),
        "outputs" -> NativeFunctionCalling.encodeToolCalls(Vector(call))
      )))
    )
    assertEquals(parsed.flatMap(_.evidence), Vector("urgent" -> DecisionEvidence.Noul(0.5)))
    assertEquals(lm.requests, 1)
  }
