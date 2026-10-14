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
import scala.util.Random

/** The rules of the objective scores that [[GepaObjectiveFrontierSuite]] and [[GepaObjectiveEngineSuite]] do not cover
  * (PORT_GAPS G-45, item O4): a metric that gives no score, the conflict rule of the evaluation cache, the fields of a
  * snapshot, a checkpoint with defects, and the validation of a merge.
  */
class GepaObjectiveRulesSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val first  = OptimizableId(0)
  private val second = OptimizableId(1)

  private val quality: Map[String, Double] = Map("quality" -> 1.0)
  private val none: Map[String, Double]    = Map.empty

  /** Gives the answer "Paris" for each request. Counts the requests. */
  private final class AnswerLm extends LanguageModel:
    val calls: AtomicInteger                                                                   = AtomicInteger(0)
    override val id: String                                                                    = "answer"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = calls.incrementAndGet()
      Right(LmResponse(outputs = Vector(LmOutput(text = "[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"))))

  /** Gives the score 1.0 and the objective score `quality`.
    *   - A question with the word UNSCORED gets a `Left`.
    *   - A question with the word THROWS gets an exception.
    */
  private val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "rules"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val question = example.get("question").map(DynamicValues.renderText).getOrElse("")
      if question.contains("UNSCORED") then Left(RuntimeError("metric", "the metric has no score"))
      else if question.contains("THROWS") then throw IllegalStateException("the metric failed")
      else Right(ScoreWithFeedback(1.0, "ok", quality))

  private def predict(layout: String, instruction: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(layout).toOption.get.withInstructions(Some(instruction)))

  private val program: DynamicPredict = predict("question -> answer", "Answer.")

  private def example(question: String): Example =
    Example(values = DynamicValues.record("question" := question, "answer" := "Paris"), inputKeys = Set("question"))

  private val dataset: Vector[Example] = Vector(example("One?"), example("Two?"))

  private val seedCandidate: Candidate = Candidate.seed(program)

  private def withLm[A](lm: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }

  private def withTempDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("gepa-objective-rules")
    try body(dir)
    finally
      val _ = Files.deleteIfExists(dir.resolve(GepaStatePersistence.fileName))
      val _ = Files.deleteIfExists(dir)

  // ── The adapter ─────────────────────────────────────────────────────────────────────────────────────────────

  test("a metric that gives a Left has the failure score and no objective score, on the two paths") {
    val batch = Vector(example("One?"), example("UNSCORED?"), example("Three?"))
    withLm(new AnswerLm) {
      val adapter = new GepaAdapter(program, metric, failureScore = -1.0)

      val scoresOnly = adapter.evaluate(batch, seedCandidate, captureTraces = false)
      assertEquals(scoresOnly.scores, Vector(1.0, -1.0, 1.0))
      assertEquals(scoresOnly.objectiveScores, Some(Vector(quality, none, quality)))
      // The output is empty, as it is for a run that fails.
      assertEquals(scoresOnly.outputs(1), RawPrediction.empty)

      val withTraces = adapter.evaluate(batch, seedCandidate, captureTraces = true)
      assertEquals(withTraces.scores, Vector(1.0, -1.0, 1.0))
      assertEquals(withTraces.objectiveScores, Some(Vector(quality, none, quality)))
      // The run passed, so the trajectory keeps the prediction.
      val unscored = withTraces.trajectories.map(_(1)).getOrElse(fail("the batch has no trajectories"))
      assertEquals(unscored.failed, false)
      assertEquals(unscored.objectiveScores, none)
      assertEquals(unscored.prediction.get("answer").map(DynamicValues.renderText), Some("Paris"))
    }
  }

  test("a metric that throws gives the failure score to its example only, on the two paths") {
    val batch = Vector(example("One?"), example("THROWS?"), example("Three?"))
    withLm(new AnswerLm) {
      val adapter = new GepaAdapter(program, metric, failureScore = -1.0)
      Vector(false, true).foreach { captureTraces =>
        val result = adapter.evaluate(batch, seedCandidate, captureTraces)
        assertEquals(result.scores, Vector(1.0, -1.0, 1.0), s"captureTraces=$captureTraces")
        assertEquals(result.objectiveScores, Some(Vector(quality, none, quality)), s"captureTraces=$captureTraces")
        assertEquals(result.outputs(1), RawPrediction.empty, s"captureTraces=$captureTraces")
      }
    }
  }

  // ── The evaluation cache ────────────────────────────────────────────────────────────────────────────────────

  private def newCache: GepaEvalCache[DynamicPredict] = new GepaEvalCache(new GepaAdapter(program, metric))

  test("a restored pair with different objective scores is rejected, and the cache does not change") {
    val cache = newCache
    val batch = Vector(example("One?"), example("Two?"))
    assertEquals(cache.restore(seedCandidate, batch, Vector(0.5, 0.5), Vector(quality, none)), Right(()))
    // The same result for the same pair is not a conflict.
    assertEquals(cache.restore(seedCandidate, batch, Vector(0.5, 0.5), Vector(quality, none)), Right(()))

    // A different value, a different set of names, and no objective score.
    assert(cache.restore(seedCandidate, batch, Vector(0.5, 0.5), Vector(Map("quality" -> 0.2), none)).isLeft)
    assert(cache.restore(seedCandidate, batch, Vector(0.5, 0.5), Vector(quality, Map("cost" -> 0.1))).isLeft)
    assert(cache.restore(seedCandidate, batch, Vector(0.5, 0.5)).isLeft)
    // The score rule stays.
    assert(cache.restore(seedCandidate, batch, Vector(0.5, 0.9), Vector(quality, none)).isLeft)

    // The first pair of this call is new, and the second pair has a conflict: the cache gets no pair.
    val third = example("Three?")
    assert(cache.restore(seedCandidate, Vector(third, batch(0)), Vector(0.9, 0.5), Vector(none, none)).isLeft)
    assertEquals(cache.uncachedCount(seedCandidate, Vector(third)), 1)

    val lm = new AnswerLm
    withLm(lm) {
      assertEquals(cache.scored(seedCandidate, batch), ScoredBatch(Vector(0.5, 0.5), Vector(quality, none), 0))
      assertEquals(lm.calls.get(), 0)
    }
  }

  test("an example that is two times in a restored batch must have one result") {
    val cache = newCache
    val twice = Vector(example("One?"), example("One?"))
    assert(cache.restore(seedCandidate, twice, Vector(0.5, 0.5), Vector(quality, none)).isLeft)
    assert(cache.restore(seedCandidate, twice, Vector(0.5, 0.9), Vector(quality, quality)).isLeft)
    assertEquals(cache.uncachedCount(seedCandidate, twice), 1)
    assertEquals(cache.restore(seedCandidate, twice, Vector(0.5, 0.5), Vector(quality, quality)), Right(()))
    assertEquals(cache.uncachedCount(seedCandidate, twice), 0)
  }

  // ── Persistence ─────────────────────────────────────────────────────────────────────────────────────────────

  test("a state with the instance type and no objective data writes the fields of an older snapshot only") {
    val plain = GepaStatePersistence.toJson(GepaState.seed(seedCandidate, Vector(0.5, 0.5), metricCalls = 2))
    assert(!plain.contains("valObjectiveScores"), plain)
    assert(!plain.contains("frontierType"), plain)

    val objective = GepaStatePersistence.toJson(
      GepaState.seed(seedCandidate, Vector(0.5, 0.5), 2, Vector(quality, none), FrontierType.Hybrid)
    )
    assert(objective.contains(""""valObjectiveScores":[[{"quality":1.0},{}]]"""), objective)
    assert(objective.contains(""""frontierType":"Hybrid""""), objective)
  }

  test("a snapshot with objective scores that are not aligned with the scores is rejected") {
    def snapshot(objectiveScores: String): String =
      s"""{"candidates":[{"optimizable-0":"c0"}],"valSubscores":[[0.5,0.5]],"parents":[[]],"totalMetricCalls":2,"valObjectiveScores":$objectiveScores}"""
    assertEquals(
      GepaStatePersistence.fromJson(snapshot("""[[{"quality":1.0},{}]]""")).map(_.objectiveScoresOf(0)),
      Right(Vector(quality, none))
    )
    assert(GepaStatePersistence.fromJson(snapshot("""[[{"quality":1.0}]]""")).isLeft)
    assert(GepaStatePersistence.fromJson(snapshot("""[[{"quality":1.0},{}],[{},{}]]""")).isLeft)
  }

  // ── The engine ──────────────────────────────────────────────────────────────────────────────────────────────

  private def resume(dir: Path, frontierType: FrontierType, valset: Vector[Example])(using
      RuntimeContext
  ): Either[DspyError, GepaResult[DynamicPredict]] =
    val config = GepaConfig(
      budget = GepaBudget.MetricCalls(MetricCallCount(2)),
      reflectionMinibatchSize = MinibatchSize(2),
      frontierType = frontierType,
      runDir = Some(dir)
    )
    new GepaEngine(new GepaAdapter(program, metric), new AnswerLm, config)
      .optimize(seedCandidate, dataset, valset)

  test("a checkpoint with a frontier type that needs objective scores and with no objective data is an error") {
    withTempDir { dir =>
      val saved =
        GepaState.seed(seedCandidate, Vector(1.0, 1.0), metricCalls = 2, frontierType = FrontierType.Cartesian)
      GepaStatePersistence.save(dir, saved)
      val lm     = new AnswerLm
      val result = withLm(lm)(resume(dir, FrontierType.Cartesian, dataset))
      assert(result.left.exists(_.message.contains("objective")), result.toString)
      assertEquals(lm.calls.get(), 0)
    }
  }

  test("a checkpoint with two results for one validation example is an invalid checkpoint") {
    withTempDir { dir =>
      val twice = Vector(dataset(0), dataset(0))
      GepaStatePersistence.save(dir, GepaState.seed(seedCandidate, Vector(1.0, 1.0), 2, Vector(quality, none)))
      // A checkpoint error is a `Left` (decision of the owner, 2026-10-13). Before, it was an exception.
      val result = withLm(new AnswerLm)(resume(dir, FrontierType.Instance, twice))
      assert(result.left.exists(_.message.contains("objective scores")), result.toString)
    }
  }

  /** Stage `a` answers "ok" only when its instruction has FIXED_A. Stage `b` answers "ok" only with FIXED_B. */
  private final class StageLm extends LanguageModel:
    override val id: String                                                                    = "stage"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val prompt          = request.messages.flatMap(_.text).mkString("\n")
      val (field, marker) = if prompt.contains("## a ##") then ("a", "FIXED_A") else ("b", "FIXED_B")
      val value           = if prompt.contains(marker) then "ok" else "bad"
      Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## $field ## ]]\n$value\n\n[[ ## completed ## ]]"))))

  /** Gives the component of the request its fix. */
  private final class StageReflectionLm extends LanguageModel:
    override val id: String                                                                    = "reflect"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val prompt    = request.messages.flatMap(_.text).mkString("\n")
      val component = if prompt.contains("Component A") then "A" else "B"
      Right(LmResponse(outputs =
        Vector(LmOutput(text = ReflectionFixtures.reply(s"Component $component. FIXED_$component")))
      ))

  /** The score is that of the stage metric of [[MergeProposerSuite]]. A training example has no `stage` field: each
    * stage that answers "ok" gives half of the score. A validation example has a `stage` field: only that stage gives
    * the score. The objective scores `a` and `b` are 1.0 for a stage that answers "ok".
    */
  private def stageMetric: FeedbackMetric =
    new FeedbackMetric:
      override def name: String = "stage_score"
      override def feedback(
          example       : Example,
          prediction    : RawPrediction,
          trace         : Vector[TraceEntry],
          component     : Option[String],
          componentTrace: Vector[TraceEntry]
      )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
        def ok(stage: String): Double =
          if prediction.get(stage).map(DynamicValues.renderText).contains("ok") then 1.0 else 0.0
        val score = example.get("stage").map(DynamicValues.renderText) match
          case Some(stage) => ok(stage)
          case None        => (ok("a") + ok("b")) * 0.5
        Right(ScoreWithFeedback(score, "Each stage must answer ok.", Map("a" -> ok("a"), "b" -> ok("b"))))

  /** Always selects the seed. A reflective mutation then changes one component of the seed only, so only a merge gives
    * a candidate with the two fixes.
    */
  private object SeedOnly extends CandidateSelector:
    def select(state: GepaState, rng: Random): Int = 0

  test("the validation of an accepted merge gives its objective scores to the state") {
    val twoStage =
      TwoStage(first = predict("question -> a", "Component A."), second = predict("question -> b", "Component B."))
    val trainset = (1 to 4).toVector.map(i =>
      Example(values = DynamicValues.record("question" := s"Question $i?"), inputKeys = Set("question"))
    )
    val valset = Vector("a", "a", "b", "b").zipWithIndex.map((stage, i) =>
      Example(
        values = DynamicValues.record("question" := s"Validation $i?", "stage" := stage),
        inputKeys = Set("question")
      )
    )
    // The budget holds the seed validation (4 calls), two reflective mutations (8 calls each), and one merge (4 calls).
    val config = GepaConfig(
      budget = GepaBudget.MetricCalls(MetricCallCount(24)),
      reflectionMinibatchSize = MinibatchSize(2),
      candidateSelector = SeedOnly,
      mergeValOverlapFloor = MergeOverlapFloor(4),
      seed = 1L
    )
    val result = withLm(new StageLm) {
      new GepaEngine(new GepaAdapter[TwoStage](twoStage, stageMetric), new StageReflectionLm, config)
        .optimize(Candidate.seed(twoStage), trainset, valset)
    }.fold(error => fail(error.message), identity)

    // The two mutations change one component of the seed each. The merge then takes the two fixes.
    assertEquals(result.state.parents, Vector(Vector.empty, Vector(0), Vector(0), Vector(1, 2)))
    assertEquals(
      result.state.candidates(3),
      Map(first -> Some("Component A. FIXED_A"), second -> Some("Component B. FIXED_B"))
    )
    // The subsample of the merge and its validation use the same cache entries.
    assertEquals(result.state.objectiveScoresOf(3), Vector.fill(4)(Map("a" -> 1.0, "b" -> 1.0)))
    assertEquals(result.state.objectiveScoresOf(0), Vector.fill(4)(Map("a" -> 0.0, "b" -> 0.0)))
    assertEquals(result.state.objectiveBest, Map("a" -> Set(1, 3), "b" -> Set(2, 3)))
  }
