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

import scala.util.Random

class MergeProposerSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  /** Two predictors must BOTH carry their token for "Paris": hinter emits good_hint only with TOKEN1; answerer
    * emits Paris only with TOKEN2 AND a good_hint. So a merge of a hinter-fixed and an answerer-fixed descendant is
    * what unlocks the correct answer. */
  private final class PipelineLm extends LanguageModel:
    override val id: String   = "pipeline"
    override val mode: LmMode = LmMode.Chat
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
    override def feedback(example: Example, prediction: DynamicPrediction, trace: Vector[TraceEntry],
        component: Option[String], componentTrace: Vector[TraceEntry])(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val gold = example.get("answer").map(DynamicValues.renderText).getOrElse("")
      val got  = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
      Right(ScoreWithFeedback(if got == gold then 1.0 else 0.0, s"expected '$gold', got '$got'"))

  private def predict(layout: String, instruction: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(layout).toOption.get.withInstructions(Some(instruction)))

  private val basePipeline = Pipeline(predict("question -> hint", "Stage one."), predict("question, hint -> answer", "Stage two."))

  private val valset: Vector[Example] = (1 to 2).toVector.map(i =>
    Example(values = DynamicValues.record("question" := s"Capital ($i)?", "answer" := "Paris"), inputKeys = Set("question")))

  // ── Pure crossover helpers ──────────────────────────────────────────────────────────────────────────────────

  test("crossover takes each component from whichever descendant improved it (complementary gains stacked)") {
    val ancestor = Map("a" -> "A0", "b" -> "B0")
    val id1      = Map("a" -> "A1", "b" -> "B0") // changed a, kept b
    val id2      = Map("a" -> "A0", "b" -> "B1") // kept a, changed b
    val (merged, desc) = MergeProposer.crossover(ancestor, 1, id1, 2, id2, _ => 0.0, new Random(0))
    assertEquals(merged, Map("a" -> "A1", "b" -> "B1")) // a from id1, b from id2
    assertEquals(desc, Vector(1, 2))                    // components sorted (a, b) -> sources (id1, id2)
  }

  test("crossover breaks a both-changed component toward the higher-scoring descendant") {
    val ancestor = Map("a" -> "A0")
    val (merged, _) = MergeProposer.crossover(ancestor, 1, Map("a" -> "A1"), 2, Map("a" -> "A2"),
      aggregateScore = Map(1 -> 0.3, 2 -> 0.9), new Random(0))
    assertEquals(merged("a"), "A2") // id2 scores higher
  }

  test("hasDesirablePredictors requires a component changed by exactly one descendant") {
    val anc = Map("a" -> "A0", "b" -> "B0")
    assert(MergeProposer.hasDesirablePredictors(anc, Map("a" -> "A1", "b" -> "B0"), Map("a" -> "A0", "b" -> "B1")))
    // Both descendants changed every component -> no component is "anchored" to the ancestor -> not desirable.
    assert(!MergeProposer.hasDesirablePredictors(Map("a" -> "A0"), Map("a" -> "A1"), Map("a" -> "A2")))
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
    val seedCand = Map("hinter" -> "Stage one.", "answerer" -> "Stage two.")
    val cand1    = Map("hinter" -> "Use TOKEN1 to produce a good_hint.", "answerer" -> "Stage two.")
    val cand2    = Map("hinter" -> "Stage one.", "answerer" -> "Use TOKEN2 with the good_hint to answer.")
    val state = GepaState(
      candidates   = Vector(seedCand, cand1, cand2),
      valSubscores = Vector(Vector(0.0, 0.0), Vector(1.0, 0.0), Vector(0.0, 1.0)),
      parents      = Vector(Vector.empty, Vector(0), Vector(0)),
      totalMetricCalls = 0
    )
    val merger = new MergeProposer[Pipeline](adapter, valset, maxMergeInvocations = 5, new Random(0))

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new PipelineLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val proposal = merger.propose(state)

      assert(proposal.isDefined, "a mergeable triplet (1, 2, ancestor 0) should be found")
      val p = proposal.get
      assertEquals(p.parents, Vector(1, 2))
      // The merge stacks both fixes: hinter from cand1 (TOKEN1), answerer from cand2 (TOKEN2).
      assert(p.candidate("hinter").contains("TOKEN1"), p.candidate("hinter"))
      assert(p.candidate("answerer").contains("TOKEN2"), p.candidate("answerer"))
      // With both fixed the merged program answers "Paris", beating either parent on the subsample.
      assert(p.accepted, "merged program should clear the subsample gate")
    }
  }

  test("propose returns None when there are fewer than three candidates (no ancestor to merge over)") {
    val adapter = new GepaAdapter[Pipeline](basePipeline, metric)
    val state = GepaState(
      candidates = Vector(Map("hinter" -> "a", "answerer" -> "b"), Map("hinter" -> "c", "answerer" -> "d")),
      valSubscores = Vector(Vector(1.0), Vector(1.0)),
      parents = Vector(Vector.empty, Vector(0)),
      totalMetricCalls = 0
    )
    val merger = new MergeProposer[Pipeline](adapter, valset, maxMergeInvocations = 5, new Random(0))
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new PipelineLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      assertEquals(merger.propose(state), None)
    }
  }
