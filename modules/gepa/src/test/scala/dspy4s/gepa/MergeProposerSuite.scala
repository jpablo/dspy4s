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
    val merger = new MergeProposer[Pipeline](
      valset,
      maxMergeInvocations = MergeInvocationLimit(5),
      new Random(0),
      new GepaEvalCache(adapter)
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
        val merger = new MergeProposer[Pipeline](
          valset3,
          maxMergeInvocations = MergeInvocationLimit(5),
          new Random(seed),
          new GepaEvalCache(adapter)
        )
        merger.propose(state).map(_.parents)
      }.toSet
      assertEquals(parents, Set(Option(Vector(2, 3))))
    }
  }
