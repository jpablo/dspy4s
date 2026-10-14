package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
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
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.programs.optimization.OptimizableId
import munit.FunSuite

import java.nio.file.Files
import scala.util.Random

class MergeProposerSuite extends FunSuite:

  private val first  = OptimizableId(0)
  private val second = OptimizableId(1)

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Two predictors must BOTH carry their token for "Paris": hinter emits good_hint only with TOKEN1; answerer emits
    * Paris only with TOKEN2 AND a good_hint. So a merge of a hinter-fixed and an answerer-fixed descendant is what
    * unlocks the correct answer.
    */
  private final class PipelineLm extends LanguageModel:
    override val id: String                                                                    = "pipeline"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val prompt = request.messages.flatMap(_.text).mkString("\n")
      if prompt.contains("## answer ##") then
        val answer = if prompt.contains("TOKEN2") && prompt.contains("good_hint") then "Paris" else "WRONG"
        Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## answer ## ]]\n$answer\n\n[[ ## completed ## ]]"))))
      else
        val hint = if prompt.contains("TOKEN1") then "good_hint" else "bad_hint"
        Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## hint ## ]]\n$hint\n\n[[ ## completed ## ]]"))))

  private val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "exact_answer"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val gold = example.get("answer").map(DynamicValues.renderText).getOrElse("")
      val got  = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
      Right(ScoreWithFeedback(if got == gold then 1.0 else 0.0, s"expected '$gold', got '$got'"))

  private def predict(layout: String, instruction: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(layout).toOption.get.withInstructions(Some(instruction)))

  private val basePipeline =
    Pipeline(predict("question -> hint", "Stage one."), predict("question, hint -> answer", "Stage two."))

  private val valset: Vector[Example] = (1 to 2).toVector.map(i =>
    Example(
      values = DynamicValues.record("question" := s"Capital ($i)?", "answer" := "Paris"),
      inputKeys = Set("question")
    )
  )

  // A seed and two children of it. Each child changes one stage, so the two children are a valid merge pair.
  private val seedCand = Map(first -> Some("Stage one."), second -> Some("Stage two."))
  private val hinter   = Map(first -> Some("Use TOKEN1 to produce a good_hint."), second -> Some("Stage two."))
  private val answerer = Map(first -> Some("Stage one."), second -> Some("Use TOKEN2 with the good_hint to answer."))

  // ── Pure crossover helpers ──────────────────────────────────────────────────────────────────────────────────

  test("crossover takes each component from whichever descendant improved it (complementary gains stacked)") {
    val ancestor       = Map(first -> Some("A0"), second -> Some("B0"))
    val id1            = Map(first -> Some("A1"), second -> Some("B0")) // changed first, kept second
    val id2            = Map(first -> Some("A0"), second -> Some("B1")) // kept first, changed second
    val (merged, desc) = MergeProposer.crossover(ancestor, 1, id1, 2, id2, _ => 0.0, new Random(0))
    assertEquals(merged, Map(first -> Some("A1"), second -> Some("B1"))) // first from id1, second from id2
    assertEquals(desc, Vector(1, 2)) // components sorted (a, b) -> sources (id1, id2)
  }

  test("crossover breaks a both-changed component toward the higher-scoring descendant") {
    val ancestor    = Map(first -> Some("A0"))
    val (merged, _) = MergeProposer.crossover(
      ancestor,
      1,
      Map(first -> Some("A1")),
      2,
      Map(first -> Some("A2")),
      aggregateScore = Map(1 -> 0.3, 2 -> 0.9),
      new Random(0)
    )
    assertEquals(merged(first), Some("A2")) // id2 scores higher
  }

  test("hasDesirableComponents requires a component changed by exactly one descendant") {
    val anc = Map(first -> Some("A0"), second -> Some("B0"))
    assert(MergeProposer.hasDesirableComponents(
      anc,
      Map(first -> Some("A1"), second -> Some("B0")),
      Map(first -> Some("A0"), second -> Some("B1"))
    ))
    // Both descendants changed every component -> no component is "anchored" to the ancestor -> not desirable.
    assert(
      !MergeProposer.hasDesirableComponents(
        Map(first -> Some("A0")),
        Map(first -> Some("A1")),
        Map(first -> Some("A2"))
      )
    )
  }

  test("selectSubsample returns up to `num` valid, distinct-where-possible indices stratified by disagreement") {
    val s1  = Vector(1.0, 0.0, 1.0, 0.0, 1.0)
    val s2  = Vector(0.0, 1.0, 0.0, 1.0, 0.0)
    val sub = MergeProposer.selectSubsample(s1, s2, num = 4, new Random(1))
    assertEquals(sub.size, 4)
    sub.foreach(i => assert(i >= 0 && i < 5))
  }

  // ── End-to-end propose over a two-stage program ─────────────────────────────────────────────────────────────

  test("propose merges two complementary frontier descendants and the merged program clears the subsample gate") {
    val adapter = new GepaAdapter[Pipeline](basePipeline, metric)
    // Hand-built lineage: seed 0 -> 1 (fixed the hinter) and 0 -> 2 (fixed the answerer). Per-instance subscores are
    // set so that 1 and 2 are the Pareto dominators (each best on one val instance) and 0 is dominated, making the
    // (1, 2, ancestor=0) triplet the only candidate pair.
    val seedCand = Map(first -> Some("Stage one."), second -> Some("Stage two."))
    val cand1    = Map(first -> Some("Use TOKEN1 to produce a good_hint."), second -> Some("Stage two."))
    val cand2    = Map(first -> Some("Stage one."), second -> Some("Use TOKEN2 with the good_hint to answer."))
    val state    = GepaState(
      candidates = CandidatePool.applyUnsafe(Vector(seedCand, cand1, cand2)),
      valSubscores = Vector(Vector(0.0, 0.0), Vector(1.0, 0.0), Vector(0.0, 1.0)),
      parents = Vector(Vector.empty, Vector(0), Vector(0)),
      totalMetricCalls = MetricCallCount(0)
    )
    // The validation set has 2 examples, so the test sets the overlap floor below gepa's default of 5.
    val merger = new MergeProposer[Pipeline](
      valset,
      maxMergeInvocations = MergeInvocationLimit(5),
      new Random(0),
      new GepaEvalCache(adapter),
      valOverlapFloor = valset.size
    )

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new PipelineLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val proposal         = merger.propose(state)

      assert(proposal.isDefined, "a mergeable triplet (1, 2, ancestor 0) should be found")
      val p = proposal.get
      assertEquals(p.parents, Vector(1, 2))
      // The merge stacks both fixes: hinter from cand1 (TOKEN1), answerer from cand2 (TOKEN2).
      assert(p.candidate(first).exists(_.contains("TOKEN1")), p.candidate(first))
      assert(p.candidate(second).exists(_.contains("TOKEN2")), p.candidate(second))
      // With both fixed the merged program answers "Paris", beating either parent on the subsample.
      assert(p.accepted, "merged program should clear the subsample gate")
    }
  }

  test("propose returns None when there are fewer than three candidates (no ancestor to merge over)") {
    val adapter = new GepaAdapter[Pipeline](basePipeline, metric)
    val state   = GepaState(
      candidates = CandidatePool.applyUnsafe(Vector(
        Map(first -> Some("a"), second -> Some("b")),
        Map(first -> Some("c"), second -> Some("d"))
      )),
      valSubscores = Vector(Vector(1.0), Vector(1.0)),
      parents = Vector(Vector.empty, Vector(0)),
      totalMetricCalls = MetricCallCount(0)
    )
    val merger = new MergeProposer[Pipeline](
      valset,
      maxMergeInvocations = MergeInvocationLimit(5),
      new Random(0),
      new GepaEvalCache(adapter)
    )
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new PipelineLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      assertEquals(merger.propose(state), None)
    }
  }

  // ── Dominated candidates (PORT_GAPS G-44; gepa `find_dominator_programs`) ───────────────────────────────────────
  // The merge pool holds only the candidates that stay after `remove_dominated_programs`.

  test("propose finds no pair when the frontier holds a dominated candidate and one candidate that stays") {
    val adapter = new GepaAdapter[Pipeline](basePipeline, metric)
    // Front 0 holds c1 and c2. Front 1 holds c2 only. c2 covers the only front of c1, so c1 leaves the merge pool.
    // Without that rule, (1, 2, ancestor 0) is a valid triplet.
    val state = GepaState(
      candidates = CandidatePool.applyUnsafe(Vector(seedCand, hinter, answerer)),
      valSubscores = Vector(Vector(0.0, 0.0), Vector(1.0, 0.0), Vector(1.0, 1.0)),
      parents = Vector(Vector.empty, Vector(0), Vector(0)),
      totalMetricCalls = MetricCallCount(0)
    )
    // The overlap floor permits a merge on the 2 validation examples, so the floor is not the reason for the result.
    val merger = new MergeProposer[Pipeline](
      valset,
      maxMergeInvocations = MergeInvocationLimit(5),
      new Random(0),
      new GepaEvalCache(adapter),
      valOverlapFloor = valset.size
    )
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new PipelineLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      assertEquals(merger.propose(state), None)
    }
  }

  test("propose takes the two parents from the candidates that stay, never from a dominated candidate") {
    val adapter = new GepaAdapter[Pipeline](basePipeline, metric)
    val valset3 = (1 to 3).toVector.map(i =>
      Example(
        values = DynamicValues.record("question" := s"Capital ($i)?", "answer" := "Paris"),
        inputKeys = Set("question")
      )
    )
    // Front 0 holds c1 and c3. Front 1 holds c2. Front 2 holds c3. c3 covers the only front of c1, so the merge pool is
    // {c2, c3}. Without that rule, (1, 2, ancestor 0) is also a valid triplet and some seeds select it.
    val hinter3 = Map(first -> Some("Use TOKEN1 to produce a good_hint. Be brief."), second -> Some("Stage two."))
    val state   = GepaState(
      candidates = CandidatePool.applyUnsafe(Vector(seedCand, hinter, answerer, hinter3)),
      valSubscores = Vector(Vector(0.0, 0.0, 0.0), Vector(1.0, 0.0, 0.0), Vector(0.0, 1.0, 0.0), Vector(1.0, 0.0, 1.0)),
      parents = Vector(Vector.empty, Vector(0), Vector(0), Vector(0)),
      totalMetricCalls = MetricCallCount(0)
    )
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new PipelineLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val parents          = (0 until 20).map { seed =>
        // The validation set has 3 examples, so the test sets the overlap floor below gepa's default of 5.
        val merger = new MergeProposer[Pipeline](
          valset3,
          maxMergeInvocations = MergeInvocationLimit(5),
          new Random(seed),
          new GepaEvalCache(adapter),
          valOverlapFloor = valset3.size
        )
        merger.propose(state).map(_.parents)
      }.toSet
      assertEquals(parents, Set(Option(Vector(2, 3))))
    }
  }

  // ── The repeated merge description (PORT_GAPS G-44; gepa `merge.py`) ────────────────────────────────────────────
  // gepa continues with the next attempt when the merge of the found triplet was already made.

  /** Two pairs can merge: (2, 3) and (5, 6). The pair (2, 3) has two common ancestors, 0 and 1, and the two ancestors
    * give the same merged candidate. The pair (5, 6) has one valid ancestor, 4. The seed scores more than 5 and 6, so
    * no pair with one candidate of each family passes the ancestor rule.
    */
  private lazy val twoPairState: GepaState =
    val examples                                          = 8
    def row(best: Set[Int], rest: Double): Vector[Double] =
      Vector.tabulate(examples)(i => if best(i) then 1.0 else rest)
    def cand(a: String, b: String): Candidate = Map(first -> Some(a), second -> Some(b))
    GepaState(
      candidates = CandidatePool.applyUnsafe(Vector(
        cand("A0", "B0"), // 0: the seed
        cand("A1", "B0"), // 1: a child of 0
        cand("A1", "B2"), // 2: a child of 1
        cand("A3", "B0"), // 3: a child of 1, with a better score than 2
        cand("A0", "B4"), // 4: a child of 0
        cand("A5", "B4"), // 5: a child of 4
        cand("A0", "B6")  // 6: a child of 4
      )),
      valSubscores = Vector(
        Vector.fill(examples)(0.5),
        Vector.fill(examples)(0.4),
        row(Set(0), 0.5),
        row(Set(1, 2), 0.5),
        Vector.fill(examples)(0.0),
        row(Set(3), 0.0),
        row(Set(4), 0.0)
      ),
      parents = Vector(Vector.empty, Vector(0), Vector(1), Vector(1), Vector(0), Vector(4), Vector(4)),
      totalMetricCalls = MetricCallCount(0)
    )

  private lazy val valset8: Vector[Example] = (1 to 8).toVector.map(i =>
    Example(
      values = DynamicValues.record("question" := s"Capital ($i)?", "answer" := "Paris"),
      inputKeys = Set("question")
    )
  )

  test("the frontier of the two-pair state has the two pairs and no ancestor") {
    assertEquals(twoPairState.nonDominatedFrontier.values.flatten.toSet, Set(2, 3, 5, 6))
  }

  test("propose continues with a different pair when the merge of the found triplet was already made") {
    val adapter = new GepaAdapter[Pipeline](basePipeline, metric)
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new PipelineLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      // After the merge of (2, 3) over one ancestor, the other ancestor gives the same merge again. A search that stops
      // at that triplet finds no second merge for approximately one seed of three.
      val found = (0 until 100).count { seed =>
        val merger = new MergeProposer[Pipeline](
          valset8,
          maxMergeInvocations = MergeInvocationLimit(5),
          new Random(seed),
          new GepaEvalCache(adapter)
        )
        val parents = Vector(merger.propose(twoPairState), merger.propose(twoPairState)).map(_.map(_.parents))
        parents.toSet == Set(Option(Vector(2, 3)), Option(Vector(5, 6)))
      }
      assert(found >= 95, s"the search found the two merges for $found of 100 seeds")
    }
  }

  test("propose finds no merge when each triplet that stays gives a merge that was already made") {
    val adapter = new GepaAdapter[Pipeline](basePipeline, metric)
    // The first four candidates of the two-pair state: (2, 3) is the only pair, with the ancestors 0 and 1.
    val state = GepaState(
      candidates = CandidatePool.applyUnsafe(twoPairState.candidates.take(4)),
      valSubscores = twoPairState.valSubscores.take(4),
      parents = twoPairState.parents.take(4),
      totalMetricCalls = MetricCallCount(0)
    )
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new PipelineLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val merger           = new MergeProposer[Pipeline](
        valset8,
        maxMergeInvocations = MergeInvocationLimit(5),
        new Random(0),
        new GepaEvalCache(adapter)
      )
      assertEquals(merger.propose(state).map(_.parents), Some(Vector(2, 3)))
      assertEquals(merger.propose(state), None)
    }
  }

  // ── The overlap floor (PORT_GAPS G-44; gepa `val_overlap_floor`) ────────────────────────────────────────────────

  test("MergeProposer rejects a negative overlap floor") {
    val adapter = new GepaAdapter[Pipeline](basePipeline, metric)
    intercept[IllegalArgumentException] {
      new MergeProposer[Pipeline](
        valset,
        maxMergeInvocations = MergeInvocationLimit(5),
        new Random(0),
        new GepaEvalCache(adapter),
        valOverlapFloor = -1
      )
    }
  }

  // ── The merge in the engine ─────────────────────────────────────────────────────────────────────────────────

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

  /** A training example has no `stage` field: each stage that answers "ok" gives half of the score. A validation
    * example has a `stage` field: only that stage gives the score. A candidate that fixes one stage is then the best on
    * the validation examples of that stage only, so the two candidates with one fix each stay on the frontier.
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
        def ok(stage: String): Boolean = prediction.get(stage).map(DynamicValues.renderText).contains("ok")
        val score                      = example.get("stage").map(DynamicValues.renderText) match
          case Some(stage) => if ok(stage) then 1.0 else 0.0
          case None        => Vector("a", "b").count(ok) * 0.5
        Right(ScoreWithFeedback(score, "Each stage must answer ok."))

  /** Always selects the seed. A reflective mutation then changes one component of the seed only, so only a merge gives
    * a candidate with the two fixes.
    */
  private object SeedOnly extends CandidateSelector:
    def select(state: GepaState, rng: Random): Int = 0

  private lazy val twoStage: TwoStage =
    TwoStage(first = predict("question -> a", "Component A."), second = predict("question -> b", "Component B."))

  private lazy val stageTrainset: Vector[Example] = (1 to 4).toVector.map(i =>
    Example(values = DynamicValues.record("question" := s"Question $i?"), inputKeys = Set("question"))
  )

  private lazy val stageValset: Vector[Example] = Vector("a", "a", "b", "b").zipWithIndex.map((stage, i) =>
    Example(
      values = DynamicValues.record("question" := s"Validation $i?", "stage" := stage),
      inputKeys = Set("question")
    )
  )

  /** The budget holds the seed validation (4 calls), two reflective mutations (8 calls each), and one merge (4 calls).
    */
  private def stageConfig: GepaConfig =
    GepaConfig(
      budget = GepaBudget.MetricCalls(MetricCallCount(24)),
      reflectionMinibatchSize = MinibatchSize(2),
      candidateSelector = SeedOnly,
      seed = 1L
    )

  /** The state that a run of the engine saves last. */
  private def finalState(config: GepaConfig): GepaState =
    val dir = Files.createTempDirectory("gepa-merge-engine")
    try
      RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new StageLm), adapter = Some(ChatAdapter()))) {
        given RuntimeContext = RuntimeEnvironment.current
        val engine           = new GepaEngine(
          new GepaAdapter[TwoStage](twoStage, stageMetric),
          new StageReflectionLm,
          config.copy(runDir = Some(dir))
        )
        val _ = engine
          .optimize(Candidate.seed(twoStage), stageTrainset, stageValset)
          .fold(error => fail(error.message), identity)
      }
      GepaStatePersistence.load(dir).fold(error => fail(error), _.getOrElse(fail("the run saved no state")))
    finally
      val _ = Files.deleteIfExists(dir.resolve(GepaStatePersistence.fileName))
      val _ = Files.deleteIfExists(dir)

  test("the engine merges on a small validation set when the configuration sets a lower overlap floor") {
    // The validation set has 4 examples, so the default floor of 5 permits no merge.
    val state = finalState(stageConfig.copy(mergeValOverlapFloor = MergeOverlapFloor(4)))

    // The two mutations change one component of the seed each. The merge then takes the two fixes.
    assertEquals(state.parents, Vector(Vector.empty, Vector(0), Vector(0), Vector(1, 2)))
    assertEquals(
      state.candidates(3),
      Map(first -> Some("Component A. FIXED_A"), second -> Some("Component B. FIXED_B"))
    )
    assertEquals(state.aggregateScore(3), 1.0)
    // The pointer of the seed moved two times. Each child took the pointer that the seed had at that time, and the
    // merged candidate took the largest pointer of its two parents.
    assertEquals(Vector.tabulate(4)(state.pointerOf), Vector(0, 1, 0, 1))
  }

  test("a resumed run continues the merge schedule of the checkpoint") {
    val config                                   = stageConfig.copy(mergeValOverlapFloor = MergeOverlapFloor(4))
    val full                                     = finalState(config)
    val dir                                      = Files.createTempDirectory("gepa-merge-resume")
    def runIn(budget: Int): GepaResult[TwoStage] =
      RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new StageLm), adapter = Some(ChatAdapter()))) {
        given RuntimeContext = RuntimeEnvironment.current
        new GepaEngine(
          new GepaAdapter[TwoStage](twoStage, stageMetric),
          new StageReflectionLm,
          config.copy(budget = GepaBudget.MetricCalls(MetricCallCount.applyUnsafe(budget)), runDir = Some(dir))
        ).optimize(Candidate.seed(twoStage), stageTrainset, stageValset).fold(error => fail(error.message), identity)
      }
    try
      // The budget of 20 holds the seed validation and the two reflective mutations. The merge is then due.
      val partA = runIn(budget = 20)
      assertEquals(partA.state.parents, Vector(Vector.empty, Vector(0), Vector(0)))
      assertEquals(partA.stopReason, GepaStopReason.BudgetExhausted)
      assert(partA.state.merge.mergesDue > 0 && partA.state.merge.lastIterFoundNewProgram, partA.state.merge.toString)
      // The resumed run starts with the merge, as the run with no stop does.
      val partB = runIn(budget = 24)
      assertEquals(partB.state, full)
      assertEquals(partB.state.parents.last, Vector(1, 2))
      assertEquals(partB.iterations, partA.iterations + 1)
    finally
      val _ = Files.deleteIfExists(dir.resolve(GepaStatePersistence.fileName))
      val _ = Files.deleteIfExists(dir)
  }

  test("the engine makes no merge on a validation set that is smaller than the default overlap floor") {
    val state = finalState(stageConfig)
    assertEquals(state.parents, Vector(Vector.empty, Vector(0), Vector(0)))
    assertEquals(state.aggregateScore(state.bestIndex), 0.5)
  }
