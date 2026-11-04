package dspy4s.programs

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, NativeTools}
import dspy4s.adapters.{AdapterCall, ChatAdapter, JSONAdapter}
import dspy4s.core.contracts.{
  DspyError, DynamicValues, PythonText, Reasoning, RuntimeContext, SignatureLayout, ToolCall, :=
}
import dspy4s.core.data.Example
import dspy4s.core.decisions.{DecisionSettings, Noul}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.strategies.{ChainOfThought, DynamicPredict, Predict, ReActV2, Refine}
import dspy4s.programs.strategies.ReActV2.TerminationReason
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

import scala.collection.mutable.ArrayBuffer

// Top-level fixtures (Mirror derivation needs them outside the suite).
final case class NrIn(question: String) derives Schema
final case class NrAnswer(answer: String) derives Schema
final case class NrOut(reasoning: Reasoning, answer: String) derives Schema
final case class NrOptional(reasoning: Option[Reasoning], answer: String) derives Schema
final case class NrDefault(answer: String, reasoning: Reasoning = Reasoning("(omitted)")) derives Schema
final case class NrDecided(reasoning: Reasoning, urgent: Noul) derives Schema

/** A [[Reasoning]] output in the programs: `Predict`, `DynamicPredict`, `ChainOfThought` with the option of upstream
  * `rationale_field_type=dspy.Reasoning`, and `ReActV2`, which types `next_thought` as `dspy.Reasoning`.
  */
class NativeReasoningProgramsSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class Script(
      reasoning      : Boolean = true,
      functionCalling: Boolean = false,
      responseSchema : Boolean = false
  )(replies: LmOutput*) extends LanguageModel:
    val requests: ArrayBuffer[LmRequest]                                                       = ArrayBuffer.empty
    override val id: String                                                                    = "script"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsReasoning: Boolean                                                    = reasoning
    override def supportsFunctionCalling: Boolean                                              = functionCalling
    override def supportsResponseSchema: Boolean                                               = responseSchema
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests += request
      Right(LmResponse(outputs = Vector(replies.lift(requests.size - 1).getOrElse(LmOutput("")))))

  private def run[A](lm: LanguageModel, adapter: Adapter = ChatAdapter())(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(adapter))) {
      body(using RuntimeEnvironment.current)
    }

  private def promptOf(request: LmRequest): String = request.messages.flatMap(_.text).mkString("\n")

  private def effort(request: LmRequest): Option[DynamicValue] =
    DynamicValues.recordGet(request.options, "reasoning_effort")

  private def low: Option[DynamicValue] = Some(DynamicValues.string("low"))

  private def answerText: String = "[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"

  private def bothText: String =
    "[[ ## reasoning ## ]]\nwritten\n\n[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"

  // ── Predict ───────────────────────────────────────────────────────────────────────────────────────────────────

  test("a typed output gets the reasoning of the reply") {
    val model  = Script()(LmOutput(answerText, reasoning = "why"))
    val result = run(model)(Predict(Signature.derived[NrIn, NrOut](name = "Q"))(NrIn("Q?")))
    assertEquals(result.map(_.output), Right(NrOut(Reasoning("why"), "Paris")))
    assertEquals(model.requests.size, 1)
    assertEquals(effort(model.requests.head), low)
    assert(!promptOf(model.requests.head).contains("[[ ## reasoning ## ]]"), promptOf(model.requests.head))
  }

  test("a typed output gets the empty text when the reply has no reasoning, with no second call") {
    val model  = Script()(LmOutput(answerText))
    val result = run(model)(Predict(Signature.derived[NrIn, NrOut](name = "Q"))(NrIn("Q?")))
    assertEquals(result.map(_.output), Right(NrOut(Reasoning(""), "Paris")))
    assertEquals(model.requests.size, 1)
  }

  test("a typed output gets its default when the reply has no reasoning") {
    val model  = Script()(LmOutput(answerText))
    val result = run(model)(Predict(Signature.derived[NrIn, NrDefault](name = "Q"))(NrIn("Q?")))
    assertEquals(result.map(_.output), Right(NrDefault("Paris", Reasoning("(omitted)"))))
  }

  test("a typed output reads the text on a model with no reasoning") {
    val model  = Script(reasoning = false)(LmOutput(bothText))
    val result = run(model)(Predict(Signature.derived[NrIn, NrOut](name = "Q"))(NrIn("Q?")))
    assertEquals(result.map(_.output), Right(NrOut(Reasoning("written"), "Paris")))
    assertEquals(effort(model.requests.head), None)
  }

  test("an Option output stays a text field") {
    val model  = Script()(LmOutput(bothText, reasoning = "native"))
    val result = run(model)(Predict(Signature.derived[NrIn, NrOptional](name = "Q"))(NrIn("Q?")))
    assertEquals(result.map(_.output), Right(NrOptional(Some(Reasoning("written")), "Paris")))
    assertEquals(effort(model.requests.head), None)
  }

  test("the strict schema of a typed output has no reasoning property") {
    val model  = Script(responseSchema = true)(LmOutput("""{"answer": "Paris"}""", reasoning = "why"))
    val result = run(model, JSONAdapter())(Predict(Signature.derived[NrIn, NrOut](name = "Q"))(NrIn("Q?")))
    assertEquals(result.map(_.output), Right(NrOut(Reasoning("why"), "Paris")))
    val format = DynamicValues.recordGet(model.requests.head.options, "response_format").fold("")(PythonText.json(_))
    assert(format.contains("json_schema"), format)
    assert(!format.contains("reasoning"), format)
  }

  test("a string signature with the type token works on a dynamic predictor") {
    val layout = Signature.fromStringDynamic("question -> reasoning: dspy.Reasoning, answer")
      .fold(error => fail(error.message), _.layout)
    val model  = Script()(LmOutput(answerText, reasoning = "why"))
    val result = run(model)(DynamicPredict(layout = layout)(DynamicValues.record("question" := "Q?")))
    val output = result.fold(error => fail(error.message), _.output)
    assertEquals(DynamicValues.recordGet(output, "reasoning"), Some(DynamicValues.string("why")))
    assertEquals(DynamicValues.recordGet(output, "answer"), Some(DynamicValues.string("Paris")))
  }

  test("a decision output and a reasoning output work together") {
    val program = Predict(Signature.derived[NrIn, NrDecided](name = "Triage", instructions = "Triage."))
      .withDecision("urgent", DecisionSettings(instructions = Some(DynamicValues.string("Is it urgent?"))))
      .fold(error => fail(error.message), identity)
    val model  = Script()(LmOutput("[[ ## urgent ## ]]\n{\"noul\": 0.9}\n\n[[ ## completed ## ]]", reasoning = "why"))
    val result = run(model)(program(NrIn("Q?"))).fold(error => fail(error.message), _.output)
    assertEquals(result.reasoning.content, "why")
    assertEquals(result.urgent.value, true)
    assert(!promptOf(model.requests.head).contains("[[ ## reasoning ## ]]"), promptOf(model.requests.head))
    assertEquals(effort(model.requests.head), low)
  }

  test("the task examples of a decision output keep the reasoning of a demo") {
    val demo = Example(
      values = DynamicValues.record("question" := "Q1", "reasoning" := "Think"),
      inputKeys = Set("question")
    )
    val program =
      Predict(Signature.derived[NrIn, NrDecided](name = "Triage", instructions = "Triage."), demos = Vector(demo))
        .withDecision("urgent", DecisionSettings(instructions = Some(DynamicValues.string("Is it urgent?"))))
        .fold(error => fail(error.message), identity)
    val model = Script()(LmOutput("[[ ## urgent ## ]]\n{\"noul\": 0.9}\n\n[[ ## completed ## ]]", reasoning = "why"))
    val _     = run(model)(program(NrIn("Q?"))).fold(error => fail(error.message), _.output)
    val text  = promptOf(model.requests.head)
    assert(text.contains("Task examples"), text)
    assert(text.contains("Think"), text)
  }

  // ── Refine ────────────────────────────────────────────────────────────────────────────────────────────────────

  private def layoutOf(dsl: String): SignatureLayout =
    SignatureLayout.parse(dsl).fold(error => fail(error.toString), identity)

  private def invocationOf(layout: SignatureLayout): AdapterInvocation =
    AdapterInvocation(
      layout = layout,
      demos = Vector.empty,
      inputs = Example(values = DynamicValues.record("question" := "Q?"), inputKeys = Set("question")),
      request = LmRequest(model = "script")
    )

  private def hintPrompt(advice: Map[SignatureLayout, String], layout: SignatureLayout, reply: String): String =
    val model   = Script()(LmOutput(reply, reasoning = "why"))
    val adapter = Refine.HintInjectingAdapter(ChatAdapter(), advice)
    val _       = AdapterCall.run(adapter, model, invocationOf(layout))(using RuntimeContext(lm = Some(model)))
    promptOf(model.requests.head)

  test("the hint of Refine reaches a predictor with native reasoning") {
    val layout = layoutOf("question -> reasoning: dspy.Reasoning, answer")
    val text   = hintPrompt(Map(layout -> "Use the secret."), layout, answerText)
    assert(text.contains("hint_"), text)
    assert(text.contains("Use the secret."), text)
    assert(!text.contains("[[ ## reasoning ## ]]"), text)
  }

  test("two predictors that differ only by a reasoning output get their own hints") {
    val full  = layoutOf("question -> reasoning: dspy.Reasoning, answer")
    val plain = layoutOf("question -> answer")
    val both  = Map(full -> "For the full one.", plain -> "For the plain one.")
    val text  = hintPrompt(both, full, answerText)
    assert(text.contains("For the full one."), text)
    assert(!text.contains("For the plain one."), text)
  }

  test("the hint of Refine reaches a predictor with a decision output") {
    val base    = Signature.derived[NrIn, NrDecided](name = "Triage", instructions = "Triage.")
    val program = Predict(base)
      .withDecision("urgent", DecisionSettings(instructions = Some(DynamicValues.string("Is it urgent?"))))
      .fold(error => fail(error.message), identity)
    val advice = Refine.HintInjectingAdapter(ChatAdapter(), Map(base.layout -> "Use the secret."))
    val model  = Script()(LmOutput("[[ ## urgent ## ]]\n{\"noul\": 0.9}\n\n[[ ## completed ## ]]", reasoning = "why"))
    val _      = run(model, advice)(program(NrIn("Q?"))).fold(error => fail(error.message), _.output)
    assert(promptOf(model.requests.head).contains("Use the secret."), promptOf(model.requests.head))
  }

  // ── ChainOfThought ────────────────────────────────────────────────────────────────────────────────────────────

  private def base: Signature[NrIn, NrAnswer] =
    Signature.derived[NrIn, NrAnswer](name = "Q", instructions = "Answer.")

  test("by default, ChainOfThought asks for a text field on a model with reasoning") {
    val model  = Script()(LmOutput(bothText, reasoning = "native"))
    val result = run(model)(ChainOfThought(base)(NrIn("Q?"))).fold(error => fail(error.message), _.output)
    assertEquals(result.reasoning, "written")
    assertEquals(result.answer, "Paris")
    assertEquals(effort(model.requests.head), None)
    assert(promptOf(model.requests.head).contains("[[ ## reasoning ## ]]"), promptOf(model.requests.head))
  }

  test("ChainOfThought with native reasoning takes the reasoning of the reply") {
    val model  = Script()(LmOutput(answerText, reasoning = "native"))
    val result = run(model)(ChainOfThought(base, nativeReasoning = true)(NrIn("Q?")))
      .fold(error => fail(error.message), _.output)
    assertEquals(result.reasoning, "native")
    assertEquals(result.answer, "Paris")
    assertEquals(effort(model.requests.head), low)
    assert(!promptOf(model.requests.head).contains("[[ ## reasoning ## ]]"), promptOf(model.requests.head))
    assertEquals(model.requests.size, 1)
  }

  test("ChainOfThought with native reasoning asks for a text field on a model with no reasoning") {
    val model  = Script(reasoning = false)(LmOutput(bothText))
    val result = run(model)(ChainOfThought(base, nativeReasoning = true)(NrIn("Q?")))
      .fold(error => fail(error.message), _.output)
    assertEquals(result.reasoning, "written")
    assertEquals(effort(model.requests.head), None)
  }

  // ── ReActV2 ───────────────────────────────────────────────────────────────────────────────────────────────────

  private def qa = Signature.fromString("question -> answer")

  private def lookup: ToolFunction =
    ToolFunction.of("lookup", "Look up a query.")(_ => "found")

  private def toolOutput(reasoning: String, name: String, args: DynamicValue.Record): LmOutput =
    LmOutput("", reasoning = reasoning, toolCalls = Vector(ToolCall(name, args, Some("call_1"))))

  private def nativeChat: ChatAdapter = ChatAdapter(native = NativeTools(enabled = true))

  test("ReActV2 on a model with reasoning takes next_thought from the reply") {
    val model = Script(functionCalling = true)(
      toolOutput("I must look.", "lookup", DynamicValues.record("query" := "cats")),
      toolOutput("I can end.", "submit", DynamicValues.record("answer" := "cats"))
    )
    val result = run(model, nativeChat)(ReActV2(qa, Vector(lookup))((question = "cats")))
      .fold(error => fail(error.message), identity)
    assertEquals(result.output.answer, "cats")
    assertEquals(ReActV2.terminationReason(result), Some(TerminationReason.Submit))
    assertEquals(effort(model.requests.head), low)
    assert(!promptOf(model.requests.head).contains("next_thought ##"), promptOf(model.requests.head))
    val thoughts = ReActV2.history(result).getOrElse(fail("no history")).messages
      .map(message => DynamicValues.recordGet(message, "next_thought").flatMap(DynamicValues.asString))
    assertEquals(thoughts, Vector(Some("I must look."), Some("I can end.")))
  }

  test("a turn of ReActV2 with no reasoning has no next_thought in its event") {
    val model  = Script(functionCalling = true)(toolOutput("", "submit", DynamicValues.record("answer" := "cats")))
    val result = run(model, nativeChat)(ReActV2(qa, Vector(lookup))((question = "cats")))
      .fold(error => fail(error.message), identity)
    val message = ReActV2.history(result).getOrElse(fail("no history")).messages.head
    assertEquals(DynamicValues.recordGet(message, "next_thought"), None)
  }

  test("the forced submit of ReActV2 sends no effort, and the prompt asks for next_thought") {
    val model = Script(functionCalling = true)(
      LmOutput("[[ ## completed ## ]]", reasoning = "No action."),
      toolOutput("", "submit", DynamicValues.record("answer" := "forced"))
    )
    val result = run(model, nativeChat)(ReActV2(qa, Vector(lookup))((question = "cats")))
      .fold(error => fail(error.message), identity)
    assertEquals(ReActV2.terminationReason(result), Some(TerminationReason.ForcedSubmit))
    assertEquals(effort(model.requests.head), low)
    assertEquals(effort(model.requests(1)), Some(DynamicValue.Null))
    assert(promptOf(model.requests(1)).contains("next_thought ##"), promptOf(model.requests(1)))
    assertEquals(result.output.answer, "forced")
  }

  test("ReActV2 on a model with no reasoning shows the type hint of next_thought") {
    val model = Script(reasoning = false)(
      LmOutput(
        "[[ ## next_thought ## ]]\nDone.\n\n[[ ## tool_calls ## ]]\n" +
          """{"tool_calls": [{"name": "submit", "args": {"answer": "cats"}}]}""" + "\n\n[[ ## completed ## ]]"
      )
    )
    val result = run(model)(ReActV2(qa, Vector(lookup))((question = "cats")))
      .fold(error => fail(error.message), identity)
    assertEquals(result.output.answer, "cats")
    assert(
      promptOf(model.requests.head).contains("`[[ ## next_thought ## ]]` (must be formatted as a valid Python str)"),
      promptOf(model.requests.head)
    )
  }
