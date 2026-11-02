package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.ConfigurationError
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
import dspy4s.programs.ProgramRunner
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

/** Two leaves in sequence, so that a test can read the round-robin pointer. Top-level so `OptimizableStructure.derived`
  * sees its Mirror field labels.
  */
final case class TwoLeaves(first: DynamicPredict, second: DynamicPredict)

object TwoLeaves:
  given ProgramRunner[TwoLeaves] with
    def run(program: TwoLeaves, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      program.first(call).flatMap(_ => program.second(call)).map(_.raw)

/** The rules of upstream GEPA that the port did not follow (`dspy/teleprompt/gepa/`,
  * `dspy/teleprompt/bootstrap_trace.py`, and the `gepa` package).
  */
class GepaUpstreamRulesSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  import ReflectionFixtures.reply

  private val first = OptimizableId(0)

  private val improved = "Answer with the CITY name only."

  /** The task LM.
    *   - A question with the word BROKEN gets an error, so the run of the program fails.
    *   - A question with the word BADFORMAT gets a reply with no field marker, so the parse fails.
    *   - Each other question gets "Paris" when the prompt has the token CITY, and "WRONG" otherwise.
    */
  private final class TaskLm(extraFields: String = "") extends LanguageModel:
    val calls: AtomicInteger                                                                   = AtomicInteger(0)
    override val id: String                                                                    = "task"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _      = calls.incrementAndGet()
      val prompt = request.messages.flatMap(_.text).mkString("\n")
      if prompt.contains("BROKEN") then Left(RuntimeError("task_lm", "the task model failed"))
      else if prompt.contains("BADFORMAT") then Right(LmResponse(outputs = Vector(LmOutput(text = "no format"))))
      else
        val answer = if prompt.contains("CITY") then "Paris" else "WRONG"
        val text   = s"[[ ## answer ## ]]\n$answer\n\n$extraFields[[ ## completed ## ]]"
        Right(LmResponse(outputs = Vector(LmOutput(text = text))))

  /** Gives `text` as the new instruction of each request. Records the text of each request. */
  private final class ReflectionLm(text: String = improved) extends LanguageModel:
    val requests: ArrayBuffer[String]                                                          = ArrayBuffer.empty
    override val id: String                                                                    = "reflect"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = requests.synchronized(requests += request.messages.flatMap(_.text).mkString("\n"))
      Right(LmResponse(outputs = Vector(LmOutput(text = reply(text)))))

  /** The score of a prediction: 1.0 for the gold answer, and 0.0 otherwise. `onProgram` and `onComponent` can replace
    * the result of a call.
    */
  private final class Graded(
      onProgram: (Example, RawPrediction, Vector[TraceEntry]) => Option[Either[DspyError, ScoreWithFeedback]] =
        (_, _, _) => None,
      onComponent: () => Option[Either[DspyError, ScoreWithFeedback]] = () => None
  ) extends FeedbackMetric:
    val componentTraces: ArrayBuffer[Vector[TraceEntry]] = ArrayBuffer.empty
    override def name: String                            = "graded"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val gold    = example.get("answer").map(DynamicValues.renderText).getOrElse("")
      val got     = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
      val correct = Right(ScoreWithFeedback(if got == gold then 1.0 else 0.0, s"expected '$gold', got '$got'"))
      component match
        case None    => onProgram(example, prediction, trace).getOrElse(correct)
        case Some(_) =>
          val _ = componentTraces.synchronized(componentTraces += trace)
          onComponent().getOrElse(correct)

  private val layout = SignatureLayout.parse("question -> answer").toOption.get

  private val program: DynamicPredict = DynamicPredict(layout = layout.withInstructions(Some("Answer.")))

  private def twoLeaves: TwoLeaves =
    TwoLeaves(
      DynamicPredict(layout = layout.withInstructions(Some("Answer.")), name = Some("first")),
      DynamicPredict(layout = layout.withInstructions(Some("Check.")), name = Some("second"))
    )

  private def example(question: String): Example =
    Example(values = DynamicValues.record("question" := question, "answer" := "Paris"), inputKeys = Set("question"))

  private val dataset: Vector[Example] = (1 to 4).toVector.map(i => example(s"Capital of France ($i)?"))

  /** A budget that holds the seed validation and one iteration for the 4 examples of [[dataset]]. */
  private def config(calls: Int = 12): GepaConfig =
    GepaConfig(
      budget = GepaBudget.MetricCalls(MetricCallCount.applyUnsafe(calls)),
      reflectionMinibatchSize = MinibatchSize(2),
      seed = 1L
    )

  private def withLm[A](lm: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }

  private def withTempDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("gepa-upstream-rules")
    try body(dir)
    finally
      Files.list(dir).toArray(size => new Array[Path](size)).foreach(path => Files.deleteIfExists(path))
      val _ = Files.deleteIfExists(dir)

  // ── Scores that are not finite ────────────────────────────────────────────────────────────────────────────────────

  test("a metric that gives NaN or an infinite score counts as a metric failure") {
    Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach { value =>
      val metric = Graded(onProgram =
        (example, _, _) =>
          Option.when(example.get("question").map(DynamicValues.renderText).exists(_.contains("BAD")))(
            Right(ScoreWithFeedback(value, "no score"))
          )
      )
      val batch = Vector(example("One?"), example("BAD?"), example("Three?"))
      withLm(new TaskLm) {
        val adapter = new GepaAdapter(program, metric, failureScore = -1.0)
        val result  = adapter.evaluate(batch, Map(first -> Some(improved)), captureTraces = true)
        assertEquals(result.scores, Vector(1.0, -1.0, 1.0), s"value: $value")
        // Upstream has no trajectory for an example whose metric fails.
        assertEquals(result.trajectories.map(_.size), Some(2), s"value: $value")
      }
    }
  }

  test("an objective score that is not finite goes away, and the finite objective scores stay") {
    val metric = Graded(onProgram =
      (_, _, _) =>
        Some(Right(ScoreWithFeedback(1.0, "ok", objectiveScores = Map("kept" -> 0.5, "gone" -> Double.NaN))))
    )
    val batch = withLm(new TaskLm) {
      new GepaAdapter(program, metric).evaluate(dataset.take(1), Candidate.seed(program), captureTraces = false)
    }
    assertEquals(batch.objectiveScores, Vector(Map("kept" -> 0.5)))
  }

  test("a NaN score on a validation example of a child keeps the fronts and the checkpoint") {
    // The child passes acceptance on the minibatch. Its validation then gives NaN on the example with the word HELDOUT.
    val metric = Graded(onProgram =
      (example, prediction, _) =>
        Option.when(
          example.get("question").map(DynamicValues.renderText).exists(_.contains("HELDOUT")) &&
            prediction.get("answer").map(DynamicValues.renderText).contains("Paris")
        )(Right(ScoreWithFeedback(Double.NaN, "no score")))
    )
    val valset = dataset :+ example("HELDOUT?")
    withTempDir { dir =>
      val result = withLm(new TaskLm) {
        new GepaEngine(new GepaAdapter(program, metric), new ReflectionLm, config(40).copy(runDir = Some(dir)))
          .optimize(Candidate.seed(program), dataset, valset)
      }
      assert(result.isRight, result.toString)
      val found = result.toOption.get
      assertEquals(found.state.valSubscores.flatten.count(_.isNaN), 0)
      // The child scores 1.0 on 4 examples and the failure score 0.0 on the last one.
      assertEquals(found.bestCandidate, Map(first -> Some(improved)))
      assertEquals(found.bestScore, 0.8)
    }
  }

  test("a failure score that is not finite is an error") {
    Vector(Double.NaN, Double.NegativeInfinity).foreach { value =>
      val _ = intercept[IllegalArgumentException](new GepaAdapter(program, Graded(), failureScore = value))
    }
  }

  test("a failure score that is not finite is a Left from compile") {
    val result = withLm(new TaskLm) {
      new Gepa[DynamicPredict](Graded(), new ReflectionLm, config().copy(failureScore = Double.NaN))
        .compile(program, dataset, dataset)
    }
    assert(result.left.exists(_.isInstanceOf[ConfigurationError]), result.toString)
  }

  // ── The trace at the program level ────────────────────────────────────────────────────────────────────────────────

  test("a metric that reads the trace scores a parent and an equal child the same") {
    // Upstream gives `trace=None` to each program-level call (`bootstrap_trace_data`, `Evaluate`).
    val metric = Graded(onProgram = (_, _, trace) => Some(Right(ScoreWithFeedback(1.0 - 0.1 * trace.size, "feedback"))))
    val result = withLm(new TaskLm) {
      new GepaEngine(new GepaAdapter(program, metric), new ReflectionLm("Another instruction."), config())
        .optimize(Candidate.seed(program), dataset, dataset)
    }
    assertEquals(result.map(_.numCandidates), Right(1), "an equal child must not pass strict acceptance")
  }

  test("the component-level call still gets the trace of the run") {
    val metric = Graded()
    val result = withLm(new TaskLm) {
      new GepaEngine(new GepaAdapter(program, metric), new ReflectionLm, config())
        .optimize(Candidate.seed(program), dataset, dataset)
    }
    assert(result.isRight, result.toString)
    assert(metric.componentTraces.nonEmpty)
    assert(metric.componentTraces.forall(_.size == 1), metric.componentTraces.toString)
  }

  // ── The metric at the component level ─────────────────────────────────────────────────────────────────────────────

  private def componentFailure(onComponent: () => Option[Either[DspyError, ScoreWithFeedback]]): Unit =
    val reflection = new ReflectionLm
    val metric     = Graded(onComponent = onComponent)
    val result     = withLm(new TaskLm) {
      val leaves = twoLeaves
      new GepaEngine(new GepaAdapter(leaves, metric), reflection, config())
        .optimize(Candidate.seed(leaves), dataset, dataset)
    }
    assert(result.isRight, result.toString)
    val found = result.toOption.get
    assertEquals(found.numCandidates, 1)
    assert(found.iterations >= 1, found.iterations.toString)
    assertEquals(reflection.requests.toVector, Vector.empty, "the task has no reflection")
    // Upstream moves the pointer before it builds the reflective dataset.
    assertEquals(found.state.nextComponent(0), 1, "the task moves the pointer")

  test("an exception of the component-level metric skips the task, and the run continues") {
    componentFailure(() => throw new IOException("the judge timed out"))
  }

  test("a Left of the component-level metric skips the task, and the run continues") {
    componentFailure(() => Some(Left(RuntimeError("judge", "no judge"))))
  }

  // ── Which runs have a trajectory ──────────────────────────────────────────────────────────────────────────────────

  test("only a run that passed and a parse failure have a trajectory") {
    // `bootstrap_trace_data` drops an example whose program raised (`capture_failed_parses=True`). The program has two
    // output fields, because a reply for one output field gets the single-output rule of the port, and it then parses.
    val twoOutputs = DynamicPredict(layout = SignatureLayout.parse("question -> answer, source").toOption.get)
    val batch      = Vector(example("One?"), example("BROKEN?"), example("BADFORMAT?"))
    val result     = withLm(new TaskLm("[[ ## source ## ]]\natlas\n\n")) {
      new GepaAdapter(twoOutputs, Graded(), failureScore = -1.0)
        .evaluate(batch, Map(first -> Some(improved)), captureTraces = true)
    }
    assertEquals(result.scores, Vector(1.0, -1.0, -1.0))
    val kept = result.trajectories.getOrElse(fail("the batch has no trajectories"))
    assertEquals(kept.map(_.example), Vector(batch(0), batch(2)))
    assertEquals(kept.map(_.failed), Vector(false, true))
  }

  test("a metric that gives a Left has no trajectory") {
    val metric = Graded(onProgram =
      (example, _, _) =>
        Option.when(example.get("question").map(DynamicValues.renderText).exists(_.contains("UNSCORED")))(
          Left(RuntimeError("metric", "no gold value"))
        )
    )
    val batch  = Vector(example("One?"), example("UNSCORED?"))
    val result = withLm(new TaskLm) {
      new GepaAdapter(program, metric, failureScore = -1.0)
        .evaluate(batch, Map(first -> Some(improved)), captureTraces = true)
    }
    assertEquals(result.scores, Vector(1.0, -1.0))
    assertEquals(result.trajectories.map(_.map(_.example)), Some(Vector(batch(0))))
  }

  test("a minibatch with no trajectory skips the task, with no pointer move") {
    // gepa `reflective_mutation.py`: the check of `eval_curr.trajectories` comes before the selector.
    val broken     = (1 to 4).toVector.map(i => example(s"BROKEN ($i)?"))
    val reflection = new ReflectionLm
    val result     = withLm(new TaskLm) {
      val leaves = twoLeaves
      new GepaEngine(new GepaAdapter(leaves, Graded()), reflection, config())
        .optimize(Candidate.seed(leaves), broken, broken)
    }
    assert(result.isRight, result.toString)
    val found = result.toOption.get
    assert(found.iterations >= 1, found.iterations.toString)
    assertEquals(found.state.nextComponent(0), 0)
    assertEquals(reflection.requests.toVector, Vector.empty)
  }

  test("an example with no input keys fails with no model call") {
    // Upstream `Example.inputs` raises.
    val lm     = new TaskLm
    val bare   = Example(values = DynamicValues.record("question" := "One?", "answer" := "Paris"))
    val result = withLm(lm) {
      new GepaAdapter(program, Graded(), failureScore = -1.0).evaluate(Vector(bare), Candidate.seed(program), true)
    }
    assertEquals(result.scores, Vector(-1.0))
    assertEquals(result.trajectories, Some(Vector.empty))
    assertEquals(lm.calls.get(), 0)
  }

  // ── The proposer inputs ───────────────────────────────────────────────────────────────────────────────────────────

  test("a leaf with no instructions gives its default text to the reflection model") {
    // dspy `gepa.py` seeds from `pred.signature.instructions`, which has the default text.
    val bare       = DynamicPredict(layout = layout)
    val reflection = new ReflectionLm
    val result     = withLm(new TaskLm) {
      new GepaEngine(new GepaAdapter(bare, Graded()), reflection, config())
        .optimize(Candidate.seed(bare), dataset, dataset)
    }
    assert(result.isRight, result.toString)
    assert(reflection.requests.nonEmpty)
    assert(
      reflection.requests.head.contains("Given the fields `question`, produce the fields `answer`."),
      reflection.requests.head
    )
  }

  test("the reflection prompt names the type of the examples") {
    // dspy `instruction_proposal.py` declares `list[dict[str, Any]]`.
    val reflection = new ReflectionLm
    val result     = withLm(new TaskLm) {
      new GepaEngine(new GepaAdapter(program, Graded()), reflection, config())
        .optimize(Candidate.seed(program), dataset, dataset)
    }
    assert(result.isRight, result.toString)
    assert(reflection.requests.nonEmpty)
    assert(
      reflection.requests.head.contains("`examples_with_feedback` (list[dict[str, Any]])"),
      reflection.requests.head
    )
  }

  test("the default feedback has the Python text of the score") {
    // dspy `gepa.py`: `f"This trajectory got a score of {o}."`.
    assertEquals(FeedbackMetric.defaultFeedback(0.0001), "This trajectory got a score of 0.0001.")
    assertEquals(FeedbackMetric.defaultFeedback(1.0e7), "This trajectory got a score of 10000000.0.")
    assertEquals(FeedbackMetric.defaultFeedback(0.5), "This trajectory got a score of 0.5.")
  }

  // ── The sums ──────────────────────────────────────────────────────────────────────────────────────────────────────

  test("a strict comparison does not depend on the order of the scores") {
    // Python 3.12 `sum` is compensated: 0.6 for the two orders. A plain fold gives 0.6 and 0.6000000000000001.
    val proposal = Proposal(Map(first -> Some("child")), 0, Vector(0.3, 0.2, 0.1), Vector(0.1, 0.2, 0.3))
    assertEquals(ProposalAcceptance.StrictImprovement.accepts(proposal), false)
    assertEquals(ProposalAcceptance.ImprovementOrEqual.accepts(proposal), true)
    assertEquals(proposal.gain, 0.0)
  }

  test("the aggregate score has the value of the Python sum") {
    // Python 3.12: `sum([0.1] * 10) / 10` is 0.1. A plain fold gives 0.09999999999999999.
    val state = GepaStateFixtures.state(
      candidates = CandidatePool.applyUnsafe(Vector(Map(first -> Some("x")))),
      valSubscores = Vector(Vector.fill(10)(0.1)),
      parents = Vector(Vector.empty),
      totalMetricCalls = MetricCallCount(10)
    )
    assertEquals(state.aggregateScore(0), 0.1)
  }

  // ── The draws ─────────────────────────────────────────────────────────────────────────────────────────────────────

  test("the parent draw reads each front in sorted order, not in the order of the hash") {
    // Each candidate is best on one example of its own, and all candidates tie on the last example. The tied front has
    // 6 members, so the set is a hash set.
    val count  = 6
    val scores = Vector.tabulate(count) { candidate =>
      Vector.tabulate(count)(index => if index == candidate then 1.0 else 0.0) :+ 1.0
    }
    val state = GepaStateFixtures.state(
      candidates = CandidatePool.applyUnsafe(Vector.tabulate(count)(index => Map(first -> Some(s"text $index")))),
      valSubscores = scores,
      parents = Vector.fill(count)(Vector.empty),
      totalMetricCalls = MetricCallCount(0)
    )
    val weighted = state.nonDominatedFrontier.flatMap(_.toVector.sorted)
    assertEquals(weighted.size, count * 2)
    (0L until 40L).foreach { seed =>
      val draw = weighted(new Random(seed).nextInt(weighted.size))
      assertEquals(CandidateSelector.Pareto.select(state, new Random(seed)), draw, s"seed: $seed")
    }
  }

  test("the pad of an epoch repeats the last ids of the shuffled list") {
    // gepa `batch_sampler.py`: the least-used id that comes last in the shuffled list. A trainset of 10 and a minibatch
    // of 3 give 2 pad slots: the last window is the id at position 9, that id again, and the id at position 8.
    val windows = (0L until 30L).map { seed =>
      new MinibatchSampler.EpochShuffled(MinibatchSize(3), seed).sample(10, 3)
    }
    windows.foreach { window =>
      assertEquals(window(0), window(1), window.toString)
      assert(window(1) != window(2), window.toString)
    }
    assert(windows.map(_(1)).toSet.size > 2, "the extra use moves with each shuffle")
  }

  // ── The checkpoint ────────────────────────────────────────────────────────────────────────────────────────────────

  private def savedState(text: String): GepaState =
    GepaStateFixtures.state(
      candidates = CandidatePool.applyUnsafe(Vector(Map(first -> Some(text)))),
      valSubscores = Vector(Vector(1.0)),
      parents = Vector(Vector.empty),
      totalMetricCalls = MetricCallCount(1)
    )

  test("a failed checkpoint write keeps the last good checkpoint") {
    // gepa `core/state.py` writes a temporary file and then calls `os.replace`.
    withTempDir { dir =>
      GepaStatePersistence.save(dir, savedState("first"))
      // A directory at the path of the temporary file makes the write fail.
      val blocker = dir.resolve(GepaStatePersistence.fileName + ".tmp")
      val _       = Files.createDirectory(blocker)
      val _       = intercept[IOException](GepaStatePersistence.save(dir, savedState("second")))
      val loaded  = GepaStatePersistence.load(dir)
      assertEquals(loaded.map(_.map(_.candidates)), Right(Some(savedState("first").candidates)))
      val _ = Files.delete(blocker)
    }
  }

  test("a checkpoint write leaves no temporary file") {
    withTempDir { dir =>
      GepaStatePersistence.save(dir, savedState("first"))
      GepaStatePersistence.save(dir, savedState("second"))
      val names = Files.list(dir).toArray(size => new Array[Path](size)).map(_.getFileName.toString).toVector
      assertEquals(names, Vector(GepaStatePersistence.fileName))
      assertEquals(
        GepaStatePersistence.load(dir).map(_.map(_.candidates)),
        Right(Some(savedState("second").candidates))
      )
    }
  }

  // ── Exceptions at the boundary ────────────────────────────────────────────────────────────────────────────────────

  test("an exception of the program structure gives a Left from optimize") {
    val inner  = DynamicPredict(layout = layout)
    val result = withLm(new TaskLm) {
      new GepaEngine(new GepaAdapter(Fragile(inner), Graded()), new ReflectionLm, config())
        .optimize(Map(first -> Some("BOOM")), dataset, dataset)
    }
    assert(result.left.exists(_.message.contains("BOOM")), result.toString)
  }
