package dspy4s.optimize.para

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.{DspyError, DynamicValues, LmUsage, RuntimeContext, SignatureLayout}
import dspy4s.core.data.Example
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, Message, MessageRole}
import dspy4s.optimize.{COPROConfig, CoproBreadth, QAInput, QAOutput, RoundCount}
import dspy4s.programs.ProgramRunner
import dspy4s.optimize.para.ParaCompile.*
import dspy4s.programs.DynamicSignature
import dspy4s.programs.strategies.Predict
import dspy4s.programs.algebra.{Program, SomeProgram}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** Offline probe of [[ParaCompile]]: COPRO driven through a packaged [[Program]] entry point, over a
  * `Predict[QAInput, QAOutput]` student. The scripted LM / instruction-aware adapter mirror `COPROSuite` (instruction
  * generation keyed by rolloutId; the task answers gold only under the winning instruction). Also pins the distinction
  * between record-running and optimization: an upcast `SomeProgram[I, O]` remains runnable but has erased the arity
  * required by optimizers, while a shape-preserving composed pipeline remains both runnable and optimizable.
  */
class ParaCompileSuite extends FunSuite:

  // ── Fixtures (COPROSuite's, over the student) ───────────────────────

  private val winningInstruction = "INSTR_C: answer precisely"

  private val proposalPool: Vector[String] = Vector(
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
    override val name: String                                                                                    = "instruction-aware"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      val instr = invocation.layout.instructions.getOrElse("")
      val q     = DynamicValues.recordGet(invocation.inputs.values, "question").map(DynamicValues.renderText).getOrElse("")
      val bi    = DynamicValues.recordGet(invocation.inputs.values, "basic_instruction").map(DynamicValues.renderText)
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
    override val id: String                                                                    = "scripted-para-copro-lm"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val text = request.messages.lastOption.flatMap(_.text).getOrElse("")
      val out  =
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
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

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
      breadth = CoproBreadth(5),
      depth = RoundCount(1),
      seed = seed,
      instructionMarker = instrGenMarker
    )

  // ── 1. Happy path: the packaged entry point finds the winner; assertions via the parameterized surface ────────────

  test("COPRO through a packaged Program selects the winning instruction (asserted via params)") {
    val student = Program.of(Predict[QAInput, QAOutput](taskSignature))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result           = student.copro(config(), trainset)
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      val report = result.toOption.get
      // The whole assertion goes through the parameterized surface: no OptimizableStructure summon at the call site.
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

  // ── 3. Erasing parameter arity preserves running but deliberately loses optimization ───────────────────

  test("an upcast SomeProgram[I, O] remains runnable but is no longer optimizable") {
    val erased: SomeProgram[QAInput, QAOutput] = Program.of(Predict[QAInput, QAOutput](taskSignature))
    assertEquals(erased.params.size, 1)
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val ran              = summon[ProgramRunner[SomeProgram[QAInput, QAOutput]]].run(erased, rec("question" := "q1"))
      assert(ran.isRight, s"record-run of the erased program failed: ${ran.left.toOption}")
    }
    val errors = compileErrors(
      "summon[dspy4s.programs.optimization.OptimizableStructure[SomeProgram[QAInput, QAOutput]]]"
    )
    assert(errors.nonEmpty, "expected erased Program structure lookup to fail")
  }

  // ── 4. The closed loop, part 2: a COMPOSED pipeline is record-runnable and optimizable ──────────────────

  test("a composed pipeline (a >>> b) is record-runnable and optimizable through the packaged evidence") {
    // Second stage maps QAOutput back to QAInput (fields `answer -> question`, unique within one layout).
    val first                                  = Program.of(Predict[QAInput, QAOutput](taskSignature))
    val second                                 = Program.of(Predict[QAOutput, QAInput](Signature.derived[QAOutput, QAInput]("Back")))
    val pipeline: Program[QAInput, QAInput, 2] = first >>> second
    // The metric compares the pipeline's final output field ("question"); this test proves the PLUMBING
    // (record-run + optimization over a composite), not instruction discovery, so zero scores are fine.
    val pipelineConfig = COPROConfig(
      metric = new dspy4s.evaluate.metrics.ExactMatch(answerField = "question"),
      breadth = CoproBreadth(5),
      depth = RoundCount(1),
      seed = 0L,
      instructionMarker = instrGenMarker
    )
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      // Uniform record-based evaluation on a composite: decode via the threaded first-leg decoder, run both
      // stages. Bare user composites need a hand-written ProgramRunner for exactly this (ProgramRunner's scaladoc).
      val ran = summon[ProgramRunner[Program[QAInput, QAInput, 2]]].run(pipeline, rec("question" := "q1"))
      assert(ran.isRight, s"record-run of the composed pipeline failed: ${ran.left.toOption}")
      // And the whole pipeline is optimizable: COPRO sees both predicts through the packaged evidence.
      val result = pipeline.copro(pipelineConfig, trainset)
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      val report = result.toOption.get
      assertEquals(report.metadata.get("optimizable_leaves"), Some(2))
      assertEquals(report.bestProgram.params.size, 2)
    }
  }

  // ── 5a. Stage 3 of the bundle promotion: a runtime-string student through the same entry point ──────────

  test("COPRO optimizes a DynamicSignature bundle program exactly like a student") {
    // The runtime-string counterpart of test 1: the student's signature exists only as a parsed value, but the
    // bundle mints fresh In/Out types with their codec, so the SAME packaged entry point (OptimizableStructure +
    // ProgramRunner over Program) drives COPRO with no dynamic-specific plumbing anywhere.
    val bundle = DynamicSignature.parse("question -> answer", "INSTR_INITIAL: default").toOption.get
    import bundle.given // the object codec, for the record-boundary runner `.copro` demands
    val student = bundle.packaged()
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result           = student.copro(config(), trainset)
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      val report = result.toOption.get
      assertEquals(report.bestProgram.params.head.instructions, Some(winningInstruction))
      assertEquals(report.metadata.get("best_score"), Some(100.0))
    }
  }

  // ── 5. Codec-equipped objects: an id-headed pipeline evaluates and optimizes ─────────────────────────────

  test("an explicitly packaged zero-arity identity remains optimizable at the head of a pipeline") {
    val identity                                = Program.of(dspy4s.programs.compose.Compose.id[QAInput])
    val pipeline: Program[QAInput, QAOutput, 1] = identity >>> Program.of(Predict[QAInput, QAOutput](taskSignature))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val ran              = summon[ProgramRunner[Program[QAInput, QAOutput, 1]]]
        .run(pipeline, rec("question" := "q1"))
      assert(ran.isRight, s"record-run of the id-headed pipeline failed: ${ran.left.toOption}")
      val report = pipeline.copro(config(), trainset).toOption.get
      assertEquals(report.bestProgram.params.head.instructions, Some(winningInstruction))
      assertEquals(report.metadata.get("best_score"), Some(100.0))
    }
  }
