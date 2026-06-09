package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable

/** Proves Refine's PER-MODULE advice routing: a two-stage program whose two predictors each need a DIFFERENT secret
  * token, where OfferFeedback returns a per-module advice dict and each predictor's `hint_` must carry only ITS own
  * advice. If advice were injected uniformly (the old behavior), both predictors would see both tokens and the
  * per-predictor prompt assertions would fail. */
class RefinePerModuleAdviceSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private case class Q(q: String)
  private case class Cand(answer: String)

  private val HinterToken   = "HINTER_SECRET"
  private val AnswererToken  = "ANSWERER_SECRET"

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record = DynamicValues.recordFromEntries(entries)

  /** Two-stage introspectable program: `hinter` (q -> hint) feeds `answerer` (q, hint -> answer). A case class of
    * `DynamicPredict`s, so `Predictors` is structurally derived with field-label names ("hinter", "answerer"). */
  private final case class HintThenAnswer(hinter: DynamicPredict, answerer: DynamicPredict)
      extends Module[TypedCall[Q], Prediction[Cand]]:
    override val moduleName: String = "hint_then_answer"
    override protected def callInputs(call: TypedCall[Q]): DynamicValue.Record  = rec("q" := call.input.q)
    override protected def callTraceEnabled(call: TypedCall[Q]): Boolean        = call.traceEnabled
    override protected def tracePayload(p: Prediction[Cand]): DynamicValue.Record = p.raw.values

    override protected def forward(call: TypedCall[Q])(using RuntimeContext): Either[DspyError, Prediction[Cand]] =
      for
        hintPred <- hinter.apply(ProgramCall(inputs = rec("q" := call.input.q)))
        hint      = DynamicValues.recordGet(hintPred.values, "hint").map(DynamicValues.renderText).getOrElse("")
        ansPred  <- answerer.apply(ProgramCall(inputs = rec("q" := call.input.q, "hint" := hint)))
        answer   <- ansPred.asString("answer")
      yield Prediction(output = Cand(answer), raw = ansPred)

  /** Generic adapter that tags each prompt with its requested OUTPUT fields (so the LM and the test can route), and
    * records the LAST prompt per tag (so we can inspect what each predictor saw on the final attempt). Parses output
    * field values back out of `LmOutput.metadata`. */
  private final class RecordingAdapter extends Adapter:
    val prompts: mutable.Map[String, String] = mutable.Map.empty
    override val name: String                = "recording"

    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      val tag = invocation.layout.outputFields.map(_.name).mkString(",")
      val rendered = invocation.layout.inputFields.map { field =>
        val v = DynamicValues.recordGet(invocation.inputs.values, field.name).map(DynamicValues.renderText).getOrElse("")
        s"${field.name}: $v"
      }.mkString("\n")
      val text = s"OUTPUTS=$tag\n$rendered"
      prompts(tag) = text
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some(text)))))

    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      val entries = layout.outputFields.map { field =>
        field.name -> DynamicValues.recordGet(output.metadata, field.name).getOrElse(DynamicValue.Null)
      }
      Right(ParsedOutput(values = DynamicValues.recordFromEntries(entries)))

  /** One LM that plays both task and reflection roles, routed by the prompt's OUTPUTS tag:
    *   - `advice`  -> returns a per-module JSON advice dict telling each predictor its own secret token.
    *   - `hint`    -> emits "good" iff the hinter's prompt carries the hinter token (via injected `hint_`).
    *   - `answer`  -> emits "Paris" iff the answerer's prompt carries the answerer token AND a "good" hint. */
  private final class TaskAndFeedbackLm extends LanguageModel:
    override val id: String   = "task-and-feedback"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val prompt = request.messages.flatMap(_.text).mkString("\n")
      if prompt.contains("advice") then
        val dict = s"""{"hinter": "Always include $HinterToken.", "answerer": "Always include $AnswererToken."}"""
        Right(LmResponse(outputs = Vector(LmOutput(text = "", metadata = rec("discussion" := "blame", "advice" := dict)))))
      else if prompt.contains("OUTPUTS=hint") then
        val hint = if prompt.contains(HinterToken) then "good" else "bad"
        Right(LmResponse(outputs = Vector(LmOutput(text = "", metadata = rec("hint" := hint)))))
      else
        val ok     = prompt.contains(AnswererToken) && prompt.contains("good")
        val answer = if ok then "Paris" else "Wrong"
        Right(LmResponse(outputs = Vector(LmOutput(text = "", metadata = rec("answer" := answer)))))

  private def predict(sig: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(sig).toOption.get.withInstructions(Some("Do the task.")))

  test("Refine routes each predictor's OWN advice to its OWN hint_ (per-module advice, not uniform)") {
    val program = HintThenAnswer(hinter = predict("q -> hint"), answerer = predict("q, hint -> answer"))
    val adapter = RecordingAdapter()
    val lm      = TaskAndFeedbackLm()
    val refine  = Refine[HintThenAnswer, Q, Cand](
      module    = program,
      n         = 2,
      rewardFn  = (_, p) => if p.output.answer == "Paris" then 1.0 else 0.0,
      threshold = 1.0
    )

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(adapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = refine.apply(TypedCall(Q("Capital of France?")))

      // Both predictors got their own token on the retry, so the final answer is correct.
      assertEquals(result.toOption.map(_.output.answer), Some("Paris"))

      // The decisive assertions: each predictor's final prompt carried ONLY its own advice token.
      val hinterPrompt   = adapter.prompts("hint")
      val answererPrompt = adapter.prompts("answer")
      assert(hinterPrompt.contains(HinterToken), s"hinter must see its own token; got:\n$hinterPrompt")
      assert(!hinterPrompt.contains(AnswererToken), s"hinter must NOT see the answerer's token; got:\n$hinterPrompt")
      assert(answererPrompt.contains(AnswererToken), s"answerer must see its own token; got:\n$answererPrompt")
      assert(!answererPrompt.contains(HinterToken), s"answerer must NOT see the hinter's token; got:\n$answererPrompt")
    }
  }

  test("parseAdvice decodes a JSON dict and falls back to uniform advice for non-JSON") {
    val names = Vector("hinter", "answerer")
    // Faithful path: JSON object keyed by module name (tolerating surrounding prose).
    val dict = Refine.parseAdvice("""here you go: {"hinter": "A", "answerer": "N/A"} thanks""", names)
    assertEquals(dict, Map("hinter" -> "A", "answerer" -> "N/A"))
    // Fallback path: a bare string becomes uniform advice across every module.
    assertEquals(Refine.parseAdvice("just try harder", names), Map("hinter" -> "just try harder", "answerer" -> "just try harder"))
  }
