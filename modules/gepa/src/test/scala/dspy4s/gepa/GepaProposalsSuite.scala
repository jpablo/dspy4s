package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ThreadCount
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
import dspy4s.programs.optimization.OptimizableLeaf
import dspy4s.programs.optimization.OptimizableMetadata
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import scala.util.Try

/** A program with one predictor. Its structure throws an exception for an instruction that has the word BOOM, so the
  * evaluation of a candidate with that instruction throws.
  */
final case class Fragile(inner: DynamicPredict)

object Fragile:
  given ProgramRunner[Fragile] with
    def run(program: Fragile, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      program.inner(call).map(_.raw)

  given OptimizableLeaf[Fragile] with
    private val leaf = summon[OptimizableLeaf[DynamicPredict]]

    def get(program: Fragile): OptimizableParameters = leaf.get(program.inner)

    def metadata(program: Fragile): OptimizableMetadata = leaf.metadata(program.inner)

    def set(program: Fragile, updated: OptimizableParameters): Fragile =
      if updated.instructions.exists(_.contains("BOOM")) then throw IllegalStateException("BOOM")
      else Fragile(leaf.set(program.inner, updated))

/** More than one proposal in one reflective iteration, and the batch evaluation of candidates (PORT_GAPS G-45, item O5;
  * upstream aa9d2d053 and 822f39319; gepa 0.1.4 `strategies/proposal_sampling.py`, `strategies/proposal_selection.py`,
  * `strategies/acceptance.py`, `strategies/batch_sampler.py`, `proposer/reflective_mutation/reflective_mutation.py`,
  * and `core/engine.py` `_run_reflective_batch`; dspy `gepa_utils.py` `batch_evaluate`).
  */
class GepaProposalsSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  import ReflectionFixtures.reply

  private val first  = OptimizableId(0)
  private val second = OptimizableId(1)

  /** The task LM. The answer comes from the first rule that applies to the prompt:
    *   - the word EASY: "Paris"
    *   - the token CITY: "Paris"
    *   - the token NEAR: "Close"
    *   - otherwise: "WRONG"
    *
    * With `barrier`, each of the first two calls whose prompt has the word CHILD waits for the other one. A call that
    * waits for more than 5 seconds gives an error, so the two calls pass only when they run at the same time.
    */
  private final class TaskLm(barrier: Option[CyclicBarrier] = None) extends LanguageModel:
    val calls: AtomicInteger                                                                   = AtomicInteger(0)
    private val childCalls                                                                     = AtomicInteger(0)
    override val id: String                                                                    = "task"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _      = calls.incrementAndGet()
      val prompt = request.messages.flatMap(_.text).mkString("\n")
      val met    = barrier.filter(_ => prompt.contains("CHILD") && childCalls.incrementAndGet() <= 2) match
        case Some(gate) => Try(gate.await(5, TimeUnit.SECONDS)).isSuccess
        case None       => true
      val answer =
        if prompt.contains("EASY") || prompt.contains("CITY") then "Paris"
        else if prompt.contains("NEAR") then "Close"
        else "WRONG"
      if met then
        Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## answer ## ]]\n$answer\n\n[[ ## completed ## ]]"))))
      else Left(RuntimeError("task_lm", "the other call did not run at the same time"))

  /** Gives each reply of `instructions` in order, and repeats the last one. Records the text of each request. */
  private final class ReflectionLm(instructions: String*) extends LanguageModel:
    val requests: ArrayBuffer[String]                                                          = ArrayBuffer.empty
    override val id: String                                                                    = "reflect"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val index = requests.synchronized {
        requests += request.messages.flatMap(_.text).mkString("\n")
        requests.size - 1
      }
      val instruction = instructions(math.min(index, instructions.size - 1))
      Right(LmResponse(outputs = Vector(LmOutput(text = reply(instruction)))))

  /** 1.0 for "Paris", 0.5 for "Close", and 0.0 for each other answer. Records the number of threads of the context of
    * each program-level call, by question.
    */
  private final class GradedMetric extends FeedbackMetric:
    val threads: TrieMap[String, Option[Int]] = TrieMap.empty
    override def name: String                 = "graded"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using ctx: RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      val got      = prediction.get("answer").map(DynamicValues.renderText).getOrElse("")
      val question = example.get("question").map(DynamicValues.renderText).getOrElse("")
      if component.isEmpty then
        val _ = threads.put(question, ctx.numThreads.map(count => count: Int))
      val score = if got == "Paris" then 1.0 else if got == "Close" then 0.5 else 0.0
      Right(ScoreWithFeedback(score, s"expected 'Paris', got '$got'"))

  private val program: DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some("Answer.")))

  private val seedCandidate: Candidate = Candidate.seed(program)

  private def example(question: String): Example =
    Example(values = DynamicValues.record("question" := question, "answer" := "Paris"), inputKeys = Set("question"))

  private def dataset(size: Int): Vector[Example] = (1 to size).toVector.map(i => example(s"Capital of France ($i)?"))

  private def instruction(text: String): Candidate = Map(first -> Some(text))

  /** The numbers of the questions of `dataset` that `request` holds. */
  private def questionNumbers(request: String): Set[Int] =
    raw"Capital of France \((\d+)\)\?".r.findAllMatchIn(request).map(_.group(1).toInt).toSet

  private def config(
      budget    : Int,
      sampling  : ProposalSampling,
      selection : ProposalSelection  = ProposalSelection.AllImprovements,
      acceptance: ProposalAcceptance = ProposalAcceptance.StrictImprovement,
      minibatch : Int                = 2
  ): GepaConfig =
    GepaConfig(
      budget = GepaBudget.MetricCalls(MetricCallCount.applyUnsafe(budget)),
      reflectionMinibatchSize = MinibatchSize.applyUnsafe(minibatch),
      proposalSampling = sampling,
      proposalSelection = selection,
      acceptance = acceptance,
      useMerge = false,
      seed = 1L
    )

  private def run(
      settings  : GepaConfig,
      reflection: LanguageModel,
      trainset  : Vector[Example] = dataset(4),
      valset    : Vector[Example] = dataset(4),
      taskLm    : LanguageModel   = new TaskLm
  ): GepaResult[DynamicPredict] =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(taskLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      new GepaEngine(new GepaAdapter(program, new GradedMetric), reflection, settings)
        .optimize(seedCandidate, trainset, valset)
        .fold(error => fail(error.message), identity)
    }

  private val sameParent2 = ProposalSampling.SameParent(ProposalCount(2))

  /** Gives each index of `script` in order, and then the last one. Counts its calls. */
  private final class ScriptedSelector(script: Int*) extends CandidateSelector:
    var draws: Int                                 = 0
    def select(state: GepaState, rng: Random): Int =
      val index = script(math.min(draws, script.size - 1))
      draws += 1
      index

  private def proposal(text: String, before: Vector[Double], after: Vector[Double]): Proposal =
    Proposal(instruction(text), parent = 0, scoresBefore = before, scoresAfter = after)

  // ── The strategies ──────────────────────────────────────────────────────────────────────────────────────────

  test("the defaults are those of upstream: one task, strict acceptance, all improvements") {
    val defaults = GepaConfig(budget = GepaBudget.MetricCalls(MetricCallCount(10)))
    assertEquals(defaults.proposalSampling, ProposalSampling.Single)
    assertEquals(defaults.proposalSelection, ProposalSelection.AllImprovements)
    assertEquals(defaults.acceptance, ProposalAcceptance.StrictImprovement)
  }

  test("each sampling strategy has its number of tasks") {
    assertEquals(ProposalSampling.Single.taskCount, 1)
    assertEquals(ProposalSampling.SameParent(ProposalCount(3)).taskCount, 3)
    assertEquals(ProposalSampling.Independent(ProposalCount(2)).taskCount, 2)
    assertEquals(ProposalSampling.PxN(ProposalCount(2), ProposalCount(3)).taskCount, 6)
    assert(compileErrors("dspy4s.gepa.ProposalCount(0)").nonEmpty)
    assert(ProposalCount.either(0).isLeft)
  }

  test("strict acceptance needs a larger sum, and the other criterion accepts an equal sum") {
    val better = proposal("better", Vector(0.0, 1.0), Vector(1.0, 1.0))
    val equal  = proposal("equal", Vector(0.0, 1.0), Vector(1.0, 0.0))
    val worse  = proposal("worse", Vector(1.0, 1.0), Vector(1.0, 0.0))
    assertEquals(
      Vector(better, equal, worse).map(ProposalAcceptance.StrictImprovement.accepts),
      Vector(true, false, false)
    )
    assertEquals(
      Vector(better, equal, worse).map(ProposalAcceptance.ImprovementOrEqual.accepts),
      Vector(true, true, false)
    )
    assertEquals(better.gain, 1.0)
  }

  test("each selection strategy takes only proposals that pass, in its order") {
    val small  = proposal("small", Vector(0.0, 0.0), Vector(0.5, 0.5))
    val large  = proposal("large", Vector(0.0, 0.0), Vector(1.0, 1.0))
    val twin   = proposal("twin", Vector(0.0, 0.0), Vector(1.0, 1.0))
    val worse  = proposal("worse", Vector(1.0, 1.0), Vector(0.0, 0.0))
    val all    = Vector(small, worse, large, twin)
    val strict = ProposalAcceptance.StrictImprovement

    assertEquals(ProposalSelection.AllImprovements.select(all, strict), Vector(small, large, twin))
    // The first proposal wins a tie.
    assertEquals(ProposalSelection.BestImprovement.select(all, strict), Vector(large))
    assertEquals(ProposalSelection.BestImprovement.select(Vector(worse), strict), Vector.empty)
    // Largest gain first. Equal gains keep the task order.
    assertEquals(ProposalSelection.TopK(ProposalCount(2)).select(all, strict), Vector(large, twin))
    assertEquals(ProposalSelection.TopK(ProposalCount(5)).select(all, strict), Vector(large, twin, small))
    assertEquals(ProposalSelection.AllImprovements.select(Vector.empty, strict), Vector.empty)
  }

  test("each selection strategy has a largest number of selected proposals") {
    assertEquals(ProposalSelection.AllImprovements.limit(4), 4)
    assertEquals(ProposalSelection.BestImprovement.limit(4), 1)
    assertEquals(ProposalSelection.TopK(ProposalCount(2)).limit(4), 2)
    assertEquals(ProposalSelection.TopK(ProposalCount(6)).limit(4), 4)
  }

  // ── The minibatch sampler ───────────────────────────────────────────────────────────────────────────────────

  test("a later call of an iteration takes the next window, with no new shuffle") {
    def sampler = MinibatchSampler.of(BatchSamplerKind.EpochShuffled, MinibatchSize(2), seed = 3L)
    // The walk of an iteration with one task: the windows of iterations 0 and 1 are the two halves of one shuffle.
    val single  = sampler
    val window0 = single.sample(4, iteration = 0)
    val window1 = single.sample(4, iteration = 1)
    assertEquals((window0 ++ window1).sorted, Vector(0, 1, 2, 3))

    val multi = sampler
    assertEquals(multi.sample(4, iteration = 0, callInIteration = 0), window0)
    assertEquals(multi.sample(4, iteration = 0, callInIteration = 1), window1)
    // The windows wrap in the same shuffle.
    assertEquals(multi.sample(4, iteration = 0, callInIteration = 2), window0)
    // The first call of the next iteration is the same as with one task.
    assertEquals(multi.sample(4, iteration = 1, callInIteration = 0), window1)
    assertEquals(multi.sample(4, iteration = 1, callInIteration = 1), window0)
  }

  // ── The batch evaluation of the adapter ─────────────────────────────────────────────────────────────────────

  test("the threads are divided between the candidates as upstream does") {
    assertEquals(GepaAdapter.threadShares(total = 8, items = 3), Vector(3, 3, 2))
    assertEquals(GepaAdapter.threadShares(total = 4, items = 2), Vector(2, 2))
    assertEquals(GepaAdapter.threadShares(total = 8, items = 1), Vector(8))
    assertEquals(GepaAdapter.threadShares(total = 2, items = 3), Vector(1, 1, 1))
    assertEquals(GepaAdapter.threadShares(total = 1, items = 4), Vector(1, 1, 1, 1))
    assertEquals(GepaAdapter.threadShares(total = 8, items = 0), Vector.empty)
  }

  test("a batch evaluation gives the results in the order of the items, each with its share of the threads") {
    val metric = new GradedMetric
    val items  = Vector(
      instruction("Use the CITY.") -> Vector(example("Question one?")),
      instruction("Answer.")       -> Vector(example("Question two?")),
      instruction("NEAR is fine.") -> Vector(example("Question three?"))
    )
    val context =
      RuntimeContext(lm = Some(new TaskLm), adapter = Some(ChatAdapter()), numThreads = Some(ThreadCount(8)))
    val batches = RuntimeEnvironment.withSettings(context) {
      new GepaAdapter(program, metric).batchEvaluate(items, captureTraces = false)(using RuntimeEnvironment.current)
    }
    assertEquals(batches.map(_.scores), Vector(Vector(1.0), Vector(0.0), Vector(0.5)))
    assertEquals(batches.map(_.trajectories), Vector(None, None, None))
    assertEquals(
      Vector("Question one?", "Question two?", "Question three?").map(metric.threads.get),
      Vector(Some(Some(3)), Some(Some(3)), Some(Some(2)))
    )
  }

  test("a batch evaluation with traces gives the trajectories of each item") {
    val items   = Vector(instruction("Use the CITY.") -> dataset(2), instruction("Answer.") -> dataset(1))
    val batches =
      RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new TaskLm), adapter = Some(ChatAdapter()))) {
        new GepaAdapter(program, new GradedMetric)
          .batchEvaluate(items, captureTraces = true)(using RuntimeEnvironment.current)
      }
    assertEquals(batches.map(_.scores), Vector(Vector(1.0, 1.0), Vector(0.0)))
    assertEquals(batches.map(_.trajectories.map(_.map(_.trace.size))), Vector(Some(Vector(1, 1)), Some(Vector(1))))
  }

  test("a batch evaluation of no item gives no result, and an item with no example gives an empty batch") {
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new TaskLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val adapter          = new GepaAdapter(program, new GradedMetric)
      assertEquals(adapter.batchEvaluate(Vector.empty, captureTraces = false), Vector.empty)
      assertEquals(
        adapter.batchEvaluate(Vector(seedCandidate -> Vector.empty), captureTraces = false),
        Vector(EvaluationBatch(Vector.empty, Vector.empty, trajectories = None))
      )
    }
  }

  test("a batch evaluation runs the candidates at the same time") {
    // Each candidate has one example, so two calls can run at the same time only when two candidates do.
    val gate  = new CyclicBarrier(2)
    val items = Vector(
      instruction("CHILD one, CITY.") -> Vector(example("Question one?")),
      instruction("CHILD two, CITY.") -> Vector(example("Question two?"))
    )
    val context = RuntimeContext(lm = Some(new TaskLm(Some(gate))), adapter = Some(ChatAdapter()))
    val batches = RuntimeEnvironment.withSettings(context) {
      new GepaAdapter(program, new GradedMetric)
        .batchEvaluate(items, captureTraces = false)(using RuntimeEnvironment.current)
    }
    assertEquals(batches.map(_.scores), Vector(Vector(1.0), Vector(1.0)))
  }

  test("a batch evaluation with fewer threads than items gives each item one thread") {
    val items = Vector(
      instruction("Use the CITY.") -> Vector(example("Question one?")),
      instruction("Answer.")       -> Vector(example("Question two?")),
      instruction("NEAR is fine.") -> Vector(example("Question three?"))
    )
    // Two threads: two worker threads evaluate the three items. One thread: the thread of the caller evaluates them.
    Vector(2, 1).foreach { threads =>
      val metric  = new GradedMetric
      val context = RuntimeContext(
        lm = Some(new TaskLm),
        adapter = Some(ChatAdapter()),
        numThreads = Some(ThreadCount.applyUnsafe(threads))
      )
      val batches = RuntimeEnvironment.withSettings(context) {
        new GepaAdapter(program, metric).batchEvaluate(items, captureTraces = false)(using RuntimeEnvironment.current)
      }
      assertEquals(batches.map(_.scores), Vector(Vector(1.0), Vector(0.0), Vector(0.5)), s"threads: $threads")
      assertEquals(
        Vector("Question one?", "Question two?", "Question three?").map(metric.threads.get),
        Vector(Some(Some(1)), Some(Some(1)), Some(Some(1))),
        s"threads: $threads"
      )
    }
  }

  test("an exception of one evaluation of a batch goes to the caller as it is") {
    // The structure of the program throws when it applies the second candidate.
    val items = Vector(instruction("Answer.") -> dataset(1), instruction("BOOM") -> dataset(1))
    // Four threads: a worker thread evaluates each item. One thread: the thread of the caller evaluates the items.
    Vector(4, 1).foreach { threads =>
      val context = RuntimeContext(
        lm = Some(new TaskLm),
        adapter = Some(ChatAdapter()),
        numThreads = Some(ThreadCount.applyUnsafe(threads))
      )
      val error = RuntimeEnvironment.withSettings(context) {
        intercept[IllegalStateException] {
          new GepaAdapter(Fragile(program), new GradedMetric)
            .batchEvaluate(items, captureTraces = false)(using RuntimeEnvironment.current)
        }
      }
      assertEquals(error.getMessage, "BOOM", s"threads: $threads")
    }
  }

  test("the cache evaluates more than one candidate in one batch, and equal candidates one time") {
    val lm = new TaskLm
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val cache            = new GepaEvalCache[DynamicPredict](new GepaAdapter(program, new GradedMetric))
      val city             = instruction("Use the CITY.")
      val scored           = cache.scoredAll(Vector(city, seedCandidate, city), dataset(2))
      assertEquals(scored.map(_.scores), Vector(Vector(1.0, 1.0), Vector(0.0, 0.0), Vector(1.0, 1.0)))
      assertEquals(scored.map(_.evaluated), Vector(2, 2, 0))
      assertEquals(lm.calls.get(), 4)

      assertEquals(cache.scoredAll(Vector(seedCandidate, city), dataset(2)).map(_.evaluated), Vector(0, 0))
      assertEquals(cache.scored(city, dataset(2)).evaluated, 0)
      assertEquals(cache.scoredAll(Vector.empty, dataset(2)), Vector.empty)
      assertEquals(lm.calls.get(), 4)
    }
  }

  // ── The engine ──────────────────────────────────────────────────────────────────────────────────────────────

  test("two tasks for one parent give two candidates in task order, each from its own minibatch") {
    val reflection = new ReflectionLm("CITY one", "CITY two")
    // 4 for the seed, 2 × 2 for the parents, 2 × 2 for the children, and 2 × 4 for the two validations.
    val result = run(config(budget = 20, sameParent2), reflection)

    assertEquals(reflection.requests.size, 2)
    assertEquals(
      result.state.candidates.toVector,
      Vector(seedCandidate, instruction("CITY one"), instruction("CITY two"))
    )
    assertEquals(result.state.parents, Vector(Vector.empty, Vector(0), Vector(0)))
    assertEquals(result.totalMetricCalls.toInt, 20)
    // The two tasks have the two windows of the trainset.
    val windows = reflection.requests.toVector.map(questionNumbers)
    assertEquals(windows.map(_.size), Vector(2, 2))
    assertEquals(windows.reduce(_ ++ _), Set(1, 2, 3, 4))
  }

  test("children with equal candidates give one candidate and one validation") {
    val reflection = new ReflectionLm("CITY one", "CITY one")
    val result     = run(config(budget = 20, sameParent2), reflection)

    assertEquals(reflection.requests.size, 2)
    assertEquals(result.state.candidates.toVector, Vector(seedCandidate, instruction("CITY one")))
    // 4 for the seed, 4 for the parents, 4 for the children, and 4 for one validation.
    assertEquals(result.totalMetricCalls.toInt, 16)
  }

  test("equal parent evaluations run one time") {
    // The trainset has one window, so the two tasks have the same parent and the same minibatch.
    val reflection = new ReflectionLm("CITY one", "CITY two")
    val result     = run(config(budget = 20, sameParent2), reflection, trainset = dataset(2))

    assertEquals(reflection.requests.size, 2)
    assertEquals(result.numCandidates.toInt, 3)
    // 4 for the seed, 2 for the one parent evaluation, 4 for the children, and 8 for the two validations.
    assertEquals(result.totalMetricCalls.toInt, 18)
  }

  test("the selection of the best improvement adds one candidate") {
    // NEAR gives a gain of 1.0 on its minibatch, and CITY gives a gain of 2.0.
    val best = config(budget = 16, sameParent2, selection = ProposalSelection.BestImprovement)
    assertEquals(
      run(best, new ReflectionLm("NEAR one", "CITY two")).state.candidates.toVector,
      Vector(seedCandidate, instruction("CITY two"))
    )
    // The first proposal wins a tie.
    val tie = run(best, new ReflectionLm("CITY one", "CITY two"))
    assertEquals(tie.state.candidates.toVector, Vector(seedCandidate, instruction("CITY one")))
    // 4 for the seed, 4 for the parents, 4 for the children, and 4 for one validation.
    assertEquals(tie.totalMetricCalls.toInt, 16)
  }

  test("the selection of all improvements keeps the task order, and the top k has the largest gain first") {
    val all = run(config(budget = 20, sameParent2), new ReflectionLm("NEAR one", "CITY two"))
    assertEquals(
      all.state.candidates.toVector,
      Vector(seedCandidate, instruction("NEAR one"), instruction("CITY two"))
    )

    val topTwo = config(budget = 20, sameParent2, selection = ProposalSelection.TopK(ProposalCount(2)))
    assertEquals(
      run(topTwo, new ReflectionLm("NEAR one", "CITY two")).state.candidates.toVector,
      Vector(seedCandidate, instruction("CITY two"), instruction("NEAR one"))
    )

    val topOne = config(budget = 16, sameParent2, selection = ProposalSelection.TopK(ProposalCount(1)))
    assertEquals(
      run(topOne, new ReflectionLm("NEAR one", "CITY two")).state.candidates.toVector,
      Vector(seedCandidate, instruction("CITY two"))
    )
  }

  test("the acceptance of an equal sum adds a child that strict acceptance rejects") {
    // The new instruction has no token, so the child has the same scores as its parent.
    val lateral = config(budget = 12, ProposalSampling.Single, acceptance = ProposalAcceptance.ImprovementOrEqual)
    val added   = run(lateral, new ReflectionLm("No token here."))
    assertEquals(added.state.candidates.toVector, Vector(seedCandidate, instruction("No token here.")))
    assertEquals(added.totalMetricCalls.toInt, 12)

    val strict = run(config(budget = 12, ProposalSampling.Single), new ReflectionLm("No token here."))
    assertEquals(strict.numCandidates.toInt, 1)
    assertEquals(strict.totalMetricCalls.toInt, 8)
  }

  test("a task with a perfect minibatch makes no proposal, and the other task makes one") {
    // Each minibatch has one example. The seed is right on the EASY example and wrong on the other one.
    val trainset   = Vector(example("EASY question?"), example("Capital of France (1)?"))
    val reflection = new ReflectionLm("CITY one")
    val result     =
      run(config(budget = 10, sameParent2, minibatch = 1), reflection, trainset = trainset, valset = dataset(2))

    assertEquals(reflection.requests.size, 1)
    assertEquals(result.state.candidates.toVector, Vector(seedCandidate, instruction("CITY one")))
    // 2 for the seed, 2 for the parents, 1 for the one child, and 2 for its validation.
    assertEquals(result.totalMetricCalls.toInt, 7)
  }

  test("a task whose proposal fails makes no child, and the other task makes one") {
    // The first task gets an empty instruction two times. The second task gets a new instruction.
    val reflection = new ReflectionLm(" ", " ", "CITY two")
    val result     = run(config(budget = 20, sameParent2), reflection)

    assertEquals(reflection.requests.size, 3)
    assertEquals(result.state.candidates.toVector, Vector(seedCandidate, instruction("CITY two")))
    // 4 for the seed, 4 for the parents, 2 for the one child, and 4 for its validation.
    assertEquals(result.totalMetricCalls.toInt, 14)
  }

  test("the other sampling strategies give their number of tasks") {
    def requests(sampling: ProposalSampling, budget: Int): Int =
      val reflection = new ReflectionLm("CITY one", "CITY two", "CITY three")
      val _          = run(config(budget, sampling), reflection)
      reflection.requests.size

    // The pool has the seed only, so each parent draw gives the seed. The budget holds one iteration.
    assertEquals(requests(ProposalSampling.Independent(ProposalCount(2)), budget = 20), 2)
    assertEquals(requests(ProposalSampling.PxN(ProposalCount(1), ProposalCount(2)), budget = 20), 2)
    assertEquals(requests(ProposalSampling.PxN(ProposalCount(2), ProposalCount(1)), budget = 20), 2)
    assertEquals(requests(ProposalSampling.SameParent(ProposalCount(3)), budget = 28), 3)
  }

  test("each parent draw calls the candidate selector one time, and a child has the parent of its task") {
    def draws(sampling: ProposalSampling, budget: Int): Int =
      val selector = new ScriptedSelector(0)
      val settings = config(budget, sampling).copy(candidateSelector = selector)
      val _        = run(settings, new ReflectionLm("CITY one", "CITY two"))
      selector.draws

    // The budget holds one iteration.
    assertEquals(draws(ProposalSampling.Single, budget = 12), 1)
    assertEquals(draws(sameParent2, budget = 20), 1)
    assertEquals(draws(ProposalSampling.Independent(ProposalCount(2)), budget = 20), 2)
    assertEquals(draws(ProposalSampling.PxN(ProposalCount(1), ProposalCount(2)), budget = 20), 1)
    assertEquals(draws(ProposalSampling.PxN(ProposalCount(2), ProposalCount(1)), budget = 20), 2)

    // The budget holds two iterations. The two draws of the second iteration give the two candidates of the first one.
    val selector = new ScriptedSelector(0, 0, 1, 2)
    val settings =
      config(budget = 36, ProposalSampling.Independent(ProposalCount(2))).copy(candidateSelector = selector)
    val result = run(settings, new ReflectionLm("NEAR one", "NEAR two", "CITY three", "CITY four"))
    assertEquals(selector.draws, 4)
    assertEquals(
      result.state.candidates.toVector,
      Vector("NEAR one", "NEAR two", "CITY three", "CITY four").map(instruction).prepended(seedCandidate)
    )
    assertEquals(result.state.parents, Vector(Vector.empty, Vector(0), Vector(0), Vector(1), Vector(2)))
    assertEquals(result.totalMetricCalls.toInt, 36)
  }

  test("an iteration starts only when its largest possible cost fits in the budget") {
    // Two tasks cost 2 × (2 × 2 + 4) = 16 at most. The seed costs 4, so a budget of 19 does not hold the iteration.
    val reflection = new ReflectionLm("CITY one", "CITY two")
    val none       = run(config(budget = 19, sameParent2), reflection)
    assertEquals(reflection.requests.size, 0)
    assertEquals(none.numCandidates.toInt, 1)
    assertEquals(none.totalMetricCalls.toInt, 4)

    // One task costs 8 at most, so the same budget holds one iteration.
    val single = run(config(budget = 19, ProposalSampling.Single), new ReflectionLm("CITY one"))
    assertEquals(single.numCandidates.toInt, 2)
    assertEquals(single.totalMetricCalls.toInt, 12)

    // With the selection of the best improvement, two tasks cost 2 × 2 × 2 + 4 = 12 at most.
    val best = config(budget = 16, sameParent2, selection = ProposalSelection.BestImprovement)
    assertEquals(run(best, new ReflectionLm("CITY one", "CITY two")).numCandidates.toInt, 2)
  }

  test("the engine evaluates the children of one iteration at the same time") {
    // Each minibatch has one example, so the two child calls can run at the same time only when the two children do.
    // A child whose call fails has the failure score, and is not accepted.
    val gate       = new CyclicBarrier(2)
    val reflection = new ReflectionLm("CHILD one, CITY.", "CHILD two, CITY.")
    val result     = run(
      config(budget = 10, sameParent2, minibatch = 1),
      reflection,
      trainset = dataset(2),
      valset = dataset(2),
      taskLm = new TaskLm(Some(gate))
    )
    assertEquals(
      result.state.candidates.toVector,
      Vector(seedCandidate, instruction("CHILD one, CITY."), instruction("CHILD two, CITY."))
    )
    assertEquals(result.totalMetricCalls.toInt, 10)
  }

  test("each task of one iteration moves the round-robin pointer of its parent") {

    /** Stage `a` answers "ok" only with FIXED_A in its prompt. Stage `b` answers "ok" only with FIXED_B. */
    final class StageLm extends LanguageModel:
      override val id: String                                                                    = "stage"
      override val mode: LmMode                                                                  = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        val prompt          = request.messages.flatMap(_.text).mkString("\n")
        val (field, marker) = if prompt.contains("## a ##") then ("a", "FIXED_A") else ("b", "FIXED_B")
        val value           = if prompt.contains(marker) then "ok" else "bad"
        Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## $field ## ]]\n$value\n\n[[ ## completed ## ]]"))))

    /** Records the component of each request, and gives that component its fix. */
    final class ComponentReflectionLm extends LanguageModel:
      val components: ArrayBuffer[String]                                                        = ArrayBuffer.empty
      override val id: String                                                                    = "reflect"
      override val mode: LmMode                                                                  = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        val prompt    = request.messages.flatMap(_.text).mkString("\n")
        val component = if prompt.contains("Component A") then "A" else "B"
        val _         = components.synchronized(components += component)
        Right(LmResponse(outputs = Vector(LmOutput(text = reply(s"Component $component. FIXED_$component")))))

    val partialCredit: FeedbackMetric = new FeedbackMetric:
      override def name: String = "partial_credit"
      override def feedback(
          example       : Example,
          prediction    : RawPrediction,
          trace         : Vector[TraceEntry],
          component     : Option[String],
          componentTrace: Vector[TraceEntry]
      )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
        val ok = Vector("a", "b").count(name => prediction.get(name).map(DynamicValues.renderText).contains("ok"))
        Right(ScoreWithFeedback(ok * 0.5, "Each stage must answer ok."))

    def predict(layout: String, text: String): DynamicPredict =
      DynamicPredict(layout = SignatureLayout.parse(layout).toOption.get.withInstructions(Some(text)))

    val twoStage  = TwoStage(predict("question -> a", "Component A."), predict("question -> b", "Component B."))
    val questions = (1 to 4).toVector.map(i =>
      Example(values = DynamicValues.record("question" := s"Question $i?"), inputKeys = Set("question"))
    )
    val reflection = new ComponentReflectionLm
    val result     =
      RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new StageLm), adapter = Some(ChatAdapter()))) {
        given RuntimeContext = RuntimeEnvironment.current
        new Gepa[TwoStage](partialCredit, reflection, config(budget = 20, sameParent2))
          .compile(twoStage, questions, questions)
          .fold(error => fail(error.message), identity)
      }

    // The first task changes component A of the seed, and the second task changes component B.
    assertEquals(reflection.components.toVector, Vector("A", "B"))
    assertEquals(
      result.state.candidates.toVector,
      Vector[Candidate](
        Map(first -> Some("Component A."), second         -> Some("Component B.")),
        Map(first -> Some("Component A. FIXED_A"), second -> Some("Component B.")),
        Map(first -> Some("Component A."), second         -> Some("Component B. FIXED_B"))
      )
    )
    // The pointer of the seed moved two times. Each child takes the pointer of its parent after the two moves.
    assertEquals(Vector(0, 1, 2).map(result.state.pointerOf), Vector(0, 0, 0))
  }
