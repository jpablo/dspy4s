package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
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
import dspy4s.programs.ProgramRunner
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

/** Two predictors that do not depend on each other: `first` gives the output `a`, and `second` gives the output `b`.
  * Top-level, so that the structure derivation sees the field labels.
  */
final case class TwoStage(first: DynamicPredict, second: DynamicPredict)

object TwoStage:
  given ProgramRunner[TwoStage] with
    def run(program: TwoStage, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      for
        a <- program.first(call)
        b <- program.second(call)
      yield b.raw.withValue("a", DynamicValues.recordGet(a.output, "a").getOrElse(DynamicValue.Null))

/** The round-robin pointer of a new candidate, and the depth of the merge search (PORT_GAPS G-44; gepa 0.1.4
  * `core/state.py`, `strategies/component_selector.py`, `proposer/reflective_mutation/reflective_mutation.py`, and
  * `proposer/merge.py`).
  */
class GepaPointerMergeSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val first  = OptimizableId(0)
  private val second = OptimizableId(1)

  private def predict(layout: String, instruction: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(layout).toOption.get.withInstructions(Some(instruction)))

  private val twoStage: TwoStage =
    TwoStage(first = predict("question -> a", "Component A."), second = predict("question -> b", "Component B."))

  private val dataset: Vector[Example] = (1 to 4).toVector.map(i =>
    Example(values = DynamicValues.record("question" := s"Question $i?"), inputKeys = Set("question"))
  )

  /** Stage `a` answers "ok" only when its instruction has FIXED_A. Stage `b` answers "ok" only with FIXED_B. */
  private final class StageLm extends LanguageModel:
    override val id: String                                                                    = "stage"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val prompt          = request.messages.flatMap(_.text).mkString("\n")
      val (field, marker) = if prompt.contains("## a ##") then ("a", "FIXED_A") else ("b", "FIXED_B")
      val value           = if prompt.contains(marker) then "ok" else "bad"
      Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## $field ## ]]\n$value\n\n[[ ## completed ## ]]"))))

  /** Records the component of each request, and gives that component its fix. */
  private final class RecordingReflectionLm extends LanguageModel:
    val components: ArrayBuffer[String]                                                        = ArrayBuffer.empty
    override val id: String                                                                    = "reflect"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val prompt    = request.messages.flatMap(_.text).mkString("\n")
      val component = if prompt.contains("Component A") then "A" else "B"
      val _         = components.synchronized(components += component)
      Right(LmResponse(outputs =
        Vector(LmOutput(text = ReflectionFixtures.reply(s"Component $component. FIXED_$component")))
      ))

  private def okScore(prediction: RawPrediction): Double =
    Vector("a", "b").count(name => prediction.get(name).map(DynamicValues.renderText).contains("ok")) * 0.5

  /** Half of the score for each stage that answers "ok". */
  private val partialCredit: FeedbackMetric = new FeedbackMetric:
    override def name: String = "partial_credit"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      Right(ScoreWithFeedback(okScore(prediction), "Each stage must answer ok."))

  private def run(
      metric      : FeedbackMetric,
      reflectionLm: LanguageModel,
      budget      : Int
  ): GepaResult[TwoStage] =
    val gepa = new Gepa[TwoStage](
      metric,
      reflectionLm,
      GepaConfig(
        budget = GepaBudget.MetricCalls(MetricCallCount.applyUnsafe(budget)),
        reflectionMinibatchSize = MinibatchSize(2),
        candidateSelector = CandidateSelector.CurrentBest,
        componentSelector = ComponentSelector.RoundRobin,
        useMerge = false,
        seed = 1L
      )
    )
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new StageLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      gepa.compile(twoStage, trainset = dataset, valset = dataset).fold(error => fail(error.message), identity)
    }

  // ── The pointer in the state ────────────────────────────────────────────────────────────────────────────────

  private val seedCandidate: Candidate = Map(first -> Some("Component A."), second -> Some("Component B."))

  private def candidate(a: String, b: String): Candidate = Map(first -> Some(a), second -> Some(b))

  test("a new candidate takes the largest pointer of its parents") {
    val seed = GepaState.seed(seedCandidate, Vector(0.0, 0.0), metricCalls = 2)
    assertEquals(seed.pointerOf(0), 0)

    // The pointer of the parent moves before its child is added, so the child continues with the next component.
    val child = seed.withPointer(0, 1).add(candidate("A1", "Component B."), Vector(1.0, 0.0), Vector(0), 2)
    assertEquals((child.pointerOf(0), child.pointerOf(1)), (1, 1))

    val sibling = child.withPointer(0, 0).add(candidate("Component A.", "B1"), Vector(0.0, 1.0), Vector(0), 2)
    assertEquals(sibling.pointerOf(2), 0)

    // A merge has two parents.
    val merged = sibling.add(candidate("A1", "B1"), Vector(1.0, 1.0), Vector(1, 2), 2)
    assertEquals(merged.pointerOf(3), 1)
  }

  test("a saved state keeps the pointers") {
    val state = GepaState.seed(seedCandidate, Vector(0.0, 0.0), metricCalls = 2)
      .withPointer(0, 1)
      .add(candidate("A1", "Component B."), Vector(1.0, 0.0), Vector(0), 2)
    val restored = GepaStatePersistence.fromJson(GepaStatePersistence.toJson(state))
    assertEquals(restored.map(s => Vector(s.pointerOf(0), s.pointerOf(1))), Right(Vector(1, 1)))
    assertEquals(restored, Right(state))
  }

  test("a state that an older version saved has the pointer 0 for each candidate") {
    val older =
      """{"candidates":[{"optimizable-0":"Component A.","optimizable-1":"Component B."}],"valSubscores":[[0.0,0.0]],"parents":[[]],"totalMetricCalls":2}"""
    val restored = GepaStatePersistence.fromJson(older)
    assertEquals(restored.map(_.pointerOf(0)), Right(0))
    assertEquals(restored.map(_.candidates.size), Right(1))
  }

  // ── The pointer in the engine ───────────────────────────────────────────────────────────────────────────────

  test("the child of an accepted mutation continues with the next component") {
    val reflection = new RecordingReflectionLm
    // The seed validation costs 4 calls, and each iteration costs 8 at most, so the budget holds two iterations.
    val result = run(partialCredit, reflection, budget = 20)

    // Iteration 1 changes component A of the seed. The child is the best candidate, so iteration 2 selects it. The
    // child takes the pointer of its parent, which has moved to B.
    assertEquals(reflection.components.toVector.take(2), Vector("A", "B"))
    assertEquals(result.bestScore, 1.0)
    assert(result.bestCandidate(first).exists(_.contains("FIXED_A")), result.bestCandidate.toString)
    assert(result.bestCandidate(second).exists(_.contains("FIXED_B")), result.bestCandidate.toString)
  }

  test("an iteration that a perfect minibatch skips does not move the pointer") {
    val programCalls = new AtomicInteger(0)
    // The program-level calls 5 and 6 are the minibatch of iteration 1. They get a perfect score, so that iteration
    // makes no proposal. The component-level calls (for the feedback text) do not count.
    val perfectFirstMinibatch: FeedbackMetric = new FeedbackMetric:
      override def name: String = "perfect_first_minibatch"
      override def feedback(
          example       : Example,
          prediction    : RawPrediction,
          trace         : Vector[TraceEntry],
          component     : Option[String],
          componentTrace: Vector[TraceEntry]
      )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
        val index = if component.isEmpty then programCalls.incrementAndGet() else 0
        val score = if index == 5 || index == 6 then 1.0 else okScore(prediction)
        Right(ScoreWithFeedback(score, "Each stage must answer ok."))

    val reflection = new RecordingReflectionLm
    // Seed validation 4, the skipped iteration 2, and one full iteration of 8 at most.
    val _ = run(perfectFirstMinibatch, reflection, budget = 14)

    assertEquals(programCalls.get() >= 8, true, s"the second iteration did not run: ${programCalls.get()} calls")
    // gepa selects the component after the perfect-score check, so the skipped iteration leaves the pointer at A.
    assertEquals(reflection.components.headOption, Some("A"))
  }

  // ── The depth of the merge search ───────────────────────────────────────────────────────────────────────────

  private def anyMetric: FeedbackMetric = partialCredit

  /** Twelve candidates that stay on the frontier, with one pair that can merge. The seed scores 0.5 on each of the 14
    * validation examples. X and Y each improve one example and change a different component. Ten more candidates are
    * the best on one example each and score 0 on the others, so the seed is better than each of them and no pair with
    * one of them passes the ancestor rule.
    */
  private lazy val sparseState: GepaState =
    val examples                                     = 14
    def row(best: Int, rest: Double): Vector[Double] = Vector.tabulate(examples)(i => if i == best then 1.0 else rest)
    val x                                            = candidate("A improved", "Component B.")
    val y                                            = candidate("Component A.", "B improved")
    val others                                       = (0 until 10).toVector.map(k => candidate(s"A other $k", "Component B."))
    GepaState(
      candidates = CandidatePool.applyUnsafe(Vector(seedCandidate, x, y) ++ others),
      valSubscores = Vector(Vector.fill(examples)(0.5), row(0, 0.5), row(1, 0.5)) ++
        others.indices.map(k => row(2 + k, 0.0)),
      parents = Vector(Vector.empty) ++ Vector.fill(12)(Vector(0)),
      totalMetricCalls = MetricCallCount(0)
    )

  private def merger(seed: Long, valsetSize: Int, valOverlapFloor: Option[Int] = None): MergeProposer[TwoStage] =
    val valset = Vector.tabulate(valsetSize)(i =>
      Example(values = DynamicValues.record("question" := s"Validation $i?"), inputKeys = Set("question"))
    )
    val cache = new GepaEvalCache(new GepaAdapter[TwoStage](twoStage, anyMetric))
    valOverlapFloor match
      case Some(floor) =>
        new MergeProposer[TwoStage](valset, MergeInvocationLimit(5), new Random(seed), cache, valOverlapFloor = floor)
      case None => new MergeProposer[TwoStage](valset, MergeInvocationLimit(5), new Random(seed), cache)

  test("the frontier of the sparse state has twelve candidates and no seed") {
    val dominators = sparseState.nonDominatedFrontier.values.flatten.toSet
    assertEquals(dominators, (1 to 12).toSet)
  }

  test("the merge search tries ten pairs in each of ten attempts") {
    val dominators = sparseState.nonDominatedFrontier.values.flatten.toSet.toVector.sorted
    // One pair of 66 can merge. Ten pair draws find it with a probability of 14%, and one hundred with 78%.
    val found = (0 until 40).count(seed =>
      merger(seed.toLong, valsetSize = 14).findTriplet(sparseState, dominators).isDefined
    )
    assert(found >= 20, s"the search found the pair for $found of 40 seeds")
    assertEquals(
      (0 until 40).flatMap(seed => merger(seed.toLong, valsetSize = 14).findTriplet(sparseState, dominators)).toSet,
      Set((1, 2, 0))
    )
  }

  // ── The overlap floor of a merge ────────────────────────────────────────────────────────────────────────────

  /** A seed and two children that each change one component. The two children are a pair that can merge. */
  private def pairState(examples: Int): GepaState =
    def row(best: Int): Vector[Double] = Vector.tabulate(examples)(i => if i == best then 1.0 else 0.0)
    GepaState(
      candidates = CandidatePool.applyUnsafe(Vector(
        seedCandidate,
        candidate("Component A. FIXED_A", "Component B."),
        candidate("Component A.", "Component B. FIXED_B")
      )),
      valSubscores = Vector(Vector.fill(examples)(0.0), row(0), row(1)),
      parents = Vector(Vector.empty, Vector(0), Vector(0)),
      totalMetricCalls = MetricCallCount(0)
    )

  private def proposal(valsetSize: Int, valOverlapFloor: Option[Int]): Option[MergeProposal] =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new StageLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      merger(seed = 0L, valsetSize, valOverlapFloor).propose(pairState(valsetSize))
    }

  test("two candidates that share fewer validation examples than the floor do not merge") {
    assertEquals(MergeProposer.DefaultValOverlapFloor, 5)
    // gepa's `val_overlap_floor` is 5: a validation set of 4 gives no merge.
    assertEquals(proposal(valsetSize = 4, valOverlapFloor = None), None)
    assert(proposal(valsetSize = 5, valOverlapFloor = None).isDefined, "five shared examples must permit the merge")
  }

  test("a lower floor permits a merge on a small validation set") {
    val merged = proposal(valsetSize = 2, valOverlapFloor = Some(2))
    assertEquals(merged.map(_.parents), Some(Vector(1, 2)))
    assert(merged.exists(_.accepted), merged.toString)
  }
