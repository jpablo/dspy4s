package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.gepa.contracts.ScoreWithFeedback
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.DynamicPredict
import munit.FunSuite

class GepaEngineSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  /** Instruction-sensitive task LM: it "follows" the instruction — only when the prompt (which carries the
    * predictor's instruction) contains the magic token "CITY" does it answer "Paris"; otherwise it answers wrong.
    * So a better instruction genuinely raises the score, and GEPA must discover it. */
  private final class TaskLm extends LanguageModel:
    override val id: String   = "task"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val prompt = request.messages.flatMap(_.text).mkString("\n")
      val answer = if prompt.contains("CITY") then "Paris" else "WRONG"
      Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## answer ## ]]\n$answer\n\n[[ ## completed ## ]]"))))

  /** Reflection LM: proposes an improved instruction containing the magic token. */
  private final class ReflectionLm extends LanguageModel:
    override val id: String   = "reflect"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = "```\nAnswer with the CITY name only.\n```"))))

  private val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "exact_answer"
    override def feedback(
        example: Example,
        prediction: DynamicPrediction,
        trace: Vector[TraceEntry],
        component: Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val gold = example.get("answer").map(DynamicValues.renderText).getOrElse("")
      val got  = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
      Right(ScoreWithFeedback(if got == gold then 1.0 else 0.0, s"expected '$gold', got '$got'"))

  private val program: DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some("Answer.")))

  private val dataset: Vector[Example] = (1 to 4).toVector.map(i =>
    Example(values = DynamicValues.record("question" := s"Capital of France ($i)?", "answer" := "Paris"), inputKeys = Set("question"))
  )

  test("GEPA discovers a better instruction and improves the program's validation score") {
    val adapter = new GepaAdapter(program, metric)
    val engine  = new GepaEngine(adapter, new ReflectionLm, GepaConfig(maxMetricCalls = 40, reflectionMinibatchSize = 2, seed = 1L))

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new TaskLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val seedCandidate = Candidate.seed(program)
      val result = engine.optimize(seedCandidate, trainset = dataset, valset = dataset)

      // The seed instruction ("Answer.") scores 0 (no "CITY"); GEPA must find the improved one.
      assertEquals(result.bestScore, 1.0)
      assert(result.bestCandidate("self").contains("CITY"), result.bestCandidate("self"))
      assert(result.numCandidates >= 2, "expected at least one accepted mutation beyond the seed")
      assert(result.totalMetricCalls <= 40, s"respected the budget: ${result.totalMetricCalls}")

      // The returned program actually carries the improved instruction.
      assertEquals(result.bestProgram.layout.instructions, Some(result.bestCandidate("self")))
    }
  }

  test("stopOnPerfectScore halts once the best candidate is perfect (opt-in; default runs to budget)") {
    val adapter = new GepaAdapter(program, metric)
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new TaskLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val seedCandidate = Candidate.seed(program)

      // Opt-in early stop: converges and halts well under the 40-call budget.
      val stopping = new GepaEngine(adapter, new ReflectionLm,
        GepaConfig(maxMetricCalls = 40, reflectionMinibatchSize = 2, stopOnPerfectScore = true, seed = 1L))
      val stopped = stopping.optimize(seedCandidate, trainset = dataset, valset = dataset)
      assertEquals(stopped.bestScore, 1.0)
      assert(stopped.totalMetricCalls < 40, s"early-stop should beat the budget; used ${stopped.totalMetricCalls}")

      // Default (parity): no perfect-score stop, so it keeps spending the budget after convergence.
      val running = new GepaEngine(adapter, new ReflectionLm,
        GepaConfig(maxMetricCalls = 40, reflectionMinibatchSize = 2, seed = 1L))
      val ranToBudget = running.optimize(seedCandidate, trainset = dataset, valset = dataset)
      assertEquals(ranToBudget.bestScore, 1.0)
      assert(
        ranToBudget.totalMetricCalls > stopped.totalMetricCalls,
        s"default should run longer than early-stop: default=${ranToBudget.totalMetricCalls}, stop=${stopped.totalMetricCalls}"
      )
    }
  }

  test("Gepa facade compiles a student end-to-end") {
    val gepa = new Gepa[DynamicPredict](metric, new ReflectionLm, GepaConfig(maxMetricCalls = 40, reflectionMinibatchSize = 2, seed = 1L))
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new TaskLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = gepa.compile(program, trainset = dataset, valset = dataset)
      assertEquals(result.bestScore, 1.0)
      assert(result.bestCandidate("self").contains("CITY"), result.bestCandidate("self"))
    }
  }
