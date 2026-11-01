package dspy4s.optimize

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{
  ContextWindowExceededError,
  DspyError,
  DynamicValues,
  ErrorLimit,
  RuntimeContext,
  RuntimeError,
  SignatureLayout,
  TraceEntry,
  :=
}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.contracts.Metric
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.optimize.propose.{GroundedProposer, GroundedProposerConfig}
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite

import java.io.ByteArrayOutputStream
import scala.collection.mutable.ArrayBuffer

/** The rules of the upstream optimizers (`dspy/teleprompt/`, `dspy/propose/`) that the port did not follow. */
class OptimizerUpstreamRulesSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class Lm(reply: LmRequest => Either[DspyError, String]) extends LanguageModel:
    val requests: ArrayBuffer[LmRequest] = ArrayBuffer.empty
    override val id: String              = "scripted"
    override val mode: LmMode            = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = requests.synchronized(requests += request)
      reply(request).map(text => LmResponse(outputs = Vector(LmOutput(text = text))))

  private def chat(fields: (String, String)*): String =
    fields.map((name, value) => s"[[ ## $name ## ]]\n$value").mkString("", "\n\n", "\n\n[[ ## completed ## ]]")

  private def text(request: LmRequest): String = request.messages.flatMap(_.text).mkString("\n")

  private def isGeneration(request: LmRequest): Boolean = text(request).contains("[[ ## proposed_instruction ## ]]")
  private def isSummary(request   : LmRequest): Boolean = text(request).contains("[[ ## observations ## ]]")
  private def isRules(request     : LmRequest): Boolean = text(request).contains("[[ ## natural_language_rules ## ]]")
  private def isTask(request      : LmRequest): Boolean = !isGeneration(request) && !isSummary(request) && !isRules(request)

  /** A model that answers each kind of request. `proposal` gives the text of a proposed instruction. */
  private def answering(proposal: LmRequest => String = _ => "Be brief."): Lm =
    new Lm(request =>
      Right(
        if isGeneration(request) then chat("proposed_instruction" -> proposal(request))
        else if isSummary(request) then chat("observations" -> "short questions")
        else if isRules(request) then chat("natural_language_rules" -> "1. Be brief.")
        else chat("answer"                                          -> "a")
      )
    )

  private val failure: DspyError = RuntimeError("lm", "the model is down")

  private def withLm[A](lm: LanguageModel, maxErrors: Option[ErrorLimit] = None)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(
      RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()), maxErrors = maxErrors)
    )(body(using RuntimeEnvironment.current))

  private val layout = SignatureLayout.parse("question -> answer").toOption.get

  private def student: DynamicPredict = DynamicPredict(layout = layout)

  private def example(index: Int): Example =
    Example(DynamicValues.record("question" := s"q$index", "answer" := s"a$index"), inputKeys = Set("question"))

  private def trainset(size: Int): Vector[Example] = Vector.tabulate(size)(example)

  private def question(example: Example): String =
    DynamicValues.recordGet(example.values, "question").map(DynamicValues.renderText).getOrElse("")

  private def metric(accepts: Example => Boolean = _ => true): Metric =
    new Metric:
      override def name: String = "scripted"
      override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
          RuntimeContext
      ): Either[DspyError, Double] = Right(if accepts(example) then 1.0 else 0.0)

  private def leaf(program: DynamicPredict) = summon[OptimizableStructure[DynamicPredict]].read(program).head

  private def augmented(program: DynamicPredict): Vector[String] =
    leaf(program).demos.filter(_.augmented).map(question)

  // ── BootstrapFewShotWithRandomSearch (`random_search.py`) ─────────────────────────────────────────────────────────

  test("a shuffled candidate of the random search bootstraps on the whole trainset") {
    val config = RandomSearchConfig(
      metric = metric(question(_) == "q9"),
      numCandidates = SearchCandidateCount(1),
      maxBootstrappedDemos = DemoCount(1),
      maxLabeledDemos = DemoCount(0)
    )
    val report = withLm(answering()) {
      new BootstrapFewShotWithRandomSearch[DynamicPredict](config).compile(student, trainset(10))
    }.toOption.get
    val shuffled = report.candidates.find(_.metadata.get("seed").contains(0)).get
    assertEquals(augmented(shuffled.program), Vector("q9"))
  }

  test("a failed evaluation of the random search is a Left, as upstream raises") {
    val lm     = new Lm(_ => Left(failure))
    val config = RandomSearchConfig(metric = metric(), numCandidates = SearchCandidateCount(1))
    val result = withLm(lm, Some(ErrorLimit(1))) {
      new BootstrapFewShotWithRandomSearch[DynamicPredict](config).compile(student, trainset(3))
    }
    assert(result.isLeft, result.toString)
  }

  test("a failed bootstrap of the random search is a Left, as upstream raises") {
    val teacher = DynamicPredict(layout = layout, lm = Some(new Lm(_ => Left(failure))))
    val config  = RandomSearchConfig(metric = metric(), numCandidates = SearchCandidateCount(1))
    val result  = withLm(answering(), Some(ErrorLimit(1))) {
      new BootstrapFewShotWithRandomSearch[DynamicPredict](config).compile(student, trainset(3), Some(teacher))
    }
    assert(result.isLeft, result.toString)
  }

  // ── BootstrapFewShot and LabeledFewShot (`bootstrap.py`, `vanilla.py`) ────────────────────────────────────────────

  test("the error limit of a bootstrap comes from the context when its config sets none") {
    val config = BootstrapFewShotConfig(metric = Some(metric()), maxLabeledDemos = DemoCount(0))
    val result = withLm(new Lm(_ => Left(failure)), Some(ErrorLimit(1))) {
      new BootstrapFewShot[DynamicPredict](config).compile(student, trainset(3))
    }
    assert(result.left.exists(_.message.contains("(1)")), result.toString)
  }

  test("a duplicate predictor key fails also with an empty trainset") {
    val program = TwoStage(DynamicPredict(layout = layout), DynamicPredict(layout = layout))
    val result  = withLm(answering()) {
      new BootstrapFewShot[TwoStage]().compile(program, Vector.empty)
    }
    assert(result.isLeft, result.toString)
  }

  test("LabeledFewShot draws a sample for each predictor from one random source") {
    val draft  = DynamicPredict(layout = SignatureLayout.parse("question -> draft").toOption.get)
    val answer = DynamicPredict(layout = SignatureLayout.parse("question, draft -> answer").toOption.get)
    val report = withLm(answering()) {
      new LabeledFewShot[TwoStage](LabeledFewShotConfig(k = DemoCount(2))).compile(TwoStage(draft, answer), trainset(8))
    }.toOption.get
    val samples = summon[OptimizableStructure[TwoStage]].read(report.bestProgram).map(_.demos.map(question))
    assertEquals(samples.map(_.size), Vector(2, 2))
    assertNotEquals(samples(0), samples(1))
  }

  // ── COPRO (`copro_optimizer.py`) ──────────────────────────────────────────────────────────────────────────────────

  private def copro(breadth: CoproBreadth = CoproBreadth(2), depth: RoundCount = RoundCount(1)): COPRO[DynamicPredict] =
    new COPRO[DynamicPredict](COPROConfig(metric = metric(), breadth = breadth, depth = depth))

  test("a failed proposal of COPRO is a Left, as upstream raises") {
    val lm     = new Lm(request => if isGeneration(request) then Left(failure) else Right(chat("answer" -> "a")))
    val result = withLm(lm)(copro().compile(student, trainset(2)))
    assert(result.isLeft, result.toString)
  }

  test("a failed evaluation of COPRO is a Left, as upstream raises") {
    val lm = new Lm(request =>
      if isGeneration(request) then Right(chat("proposed_instruction" -> "Be brief.")) else Left(failure)
    )
    val result = withLm(lm, Some(ErrorLimit(1)))(copro().compile(student, trainset(2)))
    assert(result.isLeft, result.toString)
  }

  test("COPRO has the prompts of upstream, and its basic instruction is the effective instruction") {
    val lm = answering()
    assert(withLm(lm)(copro().compile(student, trainset(1))).isRight)
    val prompt = text(lm.requests.find(isGeneration).get)
    assert(
      prompt.contains(
        "You are an instruction optimizer for large language models. I will give you a ``signature`` of fields " +
          "(inputs and outputs) in English. Your task is to propose an instruction that will lead a good language " +
          "model to perform the task well. Don't be afraid to be creative."
      ),
      prompt
    )
    assert(prompt.contains("The initial instructions before optimization"), prompt)
    assert(prompt.contains("The improved instructions for the language model"), prompt)
    assert(prompt.contains(s"[[ ## basic_instruction ## ]]\n${layout.effectiveInstructions}\n"), prompt)
  }

  test("a COPRO round shows the best attempts up to the breadth, in the prompt of upstream") {
    val counter = java.util.concurrent.atomic.AtomicInteger(0)
    val lm      = answering(_ => s"Instruction ${counter.incrementAndGet()}.")
    assert(withLm(lm)(copro(CoproBreadth(2), RoundCount(3)).compile(student, trainset(1))).isRight)
    val last = text(lm.requests.filter(isGeneration).last)
    assert(
      last.contains(
        "You are an instruction optimizer for large language models. I will give some task instructions I've tried, " +
          "along with their corresponding validation scores. The instructions are arranged in increasing order based " +
          "on their scores, where higher scores indicate better quality.\n        \n        Your task is to propose a " +
          "new instruction that will lead a good language model to perform the task even better. Don't be afraid to " +
          "be creative."
      ),
      last
    )
    assertEquals("Resulting Score #".r.findAllIn(last).size, 2, last)
  }

  test("COPRO strips the quotes of a proposal, as upstream") {
    val report = withLm(answering(_ => "\"Be brief.\""))(copro().compile(student, trainset(1))).toOption.get
    val found  = report.candidates.flatMap(_.metadata.get("instruction"))
    assert(found.contains("Be brief."), found.toString)
    assert(!found.contains("\"Be brief.\""), found.toString)
  }

  test("COPRO keeps a leaf with no instructions when each proposal is blank") {
    val report = withLm(answering(_ => " "))(copro().compile(student, trainset(1))).toOption.get
    assertEquals(leaf(report.bestProgram).instructions, None)
    assertEquals(report.candidates.size, 1, "the current instruction is the one candidate")
  }

  test("COPRO runs no evaluation after its search") {
    val lm = answering(request => s"Proposal ${request.rolloutId.getOrElse(0)}.")
    assert(withLm(lm)(copro().compile(student, trainset(3))).isRight)
    // One proposal and the current instruction, each on 3 examples.
    assertEquals(lm.requests.count(isTask), 6)
  }

  // ── InferRules (`infer_rules.py`) ─────────────────────────────────────────────────────────────────────────────────

  private def inferRules: InferRules[DynamicPredict] =
    new InferRules[DynamicPredict](InferRulesConfig(metric = metric(), numCandidates = CandidateCount(1)))

  test("a failed rule induction is a Left, as upstream raises") {
    val lm     = new Lm(request => if isRules(request) then Left(failure) else Right(chat("answer" -> "a")))
    val result = withLm(lm)(inferRules.compile(student, trainset(4)))
    assert(result.isLeft, result.toString)
  }

  test("a single example that does not fit is a Left with the text of upstream") {
    val lm = new Lm(request =>
      if isRules(request) then Left(ContextWindowExceededError("too long")) else Right(chat("answer" -> "a"))
    )
    val result = withLm(lm)(inferRules.compile(student, trainset(4)))
    assert(
      result.left.exists(_.message.contains("a single example couldn't fit in the model's context window")),
      result.toString
    )
  }

  test("a failed evaluation of a rule candidate is a Left, as upstream raises") {
    val lm = new Lm(request =>
      if isRules(request) then Right(chat("natural_language_rules" -> "1. Be brief."))
      else Left(failure)
    )
    val config = InferRulesConfig(
      metric = metric(),
      numCandidates = CandidateCount(1),
      bootstrap = BootstrapFewShotConfig(maxBootstrappedDemos = DemoCount(0), maxLabeledDemos = DemoCount(0))
    )
    val result = withLm(lm, Some(ErrorLimit(1)))(new InferRules[DynamicPredict](config).compile(student, trainset(4)))
    assert(result.isLeft, result.toString)
  }

  test("the rules follow the effective instruction, with the prompt and the values of upstream") {
    val lm   = answering()
    val flag = Example(
      DynamicValues.record("question" := true, "answer" := "a"),
      inputKeys = Set("question")
    )
    val report = withLm(lm)(inferRules.compile(student, Vector(flag), valset = Some(trainset(1)))).toOption.get
    val text_  = text(lm.requests.find(isRules).get)
    assert(
      text_.contains(
        "Given a set of examples, extract a list of 10 concise and non-redundant natural language rules that provide " +
          "clear guidance for performing the task. All rules should be actionable for a well-specified scope of " +
          "examples of this general kind of task."
      ),
      text_
    )
    assert(text_.contains("question: True"), text_)
    val instructions = leaf(report.bestProgram).instructions.getOrElse("")
    assert(instructions.startsWith(layout.effectiveInstructions), instructions)
    assert(instructions.endsWith("Please adhere to the following rules when making your prediction:\n1. Be brief."))
  }

  // ── GroundedProposer (`grounded_proposer.py`, `dataset_summary_generator.py`) ─────────────────────────────────────

  test("GroundedProposer has the prompts of upstream, and its basic instruction is the effective instruction") {
    val lm = answering()
    val _  = withLm(lm)(new GroundedProposer[DynamicPredict](GroundedProposerConfig())
      .proposeInstructions(student, trainset(2))).toOption.get
    val summary = text(lm.requests.find(isSummary).get)
    assert(
      summary.contains(
        "Given several examples from a dataset please write observations about trends that hold for most or all of " +
          "the samples. Some areas you may consider in your observations: topics, content, syntax, conciseness, etc. " +
          "It will be useful to make an educated guess as to the nature of the task this dataset will enable. Don't " +
          "be afraid to be creative"
      ),
      summary
    )
    val prompt = text(lm.requests.find(isGeneration).get)
    assert(
      prompt.contains(
        "Use the information below to learn about a task that we are trying to solve using calls to an LM, then " +
          "generate a new instruction that will be used to prompt a Language Model to better solve the task."
      ),
      prompt
    )
    assert(prompt.contains("A description of the dataset that we are using."), prompt)
    assert(prompt.contains("Basic instruction."), prompt)
    assert(prompt.contains("A suggestion for how to go about generating the new instruction."), prompt)
    assert(prompt.contains("Propose an instruction that will be used to prompt a Language Model"), prompt)
    assert(prompt.contains(s"[[ ## basic_instruction ## ]]\n${layout.effectiveInstructions}\n"), prompt)
  }

  test("a proposal goes through upstream strip_prefix") {
    val proposed = withLm(answering(_ => "Proposed Instruction: \"Do the task.\""))(
      new GroundedProposer[DynamicPredict](GroundedProposerConfig(numInstructions = CandidateCount(1)))
        .proposeInstructions(student, trainset(1))
    )
    assertEquals(proposed, Right(Vector(Vector("Do the task."))))
  }

  test("the rollout ids of two leaves do not overlap, for any number of proposals") {
    val draft  = DynamicPredict(layout = SignatureLayout.parse("question -> draft").toOption.get)
    val answer = DynamicPredict(layout = SignatureLayout.parse("question, draft -> answer").toOption.get)
    val lm     = answering()
    val config = GroundedProposerConfig(
      numInstructions = CandidateCount(65),
      useDatasetSummary = false,
      useTips = false
    )
    assert(withLm(lm)(new GroundedProposer[TwoStage](config).proposeInstructions(
      TwoStage(draft, answer),
      trainset(1)
    )).isRight)
    val ids = lm.requests.toVector.filter(isGeneration).map(_.rolloutId)
    assertEquals(ids.size, 130)
    assertEquals(ids.distinct.size, 130)
  }

  // ── MIPROv2 (`mipro_optimizer_v2.py`, `utils.py`) ─────────────────────────────────────────────────────────────────

  private def mipro(
      numCandidates: CandidateCount = CandidateCount(5),
      numTrials    : TrialCount     = TrialCount(40),
      seed         : Long           = 0L
  ): MIPROv2[DynamicPredict] =
    new MIPROv2[DynamicPredict](MIPROv2Config(
      metric = metric(),
      numCandidates = numCandidates,
      numTrials = numTrials,
      maxBootstrappedDemos = DemoCount(1),
      maxLabeledDemos = DemoCount(0),
      seed = seed
    ))

  test("the demo sets of MIPROv2 come from shuffled trainsets, as upstream create_n_fewshot_demo_sets") {
    val report = withLm(answering())(mipro().compile(student, trainset(8))).toOption.get
    val found  = report.candidates.flatMap(candidate => augmented(candidate.program)).distinct
    assert(found.size > 1, found.toString)
  }

  test("a failed bootstrap of MIPROv2 is a Left, as upstream raises") {
    val teacher = DynamicPredict(layout = layout, lm = Some(new Lm(_ => Left(failure))))
    val result  = withLm(answering(), Some(ErrorLimit(1)))(mipro().compile(student, trainset(3), Some(teacher)))
    assert(result.isLeft, result.toString)
  }

  test("a failed trial of MIPROv2 scores 0.0 with a warning, as upstream eval_candidate_program") {
    // The phases run in order: bootstrap, proposals, evaluations. Thus each task call after a proposal is an evaluation.
    val proposed = java.util.concurrent.atomic.AtomicBoolean(false)
    val lm       = new Lm(request =>
      if isGeneration(request) then
        proposed.set(true)
        Right(chat("proposed_instruction" -> "Be brief."))
      else if isSummary(request) then Right(chat("observations" -> "short questions"))
      else if proposed.get() then Left(failure)
      else Right(chat("answer" -> "a"))
    )
    val captured = new ByteArrayOutputStream
    val result   = Console.withErr(captured) {
      withLm(lm, Some(ErrorLimit(1)))(mipro(CandidateCount(3), TrialCount(1)).compile(student, trainset(1)))
    }
    assert(result.isRight, result.toString)
    assert(captured.toString.contains("An exception occurred during evaluation"), captured.toString)
  }

  test("the seed of MIPROv2 drives its proposer") {
    def rolloutIds(seed: Long): Vector[Option[Int]] =
      val lm = answering()
      assert(withLm(lm)(mipro(CandidateCount(1), TrialCount(1), seed).compile(student, trainset(1))).isRight)
      lm.requests.toVector.filter(isGeneration).map(_.rolloutId)
    assertNotEquals(rolloutIds(0L), rolloutIds(7L))
  }

  test("a leaf with no instructions keeps them in MIPROv2 when each proposal is blank") {
    val report = withLm(answering(_ => " "))(mipro(CandidateCount(1), TrialCount(3)).compile(student, trainset(1)))
    val found  = report.toOption.get.candidates.map(candidate => leaf(candidate.program).instructions).distinct
    assertEquals(found, Vector(None))
  }
