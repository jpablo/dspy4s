package dspy4s.adapters

import dspy4s.adapters.contracts.{AdapterInvocation, FieldChunk, NativeTools}
import dspy4s.core.contracts.{
  :=,
  DspyError,
  DynamicValues,
  FieldSpec,
  ParseError,
  RuntimeContext,
  SignatureLayout,
  ToolCall,
  TypeRef
}
import dspy4s.core.data.Example
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** The bugs of the code review of the adapters, the parsers, and the streaming states: each test failed before its fix.
  */
class AdapterParsersReviewSuite extends FunSuite:

  private given RuntimeContext = RuntimeContext()

  private def layout(outputs: FieldSpec*): SignatureLayout =
    SignatureLayout.of(name = "Sig", inputFields = Vector(FieldSpec("question")), outputFields = outputs.toVector, None)

  private val answer    = FieldSpec("answer")
  private val count     = FieldSpec("count", typeRef = TypeRef.int)
  private val toolCalls = FieldSpec("tool_calls", typeRef = TypeRef.toolCalls)
  private val call      = ToolCall(name = "search", args = DynamicValues.record("q" := "x"), id = Some("call_1"))

  private def int(n: Int): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Int(n))

  private def valueOf(result: Either[DspyError, dspy4s.adapters.contracts.ParsedOutput], name: String) =
    result.map(parsed => DynamicValues.recordGet(parsed.values, name))

  private def collect(chunks: Vector[FieldChunk]): Map[String, String] =
    chunks.groupMapReduce(_.fieldName)(_.text)(_ + _)

  // ── XML ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("an XML reply with a syntax error is a parse error, and the XML adapter falls back to the JSON adapter") {
    val parsed = XMLAdapter().parse(layout(answer), LmOutput(text = "<answer>\nAT&T\n</answer>"))
    assert(parsed.left.exists(_.isInstanceOf[ParseError]), parsed.toString)
    XMLAdapter().fallback match
      case Some(_: JSONAdapter) => ()
      case other                => fail(s"expected the JSON adapter as the fallback, got $other")
  }

  test("an XML field is a top-level tag, not a nested tag") {
    val text = "<reasoning>I'd write <answer>4</answer> here</reasoning>\n<answer>5</answer>"
    val got  = XMLAdapter().parse(layout(FieldSpec("reasoning"), answer), LmOutput(text = text))
    assertEquals(valueOf(got, "answer"), Right(Some(DynamicValues.string("5"))))
  }

  test("an XML value is stripped with the Python rule") {
    val got = XMLAdapter().parse(layout(answer), LmOutput(text = "<answer> Paris </answer>"))
    assertEquals(valueOf(got, "answer"), Right(Some(DynamicValues.string("Paris"))))
  }

  // ── The tool turn ──────────────────────────────────────────────────────────────────────────────────────────────

  test("provider tool calls with no tool-calls field are no tool turn: a missing required field is an error") {
    val parsed = ChatAdapter().parse(layout(count), LmOutput(text = "", toolCalls = Vector(call)))
    assert(parsed.isLeft, parsed.toString)
  }

  test("a tool turn with text that does not parse gives the defaults, in the chat and the XML formats") {
    val sig     = layout(count, toolCalls)
    val outputs = Vector(
      ChatAdapter() -> "[[ ## count ## ]]\nnot a number",
      XMLAdapter()  -> "<count>not a number</count>",
      JSONAdapter() -> """{"count": "not a number"}"""
    )
    outputs.foreach { (adapter, text) =>
      val parsed = adapter.parse(sig, LmOutput(text = text, toolCalls = Vector(call)))
      assertEquals(valueOf(parsed, "count"), Right(Some(DynamicValue.Null)), adapter.name)
      assert(valueOf(parsed, "tool_calls").exists(_.isDefined), s"${adapter.name}: $parsed")
    }
  }

  // ── The single-output fallback ─────────────────────────────────────────────────────────────────────────────────

  test("the single-output text fallback coerces the value in each format") {
    Vector(ChatAdapter(), JSONAdapter(), XMLAdapter()).foreach { adapter =>
      val parsed = adapter.parse(layout(count), LmOutput(text = "42"))
      assertEquals(valueOf(parsed, "count"), Right(Some(int(42))), adapter.name)
    }
  }

  // ── JSON object rule ───────────────────────────────────────────────────────────────────────────────────────────

  test("the JSON parser takes the first JSON object, as the JSON stream and upstream do") {
    val text   = "Draft {\"answer\": \"x\"} then\n```json\n{\"answer\": \"Paris\"}\n```"
    val parsed = JSONAdapter().parse(layout(answer), LmOutput(text = text))
    assertEquals(valueOf(parsed, "answer"), Right(Some(DynamicValues.string("x"))))
    val stream = JSONAdapter().streamingState(layout(answer)).getOrElse(fail("no stream state"))
    val chunks = stream.receive(text) ++ stream.finish()
    assertEquals(collect(chunks).get("answer"), Some("x"))
  }

  test("the JSON stream does not track the tool-calls field when the provider fills it") {
    val model = new LanguageModel:
      override val id: String                                                                    = "tools"
      override val mode: LmMode                                                                  = LmMode.Chat
      override def supportsFunctionCalling: Boolean                                              = true
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        Left(dspy4s.core.contracts.RuntimeError("test", "not called"))
    val context = RuntimeContext(lm = Some(model))
    val adapter = JSONAdapter(native = NativeTools(enabled = true))
    val stream  = adapter.streamingState(layout(answer, toolCalls))(using context).getOrElse(fail("no stream state"))
    val chunks  = stream.receive("""{"answer": "x", "tool_calls": [{"name": "a", "args": {}}]}""") ++ stream.finish()
    assertEquals(collect(chunks).keySet, Set("answer"))
  }

  // ── The chat stream ────────────────────────────────────────────────────────────────────────────────────────────

  test("the chat stream holds back a long header of another field across two chunks") {
    val state  = new ChatStreamingState(Vector(answer))
    val chunks = state.receive("[[ ## answer ## ]]\nhi\n[[ ## conversation_hist") ++
      state.receive("ory ## ]]\nmore\n[[ ## completed ## ]]") ++ state.finish()
    assertEquals(collect(chunks).get("answer"), Some("hi"))
  }

  // ── TwoStep ────────────────────────────────────────────────────────────────────────────────────────────────────

  test("an empty reply of the extraction model is a parse error of the original completion") {
    val empty = new LanguageModel:
      override val id: String                                                                    = "empty"
      override val mode: LmMode                                                                  = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        Right(LmResponse(outputs = Vector.empty))
    val invocation = AdapterInvocation(
      layout = layout(answer),
      demos = Vector.empty,
      inputs = Example(values = DynamicValues.record("question" := "q"), inputKeys = Set("question")),
      request = LmRequest(model = "main")
    )
    TwoStepAdapter(empty).parse(invocation, LmOutput(text = "The answer is Paris.")) match
      case Left(error: ParseError) =>
        assert(error.message.startsWith("Failed to parse response from the original completion"), error.message)
        assertEquals(error.raw, Some("The answer is Paris."))
      case other => fail(s"expected a parse error, got $other")
  }
