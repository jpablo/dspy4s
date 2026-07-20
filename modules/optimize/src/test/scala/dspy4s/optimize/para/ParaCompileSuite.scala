package dspy4s.optimize.para

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.{DspyError, DynamicValues, Example, RuntimeContext, SignatureLayout}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, LmUsage, Message, MessageRole}
import dspy4s.optimize.{COPROConfig, Runnable, QAInput, QAOutput}
import dspy4s.optimize.para.ParaCompile.{*, given}
import dspy4s.programs.Predict
import dspy4s.programs.para.{ParaCategory, Program, RecordCodec}
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** Offline probe of [[ParaCompile]]: COPRO driven through a packaged [[Program]] entry point, over a TYPED
  * `Predict[QAInput, QAOutput]` student. The scripted LM / instruction-aware adapter mirror `COPROSuite` (instruction
  * generation keyed by rolloutId; the task answers gold only under the winning instruction). Also pins the closed loop:
  * with `decodeInput` packaged, `copro` works on an UPCAST `Program[I, O]` (the earlier revision proved at compile time
  * that it could not) and on a COMPOSED pipeline `a >>> b`, which the ambient `Module` world cannot run from records at
  * all.
  */
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
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      val instr = invocation.layout.instructions.getOrElse("")
      val q =
        DynamicValues.recordGet(invocation.inputs.values, "question").map(DynamicValues.renderText).getOrElse("")
      val bi =
        DynamicValues.recordGet(invocation.inputs.values, "basic_instruction").map(DynamicValues.renderText)
          .getOrElse("")
      Right(FormattedPrompt(messages =
        Vector(Message(role = MessageRole.User, text = Some(s"INSTRUCTION=[$instr] QUESTION=[$q] BASIC=[$bi]")))
      ))

    override def parse(layout: SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
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
        usage = Some(LmUsage(totalTokens = 1, promptTokens = 1, completionTokens = 0))
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
  override def afterEach(context: AfterEach): Unit   = RuntimeEnvironment.resetForTests()

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

  test("COPRO through a packaged Program selects the winning instruction (asserted via params)") {
    val student = Program.of(Predict[QAInput, QAOutput](taskSignature))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result           = student.copro(config(), trainset)
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      val report = result.toOption.get
      // The whole assertion goes through the Para surface: no Predictors summon at the call site.
      assertEquals(report.bestProgram.params.head.instructions, Some(winningInstruction))
      assertEquals(report.metadata.get("best_score"), Some(100.0))
      assert(report.candidates.nonEmpty)
      assertEquals(report.candidates.head.program.params.head.instructions, Some(winningInstruction))
    }
  }

  // ── 2. Determinism through the packaged entry point ──────────────────────────────────────────────────────

  test("the packaged entry point is deterministic for a fixed seed") {
    def run(): Option[String] =
      val student = Program.of(Predict[QAInput, QAOutput](taskSignature))
      RuntimeEnvironment.withSettings(settings) {
        given RuntimeContext = RuntimeEnvironment.current
        student.copro(config(seed = 42L), trainset).toOption
          .flatMap(_.bestProgram.params.headOption)
          .flatMap(_.instructions)
      }
    val a = run()
    assertEquals(a, run())
    assertEquals(a, Some(winningInstruction))
  }

  // ── 3. The closed loop, part 1: the upcast that used to fail now works ───────────────────────────────────

  test("copro works on an UPCAST Program[I, O] (the packaged decoder closed the Runnable gap)") {
    // The earlier revision pinned (via compileErrors) that this exact shape could NOT compile: Runnable had
    // to be summoned against the packaging-refined Rep. With decodeInput packaged, both optimizer
    // capabilities are uniform over Program[I, O], so the erased type is fully optimizable.
    val erased: Program[QAInput, QAOutput] = Program.of(Predict[QAInput, QAOutput](taskSignature))
    assertEquals(erased.params.size, 1)
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val report           = erased.copro(config(), trainset).toOption.get
      assertEquals(report.bestProgram.params.head.instructions, Some(winningInstruction))
    }
  }

  // ── 4. The closed loop, part 2: a COMPOSED pipeline is record-runnable and optimizable ──────────────────

  test("a composed pipeline (a >>> b) is record-runnable and optimizable through the packaged evidence") {
    // Second stage maps QAOutput back to QAInput (fields `answer -> question`, unique within one layout).
    val first  = Program.of(Predict[QAInput, QAOutput](taskSignature))
    val second = Program.of(Predict[QAOutput, QAInput](Signature.derived[QAOutput, QAInput]("Back")))
    val pipeline: Program[QAInput, QAInput] = first >>> second
    // The metric compares the pipeline's final output field ("question"); this test proves the PLUMBING
    // (record-run + optimization over a composite), not instruction discovery, so zero scores are fine.
    val pipelineConfig = COPROConfig(
      metric = new dspy4s.evaluate.metrics.ExactMatch(answerField = "question"),
      breadth = 5,
      depth = 1,
      seed = 0L,
      instructionMarker = instrGenMarker
    )
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      // Uniform record-based evaluation on a composite: decode via the threaded first-leg decoder, run both
      // stages. Bare user composites need a hand-written Runnable for exactly this (Runnable's scaladoc).
      val ran = summon[Runnable[Program[QAInput, QAInput]]].run(pipeline, rec("question" := "q1"))
      assert(ran.isRight, s"record-run of the composed pipeline failed: ${ran.left.toOption}")
      // And the whole pipeline is optimizable: COPRO sees both predicts through the packaged evidence.
      val result = pipeline.copro(pipelineConfig, trainset)
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      val report = result.toOption.get
      assertEquals(report.metadata.get("predictors"), Some(2))
      assertEquals(report.bestProgram.params.size, 2)
    }
  }

  // ── 5. Codec-equipped objects: an id-headed pipeline evaluates and optimizes ─────────────────────────────

  test("id at the head of a pipeline is record-runnable and optimizable (codec-equipped objects)") {
    // id[QAInput] synthesizes its decoder from RecordCodec[QAInput] (via the input type's Schema, the same
    // Shape decode path Signature.derived uses), so the previously-degraded left-unit case now evaluates and
    // optimizes end-to-end.
    val C                                    = summon[ParaCategory[RecordCodec, Program]]
    val pipeline: Program[QAInput, QAOutput] = C.id[QAInput] >>> Program.of(Predict[QAInput, QAOutput](taskSignature))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val ran              = summon[Runnable[Program[QAInput, QAOutput]]].run(pipeline, rec("question" := "q1"))
      assert(ran.isRight, s"record-run of the id-headed pipeline failed: ${ran.left.toOption}")
      val report = pipeline.copro(config(), trainset).toOption.get
      assertEquals(report.bestProgram.params.head.instructions, Some(winningInstruction))
      assertEquals(report.metadata.get("best_score"), Some(100.0))
    }
  }
