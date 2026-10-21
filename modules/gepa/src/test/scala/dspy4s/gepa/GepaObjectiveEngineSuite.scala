package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.gepa.contracts.ScoreWithFeedback
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** The objective scores of the metric in the adapter, in the evaluation cache, and in the engine (PORT_GAPS G-45, item
  * O4; upstream 638e155cf, `dspy/teleprompt/gepa/gepa_utils.py` `_extract_score_and_objective_scores` and
  * `_make_evaluation_batch`, and gepa 0.1.4 `core/state.py`).
  */
class GepaObjectiveEngineSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  import ReflectionFixtures.reply

  private val first = OptimizableId(0)

  private val improved = "Answer with the CITY name only."

  private val wrong: Map[String, Double] = Map("correctness" -> 0.0, "incorrectness" -> 1.0)
  private val right: Map[String, Double] = Map("correctness" -> 1.0, "incorrectness" -> 0.0)
  private val none: Map[String, Double]  = Map.empty

  /** The task LM.
    *   - A question with the word BROKEN gets an error, so the run of the program fails.
    *   - A question with the word HELDOUT gets an error only when the prompt has the token CITY.
    *   - Each other question gets "Paris" when the prompt has the token CITY, and "WRONG" otherwise.
    */
  private final class TaskLm extends LanguageModel:
    val calls: AtomicInteger                                                                   = AtomicInteger(0)
    override val id: String                                                                    = "task"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _       = calls.incrementAndGet()
      val prompt  = request.messages.flatMap(_.text).mkString("\n")
      val hasCity = prompt.contains("CITY")
      if prompt.contains("BROKEN") || (hasCity && prompt.contains("HELDOUT")) then
        Left(RuntimeError("task_lm", "the task model failed"))
      else
        val answer = if hasCity then "Paris" else "WRONG"
        Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## answer ## ]]\n$answer\n\n[[ ## completed ## ]]"))))

  /** Gives `improved` for each request. Records the text of each request. */
  private final class ReflectionLm extends LanguageModel:
    val requests: ArrayBuffer[String]                                                          = ArrayBuffer.empty
    override val id: String                                                                    = "reflect"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = requests.synchronized(requests += request.messages.flatMap(_.text).mkString("\n"))
      Right(LmResponse(outputs = Vector(LmOutput(text = reply(improved)))))

  /** The upstream test metric: `score = (correct + 1) / 2`, `correctness = correct`, `incorrectness = 1 - correct`.
    *
    * @param objectives
    *   gives the objective scores of one example from its `correct` value (1.0 or 0.0) and its question
    * @param scoreOf
    *   gives the score of one example from its `correct` value
    */
  private final class GradedMetric(
      objectives: (Double, String) => Map[String, Double] = (correct, _) =>
        Map("correctness" -> correct, "incorrectness" -> (1 - correct)),
      scoreOf: Double => Double = correct => (correct + 1) / 2
  ) extends FeedbackMetric:
    val programCalls: AtomicInteger  = AtomicInteger(0)
    val traceSizes: ArrayBuffer[Int] = ArrayBuffer.empty
    override def name: String        = "graded"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val gold     = example.get("answer").map(DynamicValues.renderText).getOrElse("")
      val got      = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
      val question = example.get("question").map(DynamicValues.renderText).getOrElse("")
      val correct  = if got == gold then 1.0 else 0.0
      if component.isEmpty then
        val _ = programCalls.incrementAndGet()
        val _ = traceSizes.synchronized(traceSizes += trace.size)
      Right(ScoreWithFeedback(scoreOf(correct), s"expected '$gold', got '$got'", objectives(correct, question)))

  private def noObjectives: GradedMetric = new GradedMetric(objectives = (_, _) => Map.empty)

  private val program: DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some("Answer.")))

  private def example(question: String): Example =
    Example(values = DynamicValues.record("question" := question, "answer" := "Paris"), inputKeys = Set("question"))

  private val dataset: Vector[Example] = (1 to 4).toVector.map(i => example(s"Capital of France ($i)?"))

  private val seedCandidate: Candidate = Candidate.seed(program)

  /** The seed validation costs 4 calls. One iteration costs 8 at most: 2 for the parent, 2 for the child, and 4 for the
    * validation of an accepted child. A budget of 12 thus holds one iteration exactly.
    */
  private def oneIteration(frontierType: FrontierType = FrontierType.Instance): GepaConfig =
    GepaConfig(
      budget = GepaBudget.MetricCalls(MetricCallCount(12)),
      reflectionMinibatchSize = MinibatchSize(2),
      frontierType = frontierType,
      seed = 1L
    )

  private def withTaskLm[A](lm: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }

  private def optimize(
      metric      : FeedbackMetric,
      reflectionLm: LanguageModel,
      config      : GepaConfig,
      trainset    : Vector[Example] = dataset,
      valset      : Vector[Example] = dataset,
      runDir      : Option[Path]    = None
  )(using RuntimeContext): Either[DspyError, GepaResult[DynamicPredict]] =
    new GepaEngine(new GepaAdapter(program, metric), reflectionLm, config.copy(runDir = runDir))
      .optimize(seedCandidate, trainset, valset)

  private def withTempDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("gepa-objective")
    try body(dir)
    finally
      val _ = Files.deleteIfExists(dir.resolve(GepaStatePersistence.fileName))
      val _ = Files.deleteIfExists(dir)

  // ── The adapter ─────────────────────────────────────────────────────────────────────────────────────────────

  test("an evaluation with no traces keeps the objective scores, with one metric call for each example") {
    val metric = new GradedMetric
    val batch  = withTaskLm(new TaskLm) {
      new GepaAdapter(program, metric).evaluate(dataset.take(3), seedCandidate, captureTraces = false)
    }
    assertEquals(batch.scores, Vector(0.5, 0.5, 0.5))
    assertEquals(batch.objectiveScores, Some(Vector(wrong, wrong, wrong)))
    assertEquals(batch.trajectories, None)
    assertEquals(metric.programCalls.get(), 3)
  }

  test("an evaluation with traces keeps the objective scores in the batch and in each trajectory") {
    val metric = new GradedMetric
    val batch  = withTaskLm(new TaskLm) {
      new GepaAdapter(program, metric).evaluate(dataset.take(2), Map(first -> Some(improved)), captureTraces = true)
    }
    assertEquals(batch.scores, Vector(1.0, 1.0))
    assertEquals(batch.objectiveScores, Some(Vector(right, right)))
    assertEquals(batch.trajectories.map(_.map(_.objectiveScores)), Some(Vector(right, right)))
    assertEquals(metric.programCalls.get(), 2)
  }

  test("a metric with no objective score gives no objective scores for the batch") {
    withTaskLm(new TaskLm) {
      val adapter = new GepaAdapter(program, noObjectives)
      assertEquals(adapter.evaluate(dataset.take(2), seedCandidate, captureTraces = false).objectiveScores, None)
      assertEquals(adapter.evaluate(dataset.take(2), seedCandidate, captureTraces = true).objectiveScores, None)
    }
  }

  test("an example with no objective score has an empty map, on the two paths") {
    // Only the first question gets objective scores (upstream `test_gepa_adapter_forwards_sparse_objective_scores`).
    val sparse = new GradedMetric(objectives = (_, question) => if question.contains("(1)") then wrong else none)
    withTaskLm(new TaskLm) {
      val adapter = new GepaAdapter(program, sparse)
      assertEquals(
        adapter.evaluate(dataset.take(2), seedCandidate, captureTraces = false).objectiveScores,
        Some(Vector(wrong, none))
      )
      assertEquals(
        adapter.evaluate(dataset.take(2), seedCandidate, captureTraces = true).objectiveScores,
        Some(Vector(wrong, none))
      )
    }
  }

  test("a run that fails has the failure score and no objective score") {
    val batchWithFailure = Vector(dataset(0), example("BROKEN question?"), dataset(1))
    withTaskLm(new TaskLm) {
      val adapter    = new GepaAdapter(program, new GradedMetric, failureScore = -1.0)
      val scoresOnly = adapter.evaluate(batchWithFailure, seedCandidate, captureTraces = false)
      assertEquals(scoresOnly.scores, Vector(0.5, -1.0, 0.5))
      assertEquals(scoresOnly.objectiveScores, Some(Vector(wrong, none, wrong)))

      val withTraces = adapter.evaluate(batchWithFailure, seedCandidate, captureTraces = true)
      assertEquals(withTraces.scores, Vector(0.5, -1.0, 0.5))
      assertEquals(withTraces.objectiveScores, Some(Vector(wrong, none, wrong)))

      // Each run fails: no example has an objective score.
      val allFail = adapter.evaluate(Vector(example("BROKEN one?"), example("BROKEN two?")), seedCandidate, false)
      assertEquals(allFail.objectiveScores, None)
    }
  }

  test("an evaluation with no traces keeps the score of each run that passes, for any number of failures") {
    // Upstream gives `Evaluate` an error limit of 100 times the batch size, so the limit never applies.
    val broken = (1 to 12).toVector.map(i => example(s"BROKEN question $i?"))
    val batch  = withTaskLm(new TaskLm) {
      new GepaAdapter(program, new GradedMetric).evaluate(broken ++ dataset.take(2), seedCandidate, false)
    }
    assertEquals(batch.scores, Vector.fill(12)(0.0) ++ Vector(0.5, 0.5))
    assertEquals(batch.objectiveScores.map(_.drop(12)), Some(Vector(wrong, wrong)))
  }

  test("the metric gets the trace only in an evaluation with traces") {
    val metric = new GradedMetric
    withTaskLm(new TaskLm) {
      val adapter = new GepaAdapter(program, metric)
      val _       = adapter.evaluate(dataset.take(1), seedCandidate, captureTraces = false)
      assertEquals(metric.traceSizes.toVector, Vector(0))
      val _ = adapter.evaluate(dataset.take(1), seedCandidate, captureTraces = true)
      assertEquals(metric.traceSizes.toVector, Vector(0, 1))
    }
  }

  // ── The evaluation cache ────────────────────────────────────────────────────────────────────────────────────

  test("the cache gives the objective scores of the first evaluation, with no second evaluation") {
    val lm    = new TaskLm
    val cache = new GepaEvalCache[DynamicPredict](new GepaAdapter(program, new GradedMetric))
    withTaskLm(lm) {
      val batch = dataset.take(2)
      assertEquals(cache.scored(seedCandidate, batch), ScoredBatch(Vector(0.5, 0.5), Vector(wrong, wrong), 2))
      assertEquals(lm.calls.get(), 2)
      assertEquals(cache.scored(seedCandidate, batch), ScoredBatch(Vector(0.5, 0.5), Vector(wrong, wrong), 0))
      assertEquals(cache.scores(seedCandidate, batch), (Vector(0.5, 0.5), 0))
      assertEquals(lm.calls.get(), 2)
    }
  }

  test("the cache gives an empty map for an example with no objective score") {
    val cache = new GepaEvalCache[DynamicPredict](new GepaAdapter(program, noObjectives))
    withTaskLm(new TaskLm) {
      assertEquals(cache.scored(seedCandidate, dataset.take(2)), ScoredBatch(Vector(0.5, 0.5), Vector(none, none), 2))
    }
  }

  test("restored objective scores warm the cache") {
    val lm    = new TaskLm
    val cache = new GepaEvalCache[DynamicPredict](new GepaAdapter(program, new GradedMetric))
    val batch = dataset.take(2)
    assertEquals(cache.restore(seedCandidate, batch, Vector(0.25, 0.75), Vector(right, none)), Right(()))
    // A restore with no objective scores gives each example an empty map.
    val other = Map(first -> Some("Another instruction."))
    assertEquals(cache.restore(other, batch, Vector(0.1, 0.2)), Right(()))
    withTaskLm(lm) {
      assertEquals(cache.scored(seedCandidate, batch), ScoredBatch(Vector(0.25, 0.75), Vector(right, none), 0))
      assertEquals(cache.scored(other, batch), ScoredBatch(Vector(0.1, 0.2), Vector(none, none), 0))
      assertEquals(lm.calls.get(), 0)
    }
    assert(cache.restore(other, batch, Vector(0.1, 0.2), Vector(right)).isLeft)
  }

  // ── The engine ──────────────────────────────────────────────────────────────────────────────────────────────

  test("a run keeps the objective scores of each candidate, with the default frontier type") {
    assertEquals(GepaConfig(budget = GepaBudget.MetricCalls(MetricCallCount(12))).frontierType, FrontierType.Instance)
    val result = withTaskLm(new TaskLm)(optimize(new GradedMetric, new ReflectionLm, oneIteration()))
      .fold(error => fail(error.message), identity)

    assertEquals(result.numCandidates.toInt, 2)
    assertEquals(result.state.frontierType, FrontierType.Instance)
    assertEquals(result.state.candidates.toVector, Vector(seedCandidate, Map(first -> Some(improved))))
    assertEquals(result.state.objectiveScoresOf(0), Vector.fill(4)(wrong))
    assertEquals(result.state.objectiveScoresOf(1), Vector.fill(4)(right))
    assertEquals(result.state.objectiveBest, Map("correctness" -> Set(1), "incorrectness" -> Set(0)))
    assertEquals(result.state.objectiveFront, Map("correctness" -> 1.0, "incorrectness" -> 1.0))
    assertEquals(result.state.totalMetricCalls, result.totalMetricCalls)
  }

  test("a run with a metric that has no objective score keeps no objective data") {
    val result = withTaskLm(new TaskLm)(optimize(noObjectives, new ReflectionLm, oneIteration()))
      .fold(error => fail(error.message), identity)
    assertEquals(result.numCandidates.toInt, 2)
    assertEquals(result.state.valObjectiveScores, Vector.empty)
  }

  test("a run with the objective type has one front for each objective") {
    val result =
      withTaskLm(new TaskLm)(optimize(new GradedMetric, new ReflectionLm, oneIteration(FrontierType.Objective)))
        .fold(error => fail(error.message), identity)

    assertEquals(result.state.frontierType, FrontierType.Objective)
    assertEquals(
      result.state.fronts,
      Vector[(FrontierKey, Set[Int])](
        FrontierKey.Objective("correctness")   -> Set(1),
        FrontierKey.Objective("incorrectness") -> Set(0)
      )
    )
    // The best candidate uses the score only.
    assertEquals(result.bestCandidate, Map(first -> Some(improved)))
    assertEquals(result.bestScore, 1.0)
  }

  test("a frontier type that needs objective scores stops the run after the seed evaluation when the metric has none") {
    Vector(FrontierType.Objective, FrontierType.Hybrid, FrontierType.Cartesian).foreach { frontierType =>
      val taskLm     = new TaskLm
      val reflection = new ReflectionLm
      val result     = withTaskLm(taskLm)(optimize(noObjectives, reflection, oneIteration(frontierType)))

      assert(result.left.exists(_.message.contains("objective")), s"$frontierType: $result")
      // Only the seed evaluation ran.
      assertEquals(taskLm.calls.get(), 4, frontierType.toString)
      assertEquals(reflection.requests.size, 0, frontierType.toString)
    }
  }

  test("the objective type selects a candidate that the instance type removes as dominated") {
    // After iteration 1, candidate 1 has the larger score on each validation example. With the instance type it is the
    // only candidate on the frontier. Its minibatch is perfect, so each later iteration makes no reflection call. With
    // the objective type, the seed stays on the front of `incorrectness`, so a later iteration can select it.
    def reflectionCalls(frontierType: FrontierType): Int =
      val reflection = new ReflectionLm
      val config     = oneIteration(frontierType).copy(budget = GepaBudget.MetricCalls(MetricCallCount(60)))
      val result     = withTaskLm(new TaskLm)(optimize(new GradedMetric, reflection, config))
      assert(result.isRight, result.toString)
      reflection.requests.size

    assertEquals(reflectionCalls(FrontierType.Instance), 1)
    assert(reflectionCalls(FrontierType.Objective) > 1)
  }

  test("the acceptance of a candidate uses the score only") {
    // The score is the same for a right answer and a wrong answer. The objective scores are different.
    val flat       = new GradedMetric(scoreOf = _ => 0.5)
    val reflection = new ReflectionLm
    val result     = withTaskLm(new TaskLm)(optimize(flat, reflection, oneIteration(FrontierType.Objective)))
      .fold(error => fail(error.message), identity)

    assertEquals(reflection.requests.size, 1)
    // The child has the better `correctness` and the same sum of scores on the minibatch: not accepted.
    assertEquals(result.numCandidates.toInt, 1)
    assertEquals(result.totalMetricCalls.toInt, 8)
  }

  test("a later candidate with no objective score stays in the pool, and the run continues") {
    // The child passes on the trainset. Each validation run of the child fails, so the child has no objective score.
    val valset = (1 to 4).toVector.map(i => example(s"HELDOUT question $i?"))
    val result = withTaskLm(new TaskLm) {
      optimize(new GradedMetric, new ReflectionLm, oneIteration(FrontierType.Objective), valset = valset)
    }.fold(error => fail(error.message), identity)

    assertEquals(result.numCandidates.toInt, 2)
    assertEquals(result.state.valSubscores(1), Vector.fill(4)(0.0))
    assertEquals(result.state.objectiveScoresOf(1), Vector.fill(4)(none))
    assertEquals(result.state.objectiveBest, Map("correctness" -> Set(0), "incorrectness" -> Set(0)))
    // The seed has the larger mean score.
    assertEquals(result.bestCandidate, seedCandidate)
  }

  // ── Resume ──────────────────────────────────────────────────────────────────────────────────────────────────

  test("a resumed run keeps the objective scores and makes no second evaluation") {
    withTempDir { dir =>
      val config = oneIteration(FrontierType.Objective)
      val before = withTaskLm(new TaskLm)(optimize(new GradedMetric, new ReflectionLm, config, runDir = Some(dir)))
        .fold(error => fail(error.message), identity)

      val taskLm = new TaskLm
      val after  = withTaskLm(taskLm)(optimize(new GradedMetric, new ReflectionLm, config, runDir = Some(dir)))
        .fold(error => fail(error.message), identity)

      assertEquals(after.state, before.state)
      assertEquals(after.state.objectiveScoresOf(1), Vector.fill(4)(right))
      // The budget is used, so the second run makes no iteration and no evaluation.
      assertEquals(taskLm.calls.get(), 0)
    }
  }

  test("a resumed run with another frontier type is an error") {
    withTempDir { dir =>
      val saved = withTaskLm(new TaskLm) {
        optimize(new GradedMetric, new ReflectionLm, oneIteration(FrontierType.Objective), runDir = Some(dir))
      }
      assert(saved.isRight, saved.toString)

      val taskLm = new TaskLm
      val result = withTaskLm(taskLm) {
        optimize(new GradedMetric, new ReflectionLm, oneIteration(FrontierType.Instance), runDir = Some(dir))
      }
      assert(result.left.exists(_.message.contains("frontier")), result.toString)
      assertEquals(taskLm.calls.get(), 0)
    }
  }
