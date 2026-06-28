package dspy4s.optimize.propose

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

import java.util.concurrent.atomic.AtomicInteger

import zio.blocks.schema.DynamicValue

/** Offline GroundedProposer suite.
  *
  * The scripted LM serves two distinct sub-tasks through the single ambient model, branching on markers the
  * proposer weaves into each signature's instructions (rendered into the prompt by the test adapter):
  *
  *   1. Dataset summary. The summary `DynamicPredict` asks for `observations`. The scripted LM returns a canned
  *      summary string AND bumps a call counter (so the test can assert the summary call is skipped when
  *      `useDatasetSummary = false`).
  *   2. Instruction generation. The instruction-generation `DynamicPredict` asks for a `proposed_instruction`.
  *      The scripted LM returns a distinct candidate per call (keyed on the `rolloutId` the proposer threads
  *      through) and echoes part of the dataset summary into the proposal — proving the summary grounding is wired.
  */
class GroundedProposerSuite extends FunSuite:

  // ── Fixtures ──────────────────────────────────────────────────────────────

  private val summaryMarker     = "SUMMARIZE_THE_DATASET"
  private val instructionMarker = "PROPOSE_THE_INSTRUCTION"

  /** The canned dataset-summary string the scripted LM returns for summary calls; a fragment is echoed into
    * proposals so grounding is observable. */
  private val cannedSummary         = "DATASET=arithmetic word problems"
  private val cannedSummaryFragment = "arithmetic word problems"

  /** Counts summary-sub-task LM calls so the test can assert `useDatasetSummary = false` skips them. */
  private val summaryCalls = new AtomicInteger(0)

  /** Test adapter that renders the active instruction + all input fields into a single prompt so the scripted LM
    * can branch on the marker and read the grounded inputs. */
  private object GroundingAwareAdapter extends Adapter:
    override val name: String = "grounding-aware"
    override def format(invocation: AdapterInvocation)(using RuntimeContext)
        : Either[DspyError, FormattedPrompt] =
      val instr  = invocation.layout.instructions.getOrElse("")
      val inputs = DynamicValues.recordEntries(invocation.inputs.values)
        .map { case (k, v) => s"$k=[${DynamicValues.renderText(v)}]" }
        .mkString(" ")
      val body = s"INSTRUCTION=[$instr] $inputs"
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some(body)))))

    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext)
        : Either[DspyError, ParsedOutput] =
      val outField = layout.outputFields.headOption.map(_.name).getOrElse("observations")
      Right(ParsedOutput(values = rec(outField := output.text)))

  private final class ScriptedLm extends LanguageModel:
    override val id: String   = "scripted-grounded-lm"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val text = request.messages.lastOption.flatMap(_.text).getOrElse("")
      val out  =
        if text.contains(summaryMarker) then
          summaryCalls.incrementAndGet()
          cannedSummary
        else if text.contains(instructionMarker) then
          // Distinct candidate per rolloutId; echo the dataset summary fragment IF it was grounded into the prompt.
          val r          = request.rolloutId.getOrElse(0)
          val grounded   = if text.contains(cannedSummaryFragment) then s" [grounded:$cannedSummaryFragment]" else ""
          s"INSTRUCTION_CANDIDATE_$r$grounded"
        else "UNKNOWN"
      Right(LmResponse(
        outputs = Vector(LmOutput(text = out)),
        usage   = Some(LmUsage(totalTokens = 1, promptTokens = 1, completionTokens = 0))
      ))

  private def settings: RuntimeContext =
    RuntimeContext(lm = Some(new ScriptedLm), adapter = Some(GroundingAwareAdapter))

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()
    summaryCalls.set(0)
  override def afterEach(context: AfterEach): Unit = RuntimeEnvironment.resetForTests()

  private def taskLayout(name: String, instruction: String): SignatureLayout =
    SignatureLayout.of(
      name = name,
      fields = Vector(
        FieldSpec(name = "question", role = FieldRole.Input),
        FieldSpec(name = "answer", role = FieldRole.Output)
      ),
      instructions = Some(instruction)
    )

  private val trainset = Vector(
    Example(rec("question" := "2+2", "answer" := "4"), inputKeys = Set("question")),
    Example(rec("question" := "3+5", "answer" := "8"), inputKeys = Set("question")),
    Example(rec("question" := "10-4", "answer" := "6"), inputKeys = Set("question"))
  )

  private def config(
      numInstructions: Int,
      useDatasetSummary: Boolean = true,
      useTips: Boolean = true,
      seed: Long = 0L
  ): GroundedProposerConfig =
    GroundedProposerConfig(
      numInstructions = numInstructions,
      useDatasetSummary = useDatasetSummary,
      useTips = useTips,
      seed = seed,
      summaryMarker = summaryMarker,
      instructionMarker = instructionMarker
    )

  // ── 1. Single predictor: N distinct, non-empty candidates ─────────────────

  test("proposeInstructions returns N non-empty, distinct candidates for a single-predictor program") {
    val program  = DynamicPredict(layout = taskLayout("QA", "INSTR_INITIAL"))
    val proposer = new GroundedProposer[DynamicPredict](config(numInstructions = 5))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = proposer.proposeInstructions(program, trainset)
      assert(result.isRight, s"propose failed: ${result.left.toOption}")
      val perPred = result.toOption.get
      assertEquals(perPred.size, 1, "one predictor -> one candidate vector")
      val candidates = perPred.head
      assertEquals(candidates.size, 5)
      assert(candidates.forall(_.nonEmpty), s"expected all non-empty, got $candidates")
      assertEquals(candidates.distinct.size, 5, s"expected all distinct, got $candidates")
    }
  }

  // ── 2. Dataset summary is actually grounded into the proposals ────────────

  test("the dataset summary is incorporated into the proposals (grounding is wired)") {
    val program  = DynamicPredict(layout = taskLayout("QA", "INSTR_INITIAL"))
    val proposer = new GroundedProposer[DynamicPredict](config(numInstructions = 3, useDatasetSummary = true))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val candidates = proposer.proposeInstructions(program, trainset).toOption.get.head
      assert(
        candidates.forall(_.contains(cannedSummaryFragment)),
        s"expected every proposal to echo the grounded summary fragment, got $candidates"
      )
      assert(summaryCalls.get() >= 1, "summary call should have happened")
    }
  }

  // ── 3. useDatasetSummary = false skips the summary call ───────────────────

  test("useDatasetSummary = false skips the summary LM call and the grounding") {
    val program  = DynamicPredict(layout = taskLayout("QA", "INSTR_INITIAL"))
    val proposer = new GroundedProposer[DynamicPredict](config(numInstructions = 3, useDatasetSummary = false))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val candidates = proposer.proposeInstructions(program, trainset).toOption.get.head
      assertEquals(summaryCalls.get(), 0, "no summary call should have happened")
      assert(
        candidates.forall(!_.contains(cannedSummaryFragment)),
        s"expected no summary grounding, got $candidates"
      )
      assertEquals(candidates.size, 3)
      assertEquals(candidates.distinct.size, 3)
    }
  }

  // ── 4. Multi-predictor: one candidate vector per predictor ────────────────

  test("multi-predictor program returns one candidate vector per predictor") {
    val program  = TwoStage(
      first = DynamicPredict(layout = taskLayout("First", "INSTR_FIRST")),
      second = DynamicPredict(layout = taskLayout("Second", "INSTR_SECOND"))
    )
    val proposer = new GroundedProposer[TwoStage](config(numInstructions = 4))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = proposer.proposeInstructions(program, trainset)
      assert(result.isRight, s"propose failed: ${result.left.toOption}")
      val perPred = result.toOption.get
      assertEquals(perPred.size, 2, "two predictors -> two candidate vectors")
      assert(perPred.forall(_.size == 4), s"each predictor gets 4 candidates, got ${perPred.map(_.size)}")
      assert(perPred.forall(_.distinct.size == 4), "each predictor's candidates are distinct")
    }
  }

  // ── 5. Determinism: same seed -> same proposals ───────────────────────────

  test("proposeInstructions is deterministic for a fixed seed") {
    val program = DynamicPredict(layout = taskLayout("QA", "INSTR_INITIAL"))
    def run(): Vector[String] =
      val proposer = new GroundedProposer[DynamicPredict](config(numInstructions = 4, seed = 42L))
      RuntimeEnvironment.withSettings(settings) {
        given RuntimeContext = RuntimeEnvironment.current
        proposer.proposeInstructions(program, trainset).toOption.get.head
      }
    assertEquals(run(), run())
  }

  // ── 6. numInstructions <= 0 is rejected ───────────────────────────────────

  test("GroundedProposerConfig requires numInstructions > 0") {
    intercept[IllegalArgumentException] {
      val _ = GroundedProposerConfig(numInstructions = 0)
    }
  }

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

/** A two-predictor program for the multi-predictor test; `Predictors` is structurally derived over its fields. */
final case class TwoStage(first: DynamicPredict, second: DynamicPredict)
