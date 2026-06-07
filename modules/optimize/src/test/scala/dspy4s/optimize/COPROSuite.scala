package dspy4s.optimize

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.{
  DspyError,
  DynamicValues,
  Example,
  FieldRole,
  FieldSpec,
  RuntimeContext,
  SignatureLayout
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, LmUsage, Message, MessageRole}
import dspy4s.programs.DynamicPredict
import munit.FunSuite

/** Offline COPRO suite.
  *
  * The scripted LM serves two distinct sub-tasks through the single ambient model:
  *
  *   1. Instruction generation. The COPRO instruction-generation `DynamicPredict` asks for a
  *      `proposed_instruction`. The scripted LM hands back a different candidate per call by keying on the
  *      `rolloutId` COPRO threads through (so candidates are distinct, mirroring upstream's `n=breadth`).
  *   2. The actual task. The task `DynamicPredict` answers a `question`. The scripted LM returns the GOLD
  *      answer only when the WINNING instruction is the one currently in effect (the test adapter renders the
  *      active `layout.instructions` into the prompt, so the LM can see which candidate is applied); otherwise
  *      it returns a wrong answer. This forces exactly one instruction to score perfectly.
  */
class COPROSuite extends FunSuite:

  // ── Fixtures ──────────────────────────────────────────────────────────────

  /** The instruction COPRO must discover as the winner. */
  private val winningInstruction = "INSTR_C: answer precisely"

  /** Candidate instruction pool the scripted LM proposes; selected by `rolloutId % size` so ANY rolloutId
    * COPRO threads through maps into this pool (and the winner is always reachable). */
  private val proposalPool: Vector[String] =
    Vector(
      "INSTR_A: be brief",
      "INSTR_B: be verbose",
      winningInstruction,
      "INSTR_D: be formal",
      "INSTR_E: be casual"
    )

  /** Marker the instruction-generation layout carries so the scripted LM can tell the two sub-tasks apart. */
  private val instrGenMarker = "OPTIMIZE_THE_INSTRUCTION"

  private val gold: Map[String, String] = Map("q1" -> "a1", "q2" -> "a2", "q3" -> "a3")

  /** Test adapter that renders the ACTIVE instruction into the prompt so the scripted LM can branch on it. */
  private object InstructionAwareAdapter extends Adapter:
    override val name: String = "instruction-aware"
    override def format(invocation: AdapterInvocation)(using RuntimeContext)
        : Either[DspyError, FormattedPrompt] =
      val instr = invocation.layout.instructions.getOrElse("")
      val q     =
        DynamicValues.recordGet(invocation.inputs.values, "question").map(DynamicValues.renderText).getOrElse("")
      val bi    =
        DynamicValues.recordGet(invocation.inputs.values, "basic_instruction").map(DynamicValues.renderText)
          .getOrElse("")
      // Single user message carrying instruction + inputs; the scripted LM keys on its contents.
      val body = s"INSTRUCTION=[$instr] QUESTION=[$q] BASIC=[$bi]"
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some(body)))))

    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext)
        : Either[DspyError, ParsedOutput] =
      // Route the LM text into whichever output field the layout declares (task: answer, instr-gen: proposed_instruction).
      val outField = layout.outputFields.headOption.map(_.name).getOrElse("answer")
      Right(ParsedOutput(values = rec(outField := output.text)))

  private final class ScriptedLm extends LanguageModel:
    override val id: String   = "scripted-copro-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val text = request.messages.lastOption.flatMap(_.text).getOrElse("")
      val out  =
        if text.contains(instrGenMarker) then
          // Instruction-generation call: pick a candidate by rolloutId (distinct per call).
          val r = request.rolloutId.getOrElse(0)
          proposalPool(math.floorMod(r, proposalPool.size))
        else
          // Task call: answer correctly ONLY when the winning instruction is in effect.
          val q = extractBetween(text, "QUESTION=[", "]")
          if text.contains(winningInstruction) then gold.getOrElse(q, "unknown")
          else "WRONG"
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

  private val taskLayout: SignatureLayout =
    SignatureLayout(
      name = "QA",
      fields = Vector(
        FieldSpec(name = "question", role = FieldRole.Input),
        FieldSpec(name = "answer", role = FieldRole.Output)
      ),
      instructions = Some("INSTR_INITIAL: default")
    )

  private val trainset = Vector(
    Example(rec("question" := "q1", "answer" := "a1"), inputKeys = Set("question")),
    Example(rec("question" := "q2", "answer" := "a2"), inputKeys = Set("question")),
    Example(rec("question" := "q3", "answer" := "a3"), inputKeys = Set("question"))
  )

  private def metric = new dspy4s.evaluate.metrics.ExactMatch(answerField = "answer")

  private def config(breadth: Int = 5, depth: Int = 1, seed: Long = 0L): COPROConfig =
    COPROConfig(metric = metric, breadth = breadth, depth = depth, seed = seed, instructionMarker = instrGenMarker)

  // ── 1. Happy path: single-Predict student, COPRO selects the winner ───────

  test("COPRO selects the winning instruction for a single-predictor student") {
    val student = DynamicPredict(layout = taskLayout)
    val optimizer = new COPRO[DynamicPredict](config())
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = optimizer.compile(student, trainset)
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      val report = result.toOption.get
      val best   = report.bestProgram
      val applied = summon[Predictors[DynamicPredict]].read(best).head.layout.instructions
      assertEquals(applied, Some(winningInstruction))
      // The winner scored 100% (gold on every example).
      assertEquals(report.metadata.get("best_score"), Some(100.0))
    }
  }

  // ── 2. Determinism: same seed -> same chosen instruction ──────────────────

  test("COPRO is deterministic for a fixed seed") {
    val student = DynamicPredict(layout = taskLayout)
    def run(): Option[String] =
      val optimizer = new COPRO[DynamicPredict](config(seed = 42L))
      RuntimeEnvironment.withSettings(settings) {
        given RuntimeContext = RuntimeEnvironment.current
        optimizer.compile(student, trainset).toOption
          .flatMap(r => summon[Predictors[DynamicPredict]].read(r.bestProgram).headOption)
          .flatMap(_.layout.instructions)
      }
    val a = run()
    val b = run()
    assertEquals(a, b)
    assertEquals(a, Some(winningInstruction))
  }

  // ── 3. Depth>1 refinement still converges and keeps best ──────────────────

  test("COPRO with depth>1 refines via past attempts and keeps the best") {
    val student   = DynamicPredict(layout = taskLayout)
    val optimizer = new COPRO[DynamicPredict](config(breadth = 5, depth = 3))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = optimizer.compile(student, trainset)
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      val best    = result.toOption.get.bestProgram
      val applied = summon[Predictors[DynamicPredict]].read(best).head.layout.instructions
      assertEquals(applied, Some(winningInstruction))
    }
  }

  // ── 4. Report structure ───────────────────────────────────────────────────

  test("COPRO report exposes scored candidates sorted best-first") {
    val student   = DynamicPredict(layout = taskLayout)
    val optimizer = new COPRO[DynamicPredict](config())
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val report = optimizer.compile(student, trainset).toOption.get
      assert(report.candidates.nonEmpty, "report should track candidates")
      // Candidates are sorted descending by score.
      val scores = report.candidates.map(_.score)
      assertEquals(scores, scores.sorted(using Ordering[Double].reverse))
      // The metric is genuinely discriminating: the winner scores 100, losers score below it.
      assertEquals(scores.head, 100.0)
      assert(scores.exists(_ < 100.0), s"expected some losing candidates, got $scores")
      // The top candidate carries the winning instruction.
      val topInstr =
        summon[Predictors[DynamicPredict]].read(report.candidates.head.program).head.layout.instructions
      assertEquals(topInstr, Some(winningInstruction))
      // Metadata is populated.
      assert(report.metadata.contains("best_score"))
      assert(report.metadata.contains("num_candidates"))
    }
  }

  // ── 5. breadth <= 1 is rejected ───────────────────────────────────────────

  test("COPROConfig requires breadth > 1") {
    intercept[IllegalArgumentException] {
      val _ = COPROConfig(metric = metric, breadth = 1)
    }
  }
