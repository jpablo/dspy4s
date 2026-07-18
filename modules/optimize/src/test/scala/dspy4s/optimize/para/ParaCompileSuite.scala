package dspy4s.optimize.para

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.{DspyError, DynamicValues, Example, RuntimeContext, SignatureLayout}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, LmUsage, Message, MessageRole}
import dspy4s.optimize.{COPROConfig, QAInput, QAOutput}
import dspy4s.optimize.para.ParaCompile.*
import dspy4s.programs.Predict
import dspy4s.programs.para.Prog
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** Offline probe of [[ParaCompile]]: COPRO driven through a packaged [[Prog]] entry point, over a TYPED
  * `Predict[QAInput, QAOutput]` student. The scripted LM / instruction-aware adapter mirror `COPROSuite`
  * (instruction generation keyed by rolloutId; the task answers gold only under the winning instruction).
  * Also pins the prototype's ergonomics finding: the Para operations (`params`) survive an upcast to bare
  * `Prog[I, O]`, but `copro` does not (its `Runnable[prog.Rep]` becomes unsummonable), proven at compile
  * time. */
class ParaCompileSuite extends FunSuite:

  // ── Fixtures (COPROSuite's, over the typed student) ───────────────────────

  private val winningInstruction = "INSTR_C: answer precisely"

  private val proposalPool: Vector[String] =
    Vector(
      "INSTR_A: be brief",
      "INSTR_B: be verbose",
      winningInstruction,
      "INSTR_D: be formal",
      "INSTR_E: be casual"
    )

  private val instrGenMarker = "OPTIMIZE_THE_INSTRUCTION"

  private val gold: Map[String, String] = Map("q1" -> "a1", "q2" -> "a2", "q3" -> "a3")

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  private object InstructionAwareAdapter extends Adapter:
    override val name: String = "instruction-aware"
    override def format(invocation: AdapterInvocation)(using RuntimeContext)
        : Either[DspyError, FormattedPrompt] =
      val instr = invocation.layout.instructions.getOrElse("")
      val q =
        DynamicValues.recordGet(invocation.inputs.values, "question").map(DynamicValues.renderText).getOrElse("")
      val bi =
        DynamicValues.recordGet(invocation.inputs.values, "basic_instruction").map(DynamicValues.renderText)
          .getOrElse("")
      Right(FormattedPrompt(messages =
        Vector(Message(role = MessageRole.User, text = Some(s"INSTRUCTION=[$instr] QUESTION=[$q] BASIC=[$bi]")))
      ))

    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext)
        : Either[DspyError, ParsedOutput] =
      val outField = layout.outputFields.headOption.map(_.name).getOrElse("answer")
      Right(ParsedOutput(values = rec(outField := output.text)))

  private final class ScriptedLm extends LanguageModel:
    override val id: String   = "scripted-para-copro-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val text = request.messages.lastOption.flatMap(_.text).getOrElse("")
      val out =
        if text.contains(instrGenMarker) then
          proposalPool(math.floorMod(request.rolloutId.getOrElse(0), proposalPool.size))
        else
          val q = extractBetween(text, "QUESTION=[", "]")
          if text.contains(winningInstruction) then gold.getOrElse(q, "unknown") else "WRONG"
      Right(LmResponse(
        outputs = Vector(LmOutput(text = out)),
        usage   = Some(LmUsage(totalTokens = 1, promptTokens = 1, completionTokens = 0))
      ))

  private def extractBetween(s: String, start: String, end: String): String =
    val i = s.indexOf(start)
    if i < 0 then ""
    else
      val from = i + start.length
      val j    = s.indexOf(end, from)
      if j < 0 then "" else s.substring(from, j)

  private def settings: RuntimeContext =
    RuntimeContext(lm = Some(new ScriptedLm), adapter = Some(InstructionAwareAdapter))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private def taskSignature: Signature[QAInput, QAOutput] =
    Signature.derived[QAInput, QAOutput]("QA").withInstructions(Some("INSTR_INITIAL: default"))

  private val trainset = Vector(
    Example(rec("question" := "q1", "answer" := "a1"), inputKeys = Set("question")),
    Example(rec("question" := "q2", "answer" := "a2"), inputKeys = Set("question")),
    Example(rec("question" := "q3", "answer" := "a3"), inputKeys = Set("question"))
  )

  private def config(seed: Long = 0L): COPROConfig =
    COPROConfig(
      metric = new dspy4s.evaluate.metrics.ExactMatch(answerField = "answer"),
      breadth = 5,
      depth = 1,
      seed = seed,
      instructionMarker = instrGenMarker
    )

  // ── 1. Happy path: the packaged entry point finds the winner; assertions via the Para surface ────────────

  test("COPRO through a packaged Prog selects the winning instruction (asserted via params)") {
    val student = Prog.of(Predict[QAInput, QAOutput](taskSignature))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = student.copro(config(), trainset)
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      val report = result.toOption.get
      // The whole assertion goes through the Para surface: no Predictors summon at the call site.
      assertEquals(report.bestProgram.params.head.layout.instructions, Some(winningInstruction))
      assertEquals(report.metadata.get("best_score"), Some(100.0))
      assert(report.candidates.nonEmpty)
      assertEquals(report.candidates.head.program.params.head.layout.instructions, Some(winningInstruction))
    }
  }

  // ── 2. Determinism through the packaged entry point ──────────────────────────────────────────────────────

  test("the packaged entry point is deterministic for a fixed seed") {
    def run(): Option[String] =
      val student = Prog.of(Predict[QAInput, QAOutput](taskSignature))
      RuntimeEnvironment.withSettings(settings) {
        given RuntimeContext = RuntimeEnvironment.current
        student.copro(config(seed = 42L), trainset).toOption
          .flatMap(_.bestProgram.params.headOption)
          .flatMap(_.layout.instructions)
      }
    val a = run()
    assertEquals(a, run())
    assertEquals(a, Some(winningInstruction))
  }

  // ── 3. The ergonomics finding, pinned at compile time ────────────────────────────────────────────────────

  test("upcasting to bare Prog[I, O] keeps the Para operations but loses copro (Runnable unsummonable)") {
    val erased: Prog[QAInput, QAOutput] = Prog.of(Predict[QAInput, QAOutput](taskSignature))
    // The Para surface survives the upcast: params still works on the erased type.
    assertEquals(erased.params.size, 1)
    // But the optimizer entry point does not: Runnable[erased.Rep] has an abstract Rep, so nothing resolves.
    val errors = compileErrors("erased.copro(config(), trainset)")
    assert(errors.nonEmpty, "expected copro on an upcast Prog to fail compilation")
    assert(errors.contains("Runnable"), s"expected a missing-Runnable error, got:\n$errors")
  }
