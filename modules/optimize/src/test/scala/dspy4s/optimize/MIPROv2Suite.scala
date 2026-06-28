package dspy4s.optimize

import dspy4s.programs.Predictors

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
import dspy4s.optimize.propose.GroundedProposerConfig
import dspy4s.programs.DynamicPredict
import munit.FunSuite

/** Offline MIPROv2 suite.
  *
  * The single ambient scripted LM serves THREE sub-tasks, branched on markers MIPROv2's composed phases weave
  * into each signature's instructions (the test adapter renders the active instruction into the prompt):
  *
  *   1. Dataset summary (GroundedProposer phase A): returns a canned observations string.
  *   2. Instruction generation (GroundedProposer phase A): returns a distinct candidate per `rolloutId`, and the
  *      pool always contains the WINNING instruction (so it is reachable as a candidate).
  *   3. The task itself: answers a `question`. It returns the GOLD answer ONLY when the winning instruction is in
  *      effect; otherwise it returns a wrong answer. The bootstrap teacher runs this same task path, so the
  *      teacher (which is configured to already carry the winning instruction) produces correct demos.
  *
  * This forces exactly one (instruction, demo-set) assignment to score perfectly: the one carrying the winning
  * instruction. MIPROv2's search must discover it.
  */
class MIPROv2Suite extends FunSuite:

  // ── Fixtures ──────────────────────────────────────────────────────────────

  /** The instruction MIPROv2 must discover as the winner. */
  private val winningInstruction = "INSTR_WIN: answer precisely"

  /** Candidate instruction pool the scripted LM proposes for instruction-generation calls; selected by
    * `rolloutId % size` so the winner is always reachable. */
  private val proposalPool: Vector[String] =
    Vector(
      "INSTR_A: be brief",
      "INSTR_B: be verbose",
      winningInstruction,
      "INSTR_D: be formal",
      "INSTR_E: be casual"
    )

  /** Marker the instruction-generation layout carries (GroundedProposer instructionMarker). */
  private val instrGenMarker = "PROPOSE_THE_INSTRUCTION"

  /** Marker the dataset-summary layout carries (GroundedProposer summaryMarker). */
  private val summaryMarker = "SUMMARIZE_THE_DATASET"

  private val cannedSummary = "DATASET=qa pairs"

  private val gold: Map[String, String] = Map("q1" -> "a1", "q2" -> "a2", "q3" -> "a3", "q4" -> "a4")

  /** Test adapter that renders the ACTIVE instruction into the prompt so the scripted LM can branch on it. */
  private object InstructionAwareAdapter extends Adapter:
    override val name: String = "instruction-aware"
    override def format(invocation: AdapterInvocation)(using RuntimeContext)
        : Either[DspyError, FormattedPrompt] =
      val instr = invocation.layout.instructions.getOrElse("")
      val q =
        DynamicValues.recordGet(invocation.inputs.values, "question").map(DynamicValues.renderText).getOrElse("")
      val body = s"INSTRUCTION=[$instr] QUESTION=[$q]"
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some(body)))))

    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext)
        : Either[DspyError, ParsedOutput] =
      val outField = layout.outputFields.headOption.map(_.name).getOrElse("answer")
      Right(ParsedOutput(values = rec(outField := output.text)))

  private final class ScriptedLm extends LanguageModel:
    override val id: String   = "scripted-mipro-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val text = request.messages.lastOption.flatMap(_.text).getOrElse("")
      val out =
        if text.contains(summaryMarker) then cannedSummary
        else if text.contains(instrGenMarker) then
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
  override def afterEach(context: AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val taskLayout: SignatureLayout =
    SignatureLayout.of(
      name = "QA",
      fields = Vector(
        FieldSpec(name = "question", role = FieldRole.Input),
        FieldSpec(name = "answer", role = FieldRole.Output)
      ),
      instructions = Some("INSTR_INITIAL: default")
    )

  /** The teacher already carries the winning instruction, so bootstrap demos it produces are correct. */
  private val teacherLayout: SignatureLayout =
    taskLayout.withInstructions(Some(winningInstruction))

  private val trainset = Vector(
    Example(rec("question" := "q1", "answer" := "a1"), inputKeys = Set("question")),
    Example(rec("question" := "q2", "answer" := "a2"), inputKeys = Set("question")),
    Example(rec("question" := "q3", "answer" := "a3"), inputKeys = Set("question"))
  )

  private val valset = Vector(
    Example(rec("question" := "q4", "answer" := "a4"), inputKeys = Set("question"))
  )

  private def metric = new dspy4s.evaluate.metrics.ExactMatch(answerField = "answer")

  private def config(
      numCandidates: Int = 5,
      numTrials: Int = 30,
      seed: Long = 0L
  ): MIPROv2Config =
    MIPROv2Config(
      metric = metric,
      numCandidates = numCandidates,
      numTrials = numTrials,
      seed = seed,
      proposerConfig = GroundedProposerConfig(
        numInstructions = numCandidates,
        seed = seed,
        summaryMarker = summaryMarker,
        instructionMarker = instrGenMarker
      )
    )

  private def predictors = summon[Predictors[DynamicPredict]]

  // ── 1. Happy path: single-predictor student, MIPROv2 finds the winning instruction ──

  test("MIPROv2 selects the winning instruction for a single-predictor student") {
    val student   = DynamicPredict(layout = taskLayout)
    val teacher   = DynamicPredict(layout = teacherLayout)
    val optimizer = new MIPROv2[DynamicPredict](config())
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = optimizer.compile(student, trainset, teacher = Some(teacher), valset = Some(valset))
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      val report  = result.toOption.get
      val applied = predictors.read(report.bestProgram).head.layout.instructions
      assertEquals(applied, Some(winningInstruction))
      // The winner scored 100% (gold on every val example).
      assertEquals(report.metadata.get("best_score"), Some(100.0))
    }
  }

  // ── 2. best_score is the maximum across scored candidates ──────────────────

  test("MIPROv2 best_score equals the max over scored trial candidates") {
    val student   = DynamicPredict(layout = taskLayout)
    val teacher   = DynamicPredict(layout = teacherLayout)
    val optimizer = new MIPROv2[DynamicPredict](config())
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val report  = optimizer.compile(student, trainset, teacher = Some(teacher), valset = Some(valset)).toOption.get
      val maxScore = report.candidates.map(_.score).max
      assertEquals(report.metadata.get("best_score"), Some(maxScore))
      assertEquals(maxScore, 100.0)
    }
  }

  // ── 3. Determinism for a fixed seed ────────────────────────────────────────

  test("MIPROv2 is deterministic for a fixed seed") {
    val student = DynamicPredict(layout = taskLayout)
    val teacher = DynamicPredict(layout = teacherLayout)
    def run(): Option[String] =
      val optimizer = new MIPROv2[DynamicPredict](config(seed = 7L))
      RuntimeEnvironment.withSettings(settings) {
        given RuntimeContext = RuntimeEnvironment.current
        optimizer.compile(student, trainset, teacher = Some(teacher), valset = Some(valset)).toOption
          .flatMap(r => predictors.read(r.bestProgram).headOption)
          .flatMap(_.layout.instructions)
      }
    val a = run()
    val b = run()
    assertEquals(a, b)
    assertEquals(a, Some(winningInstruction))
  }

  // ── 4. Report structure: candidates sorted, metadata present ───────────────

  test("MIPROv2 report exposes scored candidates sorted best-first with metadata") {
    val student   = DynamicPredict(layout = taskLayout)
    val teacher   = DynamicPredict(layout = teacherLayout)
    val optimizer = new MIPROv2[DynamicPredict](config())
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val report = optimizer.compile(student, trainset, teacher = Some(teacher), valset = Some(valset)).toOption.get
      assert(report.candidates.nonEmpty, "report should track candidates")
      val scores = report.candidates.map(_.score)
      assertEquals(scores, scores.sorted(using Ordering[Double].reverse))
      // The metric is discriminating: the winner scores 100, some losers score below it.
      assertEquals(scores.head, 100.0)
      assert(scores.exists(_ < 100.0), s"expected some losing candidates, got $scores")
      // The top candidate carries the winning instruction.
      val topInstr = predictors.read(report.candidates.head.program).head.layout.instructions
      assertEquals(topInstr, Some(winningInstruction))
      // Metadata is populated.
      assert(report.metadata.contains("best_score"))
      assert(report.metadata.contains("num_candidates"))
      assert(report.metadata.contains("num_trials"))
      assert(report.metadata.contains("num_demo_candidates"))
    }
  }

  // ── 5. Config guards ───────────────────────────────────────────────────────

  test("MIPROv2Config requires numCandidates > 0") {
    intercept[IllegalArgumentException] {
      val _ = MIPROv2Config(metric = metric, numCandidates = 0)
    }
  }

  test("MIPROv2Config requires numTrials > 0") {
    intercept[IllegalArgumentException] {
      val _ = MIPROv2Config(metric = metric, numTrials = 0)
    }
  }
