package dspy4s.adapters

import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.ParsedOutput
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
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** A reply of the main model with provider tool calls (upstream `Adapter._call_postprocess`): the calls fill the tool
  * calls field. With no text, no extraction runs. With text, the extraction gives the other fields, and a parse error
  * of the extraction gives no text values. The model has such calls when its default options have tools, because
  * [[TwoStepAdapter]] removes the tool options of a call.
  */
class TwoStepToolTurnSuite extends FunSuite:

  private final class Lm(replies: LmOutput*) extends LanguageModel:
    private var responseSchema: Boolean          = false
    val requests: ArrayBuffer[LmRequest]         = ArrayBuffer.empty
    override val id: String                      = "scripted"
    override val mode: LmMode                    = LmMode.Chat
    override def supportsResponseSchema: Boolean = responseSchema
    def withResponseSchema: Lm                   =
      responseSchema = true
      this
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests += request
      replies.lift(requests.size - 1) match
        case Some(reply) => Right(LmResponse(outputs = Vector(reply)))
        case None        => Left(RuntimeError("scripted", s"no reply for call ${requests.size}"))

  private val call: ToolCall = ToolCall("search", DynamicValues.record("query" := "belgium"))

  private val toolLayout: SignatureLayout = SignatureLayout.create(
    name = "Plan",
    inputFields = Vector(FieldSpec("question")),
    outputFields = Vector(FieldSpec("answer"), FieldSpec("outputs", typeRef = TypeRef.toolCalls))
  ).fold(error => fail(error.toString), identity)

  /** Two text fields, so a reply with no markers is a parse error. With one text field, the single-output rule of the
    * chat adapter takes the whole text as its value.
    */
  private val twoTextFields: SignatureLayout = SignatureLayout.create(
    name = "PlanWithNote",
    inputFields = Vector(FieldSpec("question")),
    outputFields = Vector(FieldSpec("answer"), FieldSpec("note"), FieldSpec("outputs", typeRef = TypeRef.toolCalls))
  ).fold(error => fail(error.toString), identity)

  private val callsOnly: SignatureLayout = SignatureLayout.create(
    name = "Calls",
    inputFields = Vector(FieldSpec("question")),
    outputFields = Vector(FieldSpec("outputs", typeRef = TypeRef.toolCalls))
  ).fold(error => fail(error.toString), identity)

  private val plainLayout: SignatureLayout = SignatureLayout.create(
    name = "Plain",
    inputFields = Vector(FieldSpec("question")),
    outputFields = Vector(FieldSpec("answer"))
  ).fold(error => fail(error.toString), identity)

  private def invocation(layout: SignatureLayout): AdapterInvocation =
    AdapterInvocation(
      layout = layout,
      demos = Vector.empty,
      inputs = Example(values = DynamicValues.record("question" := "Q?"), inputKeys = Set("question")),
      request = LmRequest(model = "main")
    )

  private def field(parsed: ParsedOutput, name: String): DynamicValue =
    DynamicValues.recordGet(parsed.values, name).getOrElse(fail(s"no field $name in ${parsed.values}"))

  private def assertHasCall(parsed: ParsedOutput): Unit =
    val rendered = DynamicValues.renderText(field(parsed, "outputs"))
    assert(rendered.contains("search"), rendered)
    assert(rendered.contains("belgium"), rendered)

  test("provider tool calls and no text fill the tool calls field, and no extraction runs") {
    given RuntimeContext = RuntimeContext()
    val extraction       = Lm()
    val adapter          = TwoStepAdapter(extraction)

    val parsed = adapter.parse(invocation(toolLayout), LmOutput(text = "", toolCalls = Vector(call)))
      .fold(error => fail(error.toString), identity)

    assertEquals(extraction.requests.size, 0)
    assertHasCall(parsed)
    assertEquals(field(parsed, "answer"), DynamicValue.Null)
  }

  test("provider tool calls and text fill the tool calls field, and the extraction gives the other fields") {
    given RuntimeContext = RuntimeContext()
    val extraction       =
      Lm(LmOutput(text = "[[ ## answer ## ]]\nParis\n\n[[ ## outputs ## ]]\n[]\n\n[[ ## completed ## ]]"))
    val adapter = TwoStepAdapter(extraction)

    val parsed = adapter.parse(invocation(toolLayout), LmOutput(text = "It is Paris.", toolCalls = Vector(call)))
      .fold(error => fail(error.toString), identity)

    assertEquals(extraction.requests.size, 1)
    assertHasCall(parsed)
    assertEquals(field(parsed, "answer"), DynamicValues.string("Paris"))
  }

  test("a parse error of the extraction on a tool turn gives no text values, and the calls stay") {
    given RuntimeContext = RuntimeContext()
    val extraction       = Lm(LmOutput(text = "no markers"), LmOutput(text = "no markers"))
    val adapter          = TwoStepAdapter(extraction)

    val parsed = adapter.parse(invocation(twoTextFields), LmOutput(text = "It is Paris.", toolCalls = Vector(call)))
      .fold(error => fail(error.toString), identity)

    assertEquals(extraction.requests.size, 2, "the chat attempt and its JSON fallback")
    assertHasCall(parsed)
    assertEquals(field(parsed, "answer"), DynamicValue.Null)
    assertEquals(field(parsed, "note"), DynamicValue.Null)
  }

  test("one text field on a tool turn takes a reply with no markers, as with no tool turn") {
    given RuntimeContext = RuntimeContext()
    val extraction       = Lm(LmOutput(text = "no markers"))
    val adapter          = TwoStepAdapter(extraction)

    val parsed = adapter.parse(invocation(toolLayout), LmOutput(text = "It is Paris.", toolCalls = Vector(call)))
      .fold(error => fail(error.toString), identity)

    assertEquals(extraction.requests.size, 1)
    assertHasCall(parsed)
    assertEquals(field(parsed, "answer"), DynamicValues.string("no markers"))
  }

  test("text and a layout with only a tool calls field give no extraction") {
    given RuntimeContext = RuntimeContext()
    val extraction       = Lm()
    val adapter          = TwoStepAdapter(extraction)

    val parsed = adapter.parse(invocation(callsOnly), LmOutput(text = "Calling.", toolCalls = Vector(call)))
      .fold(error => fail(error.toString), identity)

    assertEquals(extraction.requests.size, 0)
    assertHasCall(parsed)
  }

  test("the extraction of a tool turn does not ask for the tool calls field in a response schema") {
    given RuntimeContext = RuntimeContext()
    val extraction       = Lm(LmOutput(text = "no markers"), LmOutput(text = "no markers")).withResponseSchema
    val adapter          = TwoStepAdapter(extraction)
    val property         = DynamicValues.record("type" := "string")
    val schema           = DynamicValues.record(
      "type"       := "object",
      "properties" -> DynamicValues.record("answer" -> property, "note" -> property, "outputs" -> property),
      "required"   := List("answer", "note", "outputs")
    )

    val parsed = adapter.parse(
      invocation(twoTextFields).copy(outputJsonSchema = Some(schema)),
      LmOutput(text = "It is Paris.", toolCalls = Vector(call))
    ).fold(error => fail(error.toString), identity)

    assertHasCall(parsed)
    val format = extraction.requests.flatMap(request => DynamicValues.recordGet(request.options, "response_format"))
    assertEquals(format.size, 1, "the JSON fallback has the response schema")
    val text = DynamicValues.renderText(format.head)
    assert(text.contains("answer"), text)
    assert(!text.contains("outputs"), text)
  }

  test("an error of the extraction model on a tool turn that is not a parse error stays an error") {
    given RuntimeContext = RuntimeContext()
    val adapter          = TwoStepAdapter(Lm())

    val result = adapter.parse(invocation(toolLayout), LmOutput(text = "It is Paris.", toolCalls = Vector(call)))

    assert(result.left.exists(_.isInstanceOf[RuntimeError]), result.toString)
  }

  test("the full adapter call gives the calls of a main reply with no text") {
    given RuntimeContext = RuntimeContext()
    val main             = Lm(LmOutput(text = "", toolCalls = Vector(call)))
    val extraction       = Lm()

    val answer = AdapterCall.run(TwoStepAdapter(extraction), main, invocation(toolLayout))
      .fold(error => fail(error.toString), identity)

    assertEquals(main.requests.size, 1)
    assertEquals(extraction.requests.size, 0)
    assertHasCall(answer.parsed.head)
  }

  test(
    "provider tool calls with no tool calls field are no tool turn, so a reply with text still gets the extraction"
  ) {
    given RuntimeContext = RuntimeContext()
    val extraction       = Lm(LmOutput(text = "[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"))
    val adapter          = TwoStepAdapter(extraction)

    val parsed = adapter.parse(invocation(plainLayout), LmOutput(text = "It is Paris.", toolCalls = Vector(call)))
      .fold(error => fail(error.toString), identity)

    assertEquals(extraction.requests.size, 1)
    assertEquals(field(parsed, "answer"), DynamicValues.string("Paris"))
  }

  test("a parse error of the extraction with no tool turn stays an error") {
    given RuntimeContext = RuntimeContext()
    val extraction       = Lm(LmOutput(text = "no markers"), LmOutput(text = "no markers"))
    val adapter          = TwoStepAdapter(extraction)

    val result = adapter.parse(invocation(toolLayout), LmOutput(text = "It is Paris."))

    assert(result.left.exists(_.isInstanceOf[ParseError]), result.toString)
  }
