package dspy4s.adapters

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, NativeTools, ToolSpec}
import dspy4s.core.contracts.{
  :=, DspyError, DynamicValues, FieldSpec, ParseError, PythonText, RuntimeContext, SignatureLayout, ToolCall, TypeRef,
  ValidationError
}
import dspy4s.core.data.Example
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, MessageRole}
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** The native reasoning step of an adapter call (upstream `Reasoning.adapt_to_native_lm_feature` and
  * `Reasoning.parse_lm_response`, with `Adapter._call_preprocess` and `Adapter._call_postprocess`, and the tests of
  * `tests/adapters/test_chat_adapter.py`, `test_json_adapter.py`, and `test_reasoning.py`).
  */
class NativeReasoningSuite extends FunSuite:

  private final class Script(
      modelId        : String              = "m",
      modelMode      : LmMode              = LmMode.Chat,
      reasoning      : Boolean             = true,
      defaults       : DynamicValue.Record = DynamicValue.Record.empty,
      functionCalling: Boolean             = false,
      responseSchema : Boolean             = false
  )(replies: LmResponse*) extends LanguageModel:
    val requests: ArrayBuffer[LmRequest]                                                       = ArrayBuffer.empty
    override val id: String                                                                    = modelId
    override val mode: LmMode                                                                  = modelMode
    override def supportsReasoning: Boolean                                                    = reasoning
    override def supportsFunctionCalling: Boolean                                              = functionCalling
    override def supportsResponseSchema: Boolean                                               = responseSchema
    override def defaultOptions: DynamicValue.Record                                           = defaults
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests += request
      Right(replies(requests.size - 1))

  private def reply(text: String, reasoning: String = ""): LmResponse =
    LmResponse(outputs = Vector(LmOutput(text = text, reasoning = reasoning)))

  private def layoutOf(outputs: FieldSpec*): SignatureLayout =
    SignatureLayout.create(name = "Q", inputFields = Vector(FieldSpec("question")), outputFields = outputs.toVector)
      .fold(error => fail(error.toString), identity)

  private def reasoningField: FieldSpec = FieldSpec("reasoning", typeRef = TypeRef.reasoning)

  private def answerField: FieldSpec = FieldSpec("answer")

  private def layout: SignatureLayout = layoutOf(reasoningField, answerField)

  private def invocationOf(
      layout : SignatureLayout,
      options: DynamicValue.Record         = DynamicValue.Record.empty,
      schema : Option[DynamicValue.Record] = None,
      demos  : Vector[Example]             = Vector.empty,
      tools  : Vector[ToolSpec]            = Vector.empty
  ): AdapterInvocation =
    AdapterInvocation(
      layout = layout,
      demos = demos,
      inputs = Example(values = DynamicValues.record("question" := "Q?"), inputKeys = Set("question")),
      request = LmRequest(model = "m", options = options),
      outputJsonSchema = schema,
      tools = tools
    )

  private def run(
      adapter    : Adapter,
      model      : LanguageModel,
      invocation : AdapterInvocation,
      checkOutput: AdapterCall.OutputCheck = AdapterCall.acceptAnyOutput
  ): Either[DspyError, AdapterCall.Answer] =
    AdapterCall.run(adapter, model, invocation, checkOutput)(using RuntimeContext(lm = Some(model)))

  private def values(result: Either[DspyError, AdapterCall.Answer]): DynamicValue.Record =
    result.fold(error => fail(error.toString), _.parsed.head.values)

  private def entries(record: DynamicValue.Record): Vector[(String, Option[String])] =
    record.fields.toVector.map((name, value) => name -> DynamicValues.asString(value))

  private def effort(request: LmRequest): Option[DynamicValue] =
    DynamicValues.recordGet(request.options, "reasoning_effort")

  private def low: Option[DynamicValue] = Some(DynamicValues.string("low"))

  private def promptOf(request: LmRequest): String = request.messages.flatMap(_.text).mkString("\n")

  private def systemOf(request: LmRequest): String =
    request.messages.find(_.role == MessageRole.System).flatMap(_.text).getOrElse("")

  private def chatAnswer: String = "[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"

  private def chatBoth: String =
    "[[ ## reasoning ## ]]\nwritten\n\n[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"

  // ── A model with reasoning ────────────────────────────────────────────────────────────────────────────────────

  test("a model with reasoning gets a prompt with no reasoning field, and the effort low") {
    val model = Script()(reply(chatAnswer, "why"))
    val _     = run(ChatAdapter(), model, invocationOf(layout))
    val sent  = model.requests.head
    assertEquals(
      systemOf(sent),
      "Your input fields are:\n1. `question` (str):\nYour output fields are:\n1. `answer` (str):\n" +
        "All interactions will be structured in the following way, with the appropriate values filled in.\n\n" +
        "[[ ## question ## ]]\n{question}\n\n[[ ## answer ## ]]\n{answer}\n\n[[ ## completed ## ]]\n" +
        "In adhering to this structure, your objective is: \n" +
        "        Given the fields `question`, produce the fields `reasoning`, `answer`."
    )
    assertEquals(
      sent.messages.last.text,
      Some(
        "[[ ## question ## ]]\nQ?\n\nRespond with the corresponding output fields, starting with the field " +
          "`[[ ## answer ## ]]`, and then ending with the marker for `[[ ## completed ## ]]`."
      )
    )
    assertEquals(effort(sent), low)
  }

  test("the reasoning of the reply fills the field, in the order of the layout") {
    val model = Script()(reply(chatAnswer, "why"))
    val found = values(run(ChatAdapter(), model, invocationOf(layout)))
    assertEquals(entries(found), Vector("reasoning" -> Some("why"), "answer" -> Some("Paris")))
    assertEquals(model.requests.size, 1)
  }

  test("each output of a reply has its own reasoning") {
    val model = Script()(
      LmResponse(outputs = Vector(LmOutput(chatAnswer, reasoning = "one"), LmOutput(chatAnswer, reasoning = "two")))
    )
    val parsed = run(ChatAdapter(), model, invocationOf(layout)).fold(error => fail(error.toString), _.parsed)
    assertEquals(
      parsed.map(output => DynamicValues.recordGet(output.values, "reasoning").flatMap(DynamicValues.asString)),
      Vector(Some("one"), Some("two"))
    )
  }

  test("two reasoning fields get the same reasoning") {
    val model = Script()(reply(chatAnswer, "why"))
    val found = values(run(
      ChatAdapter(),
      model,
      invocationOf(layoutOf(reasoningField, answerField, FieldSpec("second", typeRef = TypeRef.reasoning)))
    ))
    assertEquals(
      entries(found),
      Vector("reasoning" -> Some("why"), "answer" -> Some("Paris"), "second" -> Some("why"))
    )
    assert(!promptOf(model.requests.head).contains("[[ ## second ## ]]"), promptOf(model.requests.head))
  }

  test("a reply with no reasoning gives an empty text, and no second call") {
    val model = Script()(reply(chatAnswer))
    val found = values(run(ChatAdapter(), model, invocationOf(layout)))
    assertEquals(entries(found), Vector("reasoning" -> Some(""), "answer" -> Some("Paris")))
    assertEquals(model.requests.size, 1)
  }

  test("a reply with no reasoning gives the default of the field") {
    val model = Script()(reply(chatAnswer))
    val shown = reasoningField.copy(defaultValue = Some(DynamicValues.string("(omitted)")))
    val found = values(run(ChatAdapter(), model, invocationOf(layoutOf(answerField, shown))))
    assertEquals(entries(found), Vector("answer" -> Some("Paris"), "reasoning" -> Some("(omitted)")))
  }

  test("the check of the output gets the reasoning") {
    val seen  = ArrayBuffer.empty[Vector[String]]
    val model = Script()(reply(chatAnswer, "why"))
    val _     = run(
      ChatAdapter(),
      model,
      invocationOf(layout),
      record =>
        seen += DynamicValues.recordKeys(record).toVector
        Right(())
    )
    assertEquals(seen.toVector, Vector(Vector("reasoning", "answer")))
  }

  // ── The effort ────────────────────────────────────────────────────────────────────────────────────────────────

  test("the effort of the request stays") {
    val model   = Script()(reply(chatAnswer, "why"))
    val options = DynamicValues.record("reasoning_effort" := "high")
    val _       = run(ChatAdapter(), model, invocationOf(layout, options))
    assertEquals(effort(model.requests.head), Some(DynamicValues.string("high")))
    assert(!promptOf(model.requests.head).contains("[[ ## reasoning ## ]]"))
  }

  test("the effort of the model goes to the request") {
    val model = Script(defaults = DynamicValues.record("reasoning_effort" := "medium"))(reply(chatAnswer, "why"))
    val _     = run(ChatAdapter(), model, invocationOf(layout))
    assertEquals(effort(model.requests.head), Some(DynamicValues.string("medium")))
  }

  test("the effort of the request wins over the effort of the model") {
    val model   = Script(defaults = DynamicValues.record("reasoning_effort" := "medium"))(reply(chatAnswer, "why"))
    val options = DynamicValues.record("reasoning_effort" := "high")
    val _       = run(ChatAdapter(), model, invocationOf(layout, options))
    assertEquals(effort(model.requests.head), Some(DynamicValues.string("high")))
  }

  test("a null effort of the request keeps the text field, also over an effort of the model") {
    val model   = Script(defaults = DynamicValues.record("reasoning_effort" := "medium"))(reply(chatBoth))
    val options = DynamicValues.recordFromEntries(Vector("reasoning_effort" -> DynamicValue.Null))
    val found   = values(run(ChatAdapter(), model, invocationOf(layout, options)))
    assert(promptOf(model.requests.head).contains("[[ ## reasoning ## ]]"), promptOf(model.requests.head))
    assertEquals(effort(model.requests.head), Some(DynamicValue.Null))
    assertEquals(entries(found), Vector("reasoning" -> Some("written"), "answer" -> Some("Paris")))
  }

  test("a null effort of the model keeps the text field") {
    val nullEffort = DynamicValues.recordFromEntries(Vector("reasoning_effort" -> DynamicValue.Null))
    val model      = Script(defaults = nullEffort)(reply(chatBoth))
    val _          = run(ChatAdapter(), model, invocationOf(layout))
    assert(promptOf(model.requests.head).contains("[[ ## reasoning ## ]]"), promptOf(model.requests.head))
    assertEquals(effort(model.requests.head), None)
  }

  test("a gpt-5 model on the chat API keeps the text field") {
    val model = Script(modelId = "openai/gpt-5-mini")(reply(chatBoth))
    val _     = run(ChatAdapter(), model, invocationOf(layout))
    assert(promptOf(model.requests.head).contains("[[ ## reasoning ## ]]"), promptOf(model.requests.head))
    assertEquals(effort(model.requests.head), None)
  }

  test("a gpt-5 model on the Responses API uses the native reasoning") {
    val model = Script(modelId = "openai/gpt-5-mini", modelMode = LmMode.Responses)(reply(chatAnswer, "why"))
    val found = values(run(ChatAdapter(), model, invocationOf(layout)))
    assert(!promptOf(model.requests.head).contains("[[ ## reasoning ## ]]"), promptOf(model.requests.head))
    assertEquals(effort(model.requests.head), low)
    assertEquals(entries(found), Vector("reasoning" -> Some("why"), "answer" -> Some("Paris")))
  }

  // ── A model with no reasoning ─────────────────────────────────────────────────────────────────────────────────

  test("a model with no reasoning gets the text field, as a str, with the type hint in the reminder") {
    val model = Script(reasoning = false)(reply(chatBoth))
    val found = values(run(ChatAdapter(), model, invocationOf(layout)))
    val sent  = model.requests.head
    assertEquals(effort(sent), None)
    assert(systemOf(sent).contains("1. `reasoning` (str):"), systemOf(sent))
    assert(!systemOf(sent).contains("# note:"), systemOf(sent))
    assert(
      sent.messages.last.text.exists(
        _.contains("`[[ ## reasoning ## ]]` (must be formatted as a valid Python str)")
      ),
      sent.messages.last.text.toString
    )
    assertEquals(entries(found), Vector("reasoning" -> Some("written"), "answer" -> Some("Paris")))
  }

  test("the reasoning of the reply replaces the text of the field") {
    val model = Script(reasoning = false)(reply(chatBoth, "native"))
    val found = values(run(ChatAdapter(), model, invocationOf(layout)))
    assertEquals(entries(found), Vector("reasoning" -> Some("native"), "answer" -> Some("Paris")))
  }

  test("a text field with no text is a parse error, also when the reply has reasoning") {
    val model = Script(reasoning = false)(reply(chatAnswer, "native"))
    run(ChatAdapter(useJsonAdapterFallback = false), model, invocationOf(layout)) match
      case Left(_: ParseError) => ()
      case other               => fail(s"expected a parse error, got $other")
  }

  // ── An optional field and a text field ────────────────────────────────────────────────────────────────────────

  test("an optional field stays a text field, and the reply does not replace it") {
    val model = Script()(reply(chatBoth, "native"))
    val found =
      values(run(ChatAdapter(), model, invocationOf(layoutOf(reasoningField.copy(optional = true), answerField))))
    assert(promptOf(model.requests.head).contains("[[ ## reasoning ## ]]"), promptOf(model.requests.head))
    assertEquals(effort(model.requests.head), None)
    assertEquals(entries(found), Vector("reasoning" -> Some("written"), "answer" -> Some("Paris")))
  }

  test("a field of the type string stays a text field on a model with reasoning") {
    val model = Script()(reply(chatBoth, "native"))
    val found = values(run(ChatAdapter(), model, invocationOf(layoutOf(FieldSpec("reasoning"), answerField))))
    assert(promptOf(model.requests.head).contains("[[ ## reasoning ## ]]"), promptOf(model.requests.head))
    assertEquals(effort(model.requests.head), None)
    assertEquals(entries(found), Vector("reasoning" -> Some("written"), "answer" -> Some("Paris")))
  }

  // ── The empty reply and the fallback ──────────────────────────────────────────────────────────────────────────

  test("a reply with only reasoning is an empty reply, and the fallback has the same plan") {
    val model = Script()(reply("", "why"), reply("""{"answer": "Paris"}""", "because"))
    val found = values(run(ChatAdapter(), model, invocationOf(layout)))
    assertEquals(model.requests.size, 2)
    assertEquals(model.requests.map(effort).toVector, Vector(low, low))
    assert(!promptOf(model.requests(1)).contains("`reasoning`:"), promptOf(model.requests(1)))
    assertEquals(entries(found), Vector("reasoning" -> Some("because"), "answer" -> Some("Paris")))
  }

  test("a reply with only reasoning and no fallback is the parse error of an empty reply") {
    val model = Script()(reply("", "why"))
    run(ChatAdapter(useJsonAdapterFallback = false), model, invocationOf(layout)) match
      case Left(error: ParseError) => assertEquals(error.message, "The LM returned an empty or null response.")
      case other                   => fail(s"expected a parse error, got $other")
  }

  test("a signature with only a reasoning output asks for no output field") {
    val model = Script()(reply("[[ ## completed ## ]]", "why"))
    val found = values(run(ChatAdapter(), model, invocationOf(layoutOf(reasoningField))))
    assertEquals(entries(found), Vector("reasoning" -> Some("why")))
    assertEquals(model.requests.size, 1)
  }

  // ── The JSON, XML, and two-step adapters ──────────────────────────────────────────────────────────────────────

  private def outputSchema: DynamicValue.Record =
    PythonText.parseJson(
      """{"type": "object", "properties": {"reasoning": {"type": "string"}, "answer": {"type": "string"}},
        |"required": ["reasoning", "answer"], "additionalProperties": false}""".stripMargin
    ) match
      case Right(record: DynamicValue.Record) => record
      case other                              => fail(s"no schema: $other")

  private def responseFormat(request: LmRequest): String =
    DynamicValues.recordGet(request.options, "response_format").fold("")(PythonText.json(_))

  test("the JSON adapter shows no reasoning field in the prompt and in the schema of the signature") {
    val model = Script(responseSchema = true)(reply("""{"answer": "Paris"}""", "why"))
    val found = values(run(JSONAdapter(), model, invocationOf(layout, schema = Some(outputSchema))))
    val sent  = model.requests.head
    assert(!promptOf(sent).contains("`reasoning`:"), promptOf(sent))
    assert(responseFormat(sent).contains("json_schema"), responseFormat(sent))
    assert(!responseFormat(sent).contains("reasoning"), responseFormat(sent))
    assertEquals(effort(sent), low)
    assertEquals(entries(found), Vector("reasoning" -> Some("why"), "answer" -> Some("Paris")))
  }

  test("the JSON adapter shows no reasoning field in the schema of the fields") {
    val model = Script(responseSchema = true)(reply("""{"answer": "Paris"}""", "why"))
    val _     = run(JSONAdapter(), model, invocationOf(layout))
    assert(responseFormat(model.requests.head).contains("json_schema"), responseFormat(model.requests.head))
    assert(!responseFormat(model.requests.head).contains("reasoning"), responseFormat(model.requests.head))
  }

  test("the JSON adapter keeps a text field in the schema of a model with no reasoning") {
    val model = Script(reasoning = false, responseSchema = true)(reply("""{"reasoning": "r", "answer": "a"}"""))
    val _     = run(JSONAdapter(), model, invocationOf(layout))
    assert(
      responseFormat(model.requests.head).contains("\"reasoning\": {\"type\": \"string\""),
      responseFormat(model.requests.head)
    )
  }

  test("the XML adapter shows no reasoning field") {
    val model = Script()(reply("<answer>Paris</answer>", "why"))
    val found = values(run(XMLAdapter(), model, invocationOf(layout)))
    assert(!promptOf(model.requests.head).contains("<reasoning>"), promptOf(model.requests.head))
    assertEquals(effort(model.requests.head), low)
    assertEquals(entries(found), Vector("reasoning" -> Some("why"), "answer" -> Some("Paris")))
  }

  test("the two-step adapter takes the reasoning of the main model, and the extraction has no such field") {
    val extraction = Script(reasoning = false)(reply(chatAnswer))
    val main       = Script()(reply("It is Paris.", "why"))
    val found      = values(run(TwoStepAdapter(extraction), main, invocationOf(layout)))
    assertEquals(effort(main.requests.head), low)
    assert(!promptOf(main.requests.head).contains("`reasoning` (str)"), promptOf(main.requests.head))
    assert(!promptOf(extraction.requests.head).contains("[[ ## reasoning ## ]]"), promptOf(extraction.requests.head))
    assertEquals(entries(found), Vector("reasoning" -> Some("why"), "answer" -> Some("Paris")))
  }

  test("the XML adapter reads a reply with no tags for a signature with only a reasoning output") {
    val model = Script()(reply("Paris", "why"))
    val found = values(run(XMLAdapter(), model, invocationOf(layoutOf(reasoningField))))
    assertEquals(entries(found), Vector("reasoning" -> Some("why")))
    assertEquals(model.requests.size, 1)
  }

  // ── A tool turn ───────────────────────────────────────────────────────────────────────────────────────────────

  private def toolLayout(extra: FieldSpec*): SignatureLayout =
    layoutOf((FieldSpec("next_thought", typeRef = TypeRef.reasoning) +: extra.toVector :+
      FieldSpec("tool_calls", typeRef = TypeRef.toolCalls))*)

  private def search: ToolSpec = ToolSpec("search", Some("Search."), DynamicValues.record("type" := "object"))

  private def call: ToolCall = ToolCall("search", DynamicValues.record("query" := "test"), Some("call_1"))

  private def toolReply(text: String): LmResponse =
    LmResponse(outputs = Vector(LmOutput(text = text, reasoning = "I need a result.", toolCalls = Vector(call))))

  test("native function calling and native reasoning work together") {
    val model = Script(functionCalling = true)(toolReply("I'll search for that now."))
    val found = values(run(
      ChatAdapter(native = NativeTools(enabled = true)),
      model,
      invocationOf(toolLayout(), tools = Vector(search))
    ))
    val sent = model.requests.head
    assertEquals(effort(sent), low)
    assert(DynamicValues.recordGet(sent.options, "tools").isDefined)
    assert(!promptOf(sent).contains("next_thought ##"), promptOf(sent))
    assertEquals(
      DynamicValues.recordGet(found, "next_thought").flatMap(DynamicValues.asString),
      Some("I need a result.")
    )
    assertEquals(DynamicValues.recordGet(found, "tool_calls").flatMap(ToolCall.fromDynamic), Some(Vector(call)))
  }

  test("a tool turn whose text the check rejects keeps the reasoning") {
    val model = Script(functionCalling = true)(toolReply("[[ ## count ## ]]\n7\n\n[[ ## completed ## ]]"))
    val found = values(run(
      ChatAdapter(native = NativeTools(enabled = true)),
      model,
      invocationOf(toolLayout(FieldSpec("count", typeRef = TypeRef.int)), tools = Vector(search)),
      record =>
        DynamicValues.recordGet(record, "count") match
          case Some(_: DynamicValue.Primitive) => Left(ValidationError("no number"))
          case _                               => Right(())
    ))
    assertEquals(
      DynamicValues.recordGet(found, "next_thought").flatMap(DynamicValues.asString),
      Some("I need a result.")
    )
    assertEquals(DynamicValues.recordGet(found, "count"), Some(DynamicValue.Null))
  }

  // ── The history and the demos ─────────────────────────────────────────────────────────────────────────────────

  private def demo: Example =
    Example(
      values = DynamicValues.record("question" := "Q1", "reasoning" := "Think", "answer" := "A1"),
      inputKeys = Set("question")
    )

  test("a demo shows no reasoning on a model with reasoning") {
    val model = Script()(reply(chatAnswer, "why"))
    val _     = run(ChatAdapter(), model, invocationOf(layout, demos = Vector(demo)))
    assert(promptOf(model.requests.head).contains("A1"), promptOf(model.requests.head))
    assert(!promptOf(model.requests.head).contains("Think"), promptOf(model.requests.head))
  }

  test("a demo shows the text of the reasoning on a model with no reasoning") {
    val model = Script(reasoning = false)(reply(chatBoth))
    val _     = run(ChatAdapter(), model, invocationOf(layout, demos = Vector(demo)))
    assert(promptOf(model.requests.head).contains("[[ ## reasoning ## ]]\nThink"), promptOf(model.requests.head))
  }

  // ── The value of a text field ─────────────────────────────────────────────────────────────────────────────────

  private def chatValue(text: String): Option[String] =
    val model = Script(reasoning =
      false
    )(reply(s"[[ ## reasoning ## ]]\n$text\n\n[[ ## answer ## ]]\na\n\n[[ ## completed ## ]]"))
    DynamicValues.recordGet(values(run(ChatAdapter(), model, invocationOf(layout))), "reasoning")
      .flatMap(DynamicValues.asString)

  test("a text value takes the form of upstream Reasoning.validate_input") {
    assertEquals(chatValue("plain words"), Some("plain words"))
    assertEquals(chatValue("""{"content": "from a record"}"""), Some("from a record"))
    assertEquals(chatValue("\"quoted\""), Some("quoted"))
    assertEquals(chatValue("""{"other": 1}"""), Some("""{"other": 1}"""))
    assertEquals(chatValue("[1, 2]"), Some("[1, 2]"))
    assertEquals(chatValue("123"), Some("123"))
    assertEquals(chatValue("""{"content": 7}"""), Some("""{"content": 7}"""))
  }

  test("a list of reasoning values takes the rule for each item") {
    val typed = FieldSpec("notes", typeRef = TypeRef.fromToken("list[dspy.Reasoning]"))
    val model = Script(reasoning = false)(
      reply("[[ ## notes ## ]]\n[{\"content\": \"x\"}, \"y\"]\n\n[[ ## completed ## ]]")
    )
    val found = values(run(ChatAdapter(), model, invocationOf(layoutOf(typed))))
    assertEquals(
      DynamicValues.recordGet(found, "notes"),
      Some(DynamicValues.sequence(Vector(DynamicValues.string("x"), DynamicValues.string("y"))))
    )
    val wrong = Script(reasoning = false)(reply("[[ ## notes ## ]]\n[1]\n\n[[ ## completed ## ]]"))
    run(ChatAdapter(useJsonAdapterFallback = false), wrong, invocationOf(layoutOf(typed))) match
      case Left(_: ParseError) => ()
      case other               => fail(s"expected a parse error, got $other")
  }

  private def jsonValue(text: String): Either[DspyError, AdapterCall.Answer] =
    val adapter = JSONAdapter()
    run(adapter, Script(reasoning = false)(reply(text), reply(text)), invocationOf(layout))

  test("a JSON value takes the form of upstream Reasoning.validate_input") {
    assertEquals(
      DynamicValues.recordGet(values(jsonValue("""{"reasoning": {"content": "x"}, "answer": "a"}""")), "reasoning"),
      Some(DynamicValues.string("x"))
    )
    assertEquals(
      DynamicValues.recordGet(values(jsonValue("""{"reasoning": "x", "answer": "a"}""")), "reasoning"),
      Some(DynamicValues.string("x"))
    )
  }

  test("a JSON value that is not a text or a record with a text content is a parse error") {
    Vector(
      """{"reasoning": 7, "answer": "a"}""",
      """{"reasoning": ["x"], "answer": "a"}""",
      """{"reasoning": {"other": "x"}, "answer": "a"}""",
      """{"reasoning": {"content": 7}, "answer": "a"}"""
    ).foreach { text =>
      jsonValue(text) match
        case Left(_: ParseError) => ()
        case other               => fail(s"expected a parse error for $text, got $other")
    }
  }
