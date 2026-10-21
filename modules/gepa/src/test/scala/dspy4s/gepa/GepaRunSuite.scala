package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.ConfigurationError
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
import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** The stop reason, the errors, the resume, and the facade of a GEPA run (PORT_GAPS G-44 and G-45; upstream
  * `dspy/teleprompt/gepa/gepa.py` `compile`, and gepa 0.1.4 `core/engine.py` and `core/state.py`).
  */
class GepaRunSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  import ReflectionFixtures.reply

  private val first = OptimizableId(0)

  private val QuestionNumber = raw"Capital of France \((\d+)\)\?".r
  private val ForList        = raw"FOR ([\d,]+)".r

  private def numbersIn(text: String): Set[Int] = QuestionNumber.findAllMatchIn(text).map(_.group(1).toInt).toSet

  private def forList(text: String): Set[Int] =
    ForList.findAllMatchIn(text).flatMap(_.group(1).split(',').filter(_.nonEmpty).map(_.toInt)).toSet

  /** The task LM. With the token CITY, the answer is "Paris" for each question, or, when the instruction has a list
    * `FOR 1,3`, only for the questions with a number in the list. Otherwise the answer is "WRONG".
    */
  private final class TaskLm extends LanguageModel:
    val calls: AtomicInteger                                                                   = AtomicInteger(0)
    override val id: String                                                                    = "task"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _      = calls.incrementAndGet()
      val prompt = request.messages.flatMap(_.text).mkString("\n")
      val listed = forList(prompt)
      val right  = prompt.contains("CITY") && (listed.isEmpty || numbersIn(prompt).exists(listed.contains))
      val answer = if right then "Paris" else "WRONG"
      Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## answer ## ]]\n$answer\n\n[[ ## completed ## ]]"))))

  /** The reflection LM. Its reply depends on the request only, so two runs get the same reply for the same request.
    *   - With `fixed`, the reply is that instruction.
    *   - Otherwise the reply is `CITY FOR <list>`, with the numbers of the questions of the request and the numbers of
    *     the list of the current instruction.
    */
  private final class ReflectionLm(fixed: Option[String] = None) extends LanguageModel:
    val requests: ArrayBuffer[String]                                                          = ArrayBuffer.empty
    override val id: String                                                                    = "reflect"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val text = request.messages.flatMap(_.text).mkString("\n")
      val _    = requests.synchronized(requests += text)
      val next = fixed.getOrElse(s"CITY FOR ${(numbersIn(text) ++ forList(text)).toVector.sorted.mkString(",")}")
      Right(LmResponse(outputs = Vector(LmOutput(text = reply(next)))))

  private val metric: FeedbackMetric = new FeedbackMetric:
    override def name: String = "exact_answer"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val got = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
      Right(ScoreWithFeedback(if got == "Paris" then 1.0 else 0.0, s"expected 'Paris', got '$got'"))

  private val program: DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some("Answer.")))

  private val seedCandidate: Candidate = Candidate.seed(program)

  private def dataset(size: Int): Vector[Example] = (1 to size).toVector.map(i =>
    Example(
      values = DynamicValues.record("question" := s"Capital of France ($i)?", "answer" := "Paris"),
      inputKeys = Set("question")
    )
  )

  private def calls(budget: Int): GepaBudget = GepaBudget.MetricCalls(MetricCallCount.applyUnsafe(budget))

  private def settings(budget: GepaBudget, minibatch: Int = 2): GepaConfig =
    GepaConfig(budget = budget, reflectionMinibatchSize = MinibatchSize.applyUnsafe(minibatch), seed = 7L)

  private def withTaskLm[A](lm: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }

  private def optimize(
      config      : GepaConfig,
      reflectionLm: LanguageModel   = new ReflectionLm,
      taskLm      : LanguageModel   = new TaskLm,
      trainset    : Vector[Example] = dataset(4),
      valset      : Vector[Example] = dataset(4)
  ): Either[DspyError, GepaResult[DynamicPredict]] =
    withTaskLm(taskLm) {
      new GepaEngine(new GepaAdapter(program, metric), reflectionLm, config).optimize(seedCandidate, trainset, valset)
    }

  private def success(result: Either[DspyError, GepaResult[DynamicPredict]]): GepaResult[DynamicPredict] =
    result.fold(error => fail(error.message), identity)

  private def withTempDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("gepa-run")
    try body(dir)
    finally
      val _ = Files.deleteIfExists(dir.resolve(GepaStatePersistence.fileName))
      val _ = Files.deleteIfExists(dir)

  private def isConfigurationError(result: Either[DspyError, ?]): Boolean =
    result match
      case Left(_: ConfigurationError) => true
      case _                           => false

  // ── The stop reason ─────────────────────────────────────────────────────────────────────────────────────────

  test("a budget that holds no iteration after the seed evaluation gives the stop reason and zero iterations") {
    // Upstream example (tests/teleprompt/test_gepa.py): a budget of 5, two train examples, two validation examples,
    // and a minibatch of 3. One iteration needs 2 × 3 + 2 = 8 calls at most, and 3 calls remain.
    val reflection = new ReflectionLm
    val result     = success(
      optimize(settings(calls(5), minibatch = 3), reflection, trainset = dataset(2), valset = dataset(2))
    )
    assertEquals(result.stopReason, GepaStopReason.IterationDoesNotFit(required = 8, remaining = 3))
    assertEquals(result.iterations, 0)
    assertEquals(result.totalMetricCalls.toInt, 2)
    assertEquals(result.maxMetricCalls.toInt, 5)
    assertEquals(reflection.requests.size, 0)
  }

  test("a run that uses each metric call of its budget stops for that reason") {
    val seedOnly = success(optimize(settings(calls(4))))
    assertEquals(seedOnly.stopReason, GepaStopReason.BudgetExhausted)
    assertEquals(seedOnly.iterations, 0)

    // 4 for the seed, and 2 + 2 + 4 for one accepted iteration.
    val oneIteration = success(optimize(settings(calls(12))))
    assertEquals(oneIteration.stopReason, GepaStopReason.BudgetExhausted)
    assertEquals(oneIteration.iterations, 1)
    assertEquals(oneIteration.totalMetricCalls.toInt, 12)
  }

  test("a run that reaches the perfect score stops for that reason when the option is on") {
    val config = settings(calls(40)).copy(stopOnPerfectScore = true)
    val result = success(optimize(config, new ReflectionLm(fixed = Some("Answer with the CITY name only."))))
    assertEquals(result.stopReason, GepaStopReason.PerfectScore)
    assertEquals(result.iterations, 1)
    assertEquals(result.bestScore, 1.0)
  }

  test("the state counts the proposal tasks whose proposer failed two times") {
    // Each iteration gets an empty instruction two times and costs its minibatch only (2 calls). One iteration needs
    // 8 calls at most. The budget of 16 holds three iterations: 12, 10, and 8 calls remain at their start.
    val reflection = new ReflectionLm(fixed = Some(" "))
    val result     = success(optimize(settings(calls(16)), reflection))
    assertEquals(result.iterations, 3)
    assertEquals(result.state.failedProposals, 3)
    assertEquals(reflection.requests.size, 6)
    assertEquals(result.numCandidates.toInt, 1)
    assertEquals(result.stopReason, GepaStopReason.IterationDoesNotFit(required = 8, remaining = 6))
  }

  test("the state records the metric calls before the validation of each candidate") {
    // 4 for the seed, then 2 for the parent and 2 for the child before the validation of the child.
    val result = success(optimize(settings(calls(12))))
    assertEquals(result.numCandidates.toInt, 2)
    assertEquals(result.state.discoveryCalls, Vector(0, 8))
  }

  // ── Errors ──────────────────────────────────────────────────────────────────────────────────────────────────

  test("an empty trainset is a configuration error before any LM call") {
    val taskLm     = new TaskLm
    val reflection = new ReflectionLm
    assert(isConfigurationError(optimize(settings(calls(20)), reflection, taskLm, trainset = Vector.empty)))
    val facade = withTaskLm(taskLm) {
      new Gepa[DynamicPredict](metric, reflection, settings(calls(20))).compile(program, Vector.empty)
    }
    assert(isConfigurationError(facade), facade.toString)
    assertEquals(taskLm.calls.get(), 0)
    assertEquals(reflection.requests.size, 0)
  }

  test("a checkpoint for other components is a Left before any LM call, and the file stays unchanged") {
    withTempDir { dir =>
      GepaStatePersistence.save(dir, GepaState.seed(Map(OptimizableId(5) -> Some("Other.")), Vector.fill(4)(0.0), 4))
      val file   = dir.resolve(GepaStatePersistence.fileName)
      val before = Files.readAllBytes(file).toVector
      val taskLm = new TaskLm
      val result = optimize(settings(calls(20)).copy(runDir = Some(dir)), taskLm = taskLm)
      assert(result.isLeft, result.toString)
      assertEquals(taskLm.calls.get(), 0)
      assertEquals(Files.readAllBytes(file).toVector, before)
    }
  }

  test("a checkpoint with a validation row of another size is a Left before any LM call") {
    withTempDir { dir =>
      GepaStatePersistence.save(dir, GepaState.seed(seedCandidate, Vector.fill(3)(0.0), 3))
      val taskLm = new TaskLm
      val result = optimize(settings(calls(20)).copy(runDir = Some(dir)), taskLm = taskLm)
      assert(result.isLeft, result.toString)
      assertEquals(taskLm.calls.get(), 0)
    }
  }

  // ── Resume ──────────────────────────────────────────────────────────────────────────────────────────────────

  test("a saved state keeps the iteration index, the merge schedule, the discovery counts, and the failures") {
    val state = GepaState
      .seed(seedCandidate, Vector(0.0, 1.0), metricCalls = 2)
      .add(Map(first -> Some("CITY")), Vector(1.0, 1.0), parents = Vector(0), metricCalls = 6)
      .copy(
        iteration = 3,
        merge = MergeSchedule(
          mergesDue = 1,
          totalMergesTested = 2,
          lastIterFoundNewProgram = true,
          performedTriplets = Set((1, 2, 0)),
          performedDescriptions = Set((1, 2, Vector(0, 1)))
        ),
        failedProposals = 4
      )
    assertEquals(state.discoveryCalls, Vector(0, 2))
    assertEquals(GepaStatePersistence.fromJson(GepaStatePersistence.toJson(state)), Right(state))
  }

  test("a state that an older version saved starts at iteration 0 with the initial merge schedule") {
    val older    = """{"candidates":[{"optimizable-0":"c0"}],"valSubscores":[[0.5]],"parents":[[]],"totalMetricCalls":1}"""
    val restored = GepaStatePersistence.fromJson(older).fold(error => fail(error), identity)
    assertEquals(restored.iteration, 0)
    assertEquals(restored.merge, MergeSchedule.initial)
    assertEquals(restored.discoveryCalls, Vector.empty)
    assertEquals(restored.failedProposals, 0)
  }

  test("a resumed run makes the same draws and gives the same state as a run with no stop") {
    val oneTask  = settings(calls(1))
    val twoTasks = oneTask.copy(
      batchSampler = BatchSamplerKind.RandomDraw,
      proposalSampling = ProposalSampling.Independent(ProposalCount(2))
    )
    Vector("one task, epoch sampler" -> oneTask, "two tasks, random sampler" -> twoTasks).foreach {
      (label, base) =>
        def run(budget: Int, runDir: Option[Path], reflection: ReflectionLm, taskLm: TaskLm) =
          success(
            optimize(
              base.copy(budget = calls(budget), runDir = runDir),
              reflection,
              taskLm,
              trainset = dataset(6),
              valset = dataset(6)
            )
          )

        val fullReflection = new ReflectionLm
        val fullTask       = new TaskLm
        val full           = run(budget = 120, runDir = None, fullReflection, fullTask)

        withTempDir { dir =>
          val (reflectionA, taskA) = (new ReflectionLm, new TaskLm)
          val partA                = run(budget = 50, runDir = Some(dir), reflectionA, taskA)
          val (reflectionB, taskB) = (new ReflectionLm, new TaskLm)
          val partB                = run(budget = 120, runDir = Some(dir), reflectionB, taskB)

          // The stop is in the middle of the run.
          assert(partA.iterations > 0 && partA.iterations < full.iterations, s"$label: ${partA.iterations}")
          assertEquals(partB.state, full.state, label)
          assertEquals(partB.iterations, full.iterations, label)
          assertEquals(reflectionA.requests.toVector ++ reflectionB.requests.toVector, fullReflection.requests.toVector)
          // The resumed run makes no second seed evaluation.
          assertEquals(taskA.calls.get() + taskB.calls.get(), fullTask.calls.get(), label)
        }
    }
  }

  // ── The facade ──────────────────────────────────────────────────────────────────────────────────────────────

  private def compile(
      config: GepaConfig,
      valset: Option[Vector[Example]],
      taskLm: TaskLm = new TaskLm
  ): Either[DspyError, GepaResult[DynamicPredict]] =
    withTaskLm(taskLm) {
      val gepa = new Gepa[DynamicPredict](metric, new ReflectionLm(fixed = Some("Answer with the CITY name.")), config)
      valset.fold(gepa.compile(program, dataset(3)))(validation => gepa.compile(program, dataset(3), validation))
    }

  test("with no validation set, the trainset gives the Pareto scores") {
    val result = success(compile(settings(calls(20)), valset = None))
    assertEquals(result.state.valSubscores.head.size, 3)
    val empty = success(compile(settings(calls(20)), valset = Some(Vector.empty)))
    assertEquals(empty.state.valSubscores.head.size, 3)
  }

  test("the facade resolves the budget with the validation set that the caller gave") {
    val twice = settings(GepaBudget.FullEvals(FullEvalCount(2)))
    assertEquals(success(compile(twice, valset = Some(dataset(2)))).maxMetricCalls.toInt, 10)
    assertEquals(success(compile(twice, valset = None)).maxMetricCalls.toInt, 6)
    assertEquals(success(compile(twice, valset = Some(Vector.empty))).maxMetricCalls.toInt, 6)

    val light = settings(GepaBudget.Auto(AutoBudget.Light)).copy(stopOnPerfectScore = true)
    assertEquals(success(compile(light, valset = None)).maxMetricCalls.toInt, GepaBudget.auto(1, 6, 3))
    assertEquals(success(compile(light, valset = Some(dataset(4)))).maxMetricCalls.toInt, GepaBudget.auto(1, 6, 4))
  }

  test("the facade saves the state in the run directory of the configuration") {
    withTempDir { dir =>
      val result = success(compile(settings(calls(20)).copy(runDir = Some(dir)), valset = None))
      val file   = dir.resolve(GepaStatePersistence.fileName)
      assert(Files.exists(file))
      val saved = GepaStatePersistence.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
      assertEquals(saved, Right(result.state))
    }
  }
