package dspy4s.optimize

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.ValidationError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.evaluate.contracts.Metric
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.programs.ProgramRunner
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.OptimizableLeaf
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.optimization.OptimizableView
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** A program whose leaf count is known only at run time. Each step answers the question, and the last answer wins. */
final case class ScriptedChain(steps: Vector[ScriptedPredictProgram])

object ScriptedChain:
  private val leaf = summon[OptimizableLeaf[ScriptedPredictProgram]]

  given OptimizableStructure[ScriptedChain] with
    type Arity = Int
    def arity(program  : ScriptedChain): Int                                                   = program.steps.size
    def inspect(program: ScriptedChain): Vector[OptimizableView]                               = program.steps.map(leaf.inspect)
    def replace(program: ScriptedChain, updates: Vector[OptimizableParameters]): ScriptedChain =
      ScriptedChain(program.steps.zip(updates).map((step, update) => leaf.set(step, update)))

  given ProgramRunner[ScriptedChain] with
    def run(program: ScriptedChain, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      program.steps.foldLeft[Either[DspyError, RawPrediction]](Right(RawPrediction(DynamicValue.Record.empty))) {
        (previous, step) => previous.flatMap(_ => step(call).map(_.raw))
      }

class BootstrapFewShotSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  private val signature = SignatureDsl.parse("question: str -> answer: str").toOption.get

  private val exactMatch: Metric = new ExactMatch(answerField = "answer")

  /** Two examples with the same question. The second has one more field, so the two examples are not equal. */
  private val twins = Vector(
    Example(rec("question" := "q1", "answer" := "a1"), inputKeys = Set("question")),
    Example(rec("question" := "q1", "answer" := "a1", "note" := "twin"), inputKeys = Set("question"))
  )

  test("BootstrapFewShot returns original student when trainset is empty") {
    val student          = ScriptedPredictProgram(Map.empty, signature)
    val optimizer        = new BootstrapFewShot[ScriptedPredictProgram]()
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(student, Vector.empty)
    assert(result.isRight)
    assertEquals(result.toOption.get.bestProgram.demos.size, 0)
  }

  test("BootstrapFewShot bootstraps demos from examples where teacher succeeds") {
    val teacher  = ScriptedPredictProgram(Map("q1" -> "a1", "q2" -> "a2", "q3" -> "a3"), signature)
    val trainset = Vector(
      Example(rec("question" := "q1", "answer" := "a1"), inputKeys = Set("question")),
      Example(rec("question" := "q2", "answer" := "a2"), inputKeys = Set("question")),
      Example(rec("question" := "q3", "answer" := "a3"), inputKeys = Set("question"))
    )
    val student   = ScriptedPredictProgram(Map.empty, signature)
    val optimizer = new BootstrapFewShot[ScriptedPredictProgram](
      BootstrapFewShotConfig(
        maxBootstrappedDemos = DemoCount(2),
        maxLabeledDemos = DemoCount(1),
        maxRounds = RoundCount(1)
      )
    )
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(student, trainset, teacher = Some(teacher))
    assert(result.isRight, s"compile failed: ${result.left.toOption}")
    val report = result.toOption.get
    assert(report.bestProgram.demos.size >= 2, s"expected at least 2 demos, got ${report.bestProgram.demos.size}")

    val bootstrapped = report.bestProgram.demos.filter(_.augmented)
    assertEquals(bootstrapped.size, 2)
  }

  test("BootstrapFewShot uses metric to filter which traces to keep") {
    val teacher  = ScriptedPredictProgram(Map("q1" -> "wrong", "q2" -> "expected", "q3" -> "also-wrong"), signature)
    val trainset = Vector(
      Example(rec("question" := "q1", "answer" := "expected"), inputKeys = Set("question")),
      Example(rec("question" := "q2", "answer" := "expected"), inputKeys = Set("question")),
      Example(rec("question" := "q3", "answer" := "expected"), inputKeys = Set("question"))
    )

    val exactMatch = new dspy4s.evaluate.metrics.ExactMatch(answerField = "answer")

    val student   = ScriptedPredictProgram(Map.empty, signature)
    val optimizer = new BootstrapFewShot[ScriptedPredictProgram](
      BootstrapFewShotConfig(
        metric = Some(exactMatch),
        maxBootstrappedDemos = DemoCount(10),
        maxLabeledDemos = DemoCount(0)
      )
    )
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(student, trainset, teacher = Some(teacher))
    assert(result.isRight)
    val bootstrapped = result.toOption.get.bestProgram.demos.filter(_.augmented)
    assertEquals(bootstrapped.size, 1, "only q2 should pass the exact match metric")
    assertEquals(lookupString(bootstrapped.head.values, "question"), "q2")
  }

  test("BootstrapFewShot returns RuntimeError when teacher throws and errors exceed maxErrors") {
    val trainset = (1 to 5).map(i =>
      Example(rec("question" := s"q$i", "answer" := s"a$i"), inputKeys = Set("question"))
    ).toVector

    val blowingUp = ScriptedPredictProgram(
      answers = Map.empty,
      layout = signature,
      failsWith = Some(new RuntimeException("boom"))
    )
    val optimizer = new BootstrapFewShot[ScriptedPredictProgram](
      BootstrapFewShotConfig(maxErrors = ErrorLimit(2), maxBootstrappedDemos = DemoCount(10))
    )
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(blowingUp, trainset)
    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[RuntimeError])
  }

  test("BootstrapFewShot fills remaining slots from the examples that were not bootstrapped") {
    // There is no metric, so the first example passes and the bootstrap stops at `maxBootstrappedDemos = 1`. The other
    // four examples did not run. They are the labeled pool (Python: `self.validation`), and one of them fills the slot
    // that `maxLabeledDemos = 2` leaves.
    val teacher  = ScriptedPredictProgram(Map("q1" -> "a1"), signature)
    val trainset = (1 to 5).map(i =>
      Example(rec("question" := s"q$i", "answer" := s"a$i"), inputKeys = Set("question"))
    ).toVector

    val student   = ScriptedPredictProgram(Map.empty, signature)
    val optimizer = new BootstrapFewShot[ScriptedPredictProgram](
      BootstrapFewShotConfig(
        maxBootstrappedDemos = DemoCount(1),
        maxLabeledDemos = DemoCount(2),
        maxErrors = ErrorLimit(100),
        seed = 7L
      )
    )
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(student, trainset, teacher = Some(teacher))
    assert(result.isRight)
    val demos   = result.toOption.get.bestProgram.demos
    val labeled = demos.filterNot(_.augmented)
    assertEquals(demos.count(_.augmented), 1)
    assertEquals(labeled.size, 1)
    assert(labeled.forall(trainset.drop(1).contains), s"the labeled demo must come from the pool, got $labeled")
  }

  // ── Errors (Python: the `except Exception` block of `_bootstrap_one_example`) ──

  private def numbered(count: Int): Vector[Example] = (1 to count).map(i =>
    Example(rec("question" := s"q$i", "answer" := s"a$i"), inputKeys = Set("question"))
  ).toVector

  /** Accepts each prediction, but fails for the questions that `failOn` selects: with a `Left`, or with an exception.
    */
  private final class FailingMetric(failOn: String => Boolean, thrown: Boolean = false) extends Metric:
    override def name: String = "failing"
    override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
        RuntimeContext
    ): Either[DspyError, Double] =
      if !failOn(lookupString(example.values, "question")) then Right(1.0)
      else if thrown then throw new IllegalStateException("metric boom")
      else Left(RuntimeError("metric", "metric failed"))

  /** Compile a scripted student with a scripted teacher. The answers are not important for these tests. */
  private def compileScripted(
      config  : BootstrapFewShotConfig,
      trainset: Vector[Example]
  ): Either[DspyError, OptimizationReport[ScriptedPredictProgram]] =
    val program          = ScriptedPredictProgram(Map.empty, signature)
    given RuntimeContext = RuntimeEnvironment.current
    new BootstrapFewShot[ScriptedPredictProgram](config).compile(program, trainset, teacher = Some(program))

  test("BootstrapFewShot counts a metric that returns a Left as an error") {
    val config = BootstrapFewShotConfig(metric = Some(new FailingMetric(_ => true)), maxErrors = ErrorLimit(2))
    compileScripted(config, numbered(5)) match
      case Left(error: RuntimeError) => assert(error.message.contains("metric failed"), error.message)
      case other                     => fail(s"expected the error limit to stop the compile, got $other")
  }

  test("BootstrapFewShot counts a metric that throws as an error") {
    val metric = new FailingMetric(_ => true, thrown = true)
    val config = BootstrapFewShotConfig(metric = Some(metric), maxErrors = ErrorLimit(2))
    compileScripted(config, numbered(5)) match
      case Left(error: RuntimeError) => assert(error.message.contains("metric boom"), error.message)
      case other                     => fail(s"expected the error limit to stop the compile, got $other")
  }

  test("BootstrapFewShot continues after an error below the limit, and reports the counts") {
    val config = BootstrapFewShotConfig(
      metric = Some(new FailingMetric(_.equals("q1"))),
      maxBootstrappedDemos = DemoCount(2),
      maxLabeledDemos = DemoCount(0),
      maxErrors = ErrorLimit(3)
    )
    val report = compileScripted(config, numbered(5)).toOption.get
    assertEquals(report.bestProgram.demos.map(demo => lookupString(demo.values, "question")), Vector("q2", "q3"))
    val counts = report.candidates.head.metadata
    assertEquals(counts.get("num_errors"), Some(1))
    assertEquals(counts.get("num_failed_examples"), Some(1))
    assertEquals(counts.get("num_bootstrapped_demos"), Some(2))
    assertEquals(counts.get("num_labeled_demos_per_predictor"), Some(Vector(0)))
  }

  // ── Teacher (Python: `_prepare_student_and_teacher`) ──
  // A `DemoAwarePredictProgram` with no answers knows a question only from a demo. The tests below use that to show
  // which demos the teacher has in a run.

  private def compileDemoAware(
      config  : BootstrapFewShotConfig,
      trainset: Vector[Example],
      student : DemoAwarePredictProgram         = DemoAwarePredictProgram(signature),
      teacher : Option[DemoAwarePredictProgram] = None
  ): Vector[Example] =
    given RuntimeContext = RuntimeEnvironment.current
    new BootstrapFewShot[DemoAwarePredictProgram](config)
      .compile(student, trainset, teacher)
      .toOption
      .get
      .bestProgram
      .demos

  test("BootstrapFewShot removes the current example from the demos of the teacher") {
    // The teacher has the three labeled demos, but not the demo of the example that it runs. Thus no example passes.
    val config = BootstrapFewShotConfig(metric = Some(exactMatch), maxLabeledDemos = DemoCount(3))
    val demos  = compileDemoAware(config, numbered(3))
    assertEquals(demos.count(_.augmented), 0)
    assertEquals(demos.count(!_.augmented), 3)
  }

  test("BootstrapFewShot gives labeled demos to a teacher that has no demos") {
    // Each twin passes only when the teacher has the other twin as a labeled demo.
    val config = BootstrapFewShotConfig(metric = Some(exactMatch), maxLabeledDemos = DemoCount(2))
    val demos  = compileDemoAware(config, twins, teacher = Some(DemoAwarePredictProgram(signature)))
    assertEquals(demos.count(_.augmented), 2)
  }

  test("BootstrapFewShot gives no labeled demos to the teacher when maxLabeledDemos is 0") {
    val config = BootstrapFewShotConfig(metric = Some(exactMatch), maxLabeledDemos = DemoCount(0))
    val demos  = compileDemoAware(config, twins, teacher = Some(DemoAwarePredictProgram(signature)))
    assertEquals(demos.size, 0)
  }

  test("BootstrapFewShot gives no labeled demos to a teacher that has a demo") {
    // The teacher has a demo, so it is compiled. It keeps that demo, and it does not learn the twins.
    val own     = Example(rec("question" := "other", "answer" := "x"), inputKeys = Set("question"))
    val teacher = DemoAwarePredictProgram(signature, demos = Vector(own))
    val config  = BootstrapFewShotConfig(metric = Some(exactMatch), maxLabeledDemos = DemoCount(2))
    val demos   = compileDemoAware(config, twins, teacher = Some(teacher))
    assertEquals(demos.count(_.augmented), 0)
  }

  test("BootstrapFewShot keeps the demos of a student that is its own teacher") {
    // Python: the teacher is `student.deepcopy()`. The student passes only with its own demo.
    val hint    = Example(rec("question" := "q1", "answer" := "a1", "note" := "hint"), inputKeys = Set("question"))
    val student = DemoAwarePredictProgram(signature, demos = Vector(hint))
    val config  = BootstrapFewShotConfig(metric = Some(exactMatch), maxLabeledDemos = DemoCount(0))
    val demos   = compileDemoAware(config, twins.take(1), student = student)
    assertEquals(demos.count(_.augmented), 1)
    assert(!demos.contains(hint), "the compiled student gets new demos, not its old demos")
  }

  test("BootstrapFewShot gives no labeled demos to a student that has a demo and is its own teacher") {
    // The rule for a compiled teacher is the same for the two cases. With labeled demos, each twin would pass.
    val own     = Example(rec("question" := "other", "answer" := "x"), inputKeys = Set("question"))
    val student = DemoAwarePredictProgram(signature, demos = Vector(own))
    val config  = BootstrapFewShotConfig(metric = Some(exactMatch), maxLabeledDemos = DemoCount(2))
    val demos   = compileDemoAware(config, twins, student = student)
    assertEquals(demos.count(_.augmented), 0)
  }

  test("BootstrapFewShot rejects a teacher with another number of leaves") {
    val longer           = SignatureDsl.parse("question: str -> answer: str, note: str").toOption.get
    val student          = ScriptedChain(Vector(ScriptedPredictProgram(Map.empty, signature)))
    val teacher          = ScriptedChain(student.steps :+ ScriptedPredictProgram(Map.empty, longer))
    given RuntimeContext = RuntimeEnvironment.current
    new BootstrapFewShot[ScriptedChain]().compile(student, numbered(2), teacher = Some(teacher)) match
      case Left(error: ValidationError) => assert(error.message.contains("same number of predictors"), error.message)
      case other                        => fail(s"expected a ValidationError, got $other")
  }

  // ── Random choices ──

  test("BootstrapFewShot is repeatable for a seed, and the seed changes the labeled sample") {
    def demosFor(seed: Long): Vector[Example] =
      val config =
        BootstrapFewShotConfig(maxBootstrappedDemos = DemoCount(1), maxLabeledDemos = DemoCount(4), seed = seed)
      compileScripted(config, numbered(12)).toOption.get.bestProgram.demos

    val labeled = demosFor(3L).filterNot(_.augmented)
    assertEquals(labeled.size, 3)
    assertEquals(labeled.distinct.size, 3, "the sample has no replacement")
    assert(labeled.forall(numbered(12).drop(1).contains), s"each labeled demo must come from the pool, got $labeled")
    assertEquals(demosFor(3L), demosFor(3L))
    assertNotEquals(demosFor(3L), demosFor(4L))
  }

  /** Three demos of one leaf for one example. `step` tells the runs apart. */
  private def runs(example: Int): Vector[Example] =
    Vector.tabulate(3)(step =>
      Example(rec("question" := s"q$example", "step" := step.toString), inputKeys = Set("question"), augmented = true)
    )

  private def chosenSteps(seed: Long): Vector[Int] =
    Vector.tabulate(400)(example => runs(example).indexOf(BootstrapFewShot.chooseDemo(runs(example), seed).get))

  test("a leaf that ran one time keeps its demo, and a leaf that did not run has no demo") {
    assertEquals(BootstrapFewShot.chooseDemo(Vector.empty, 0L), None)
    assertEquals(BootstrapFewShot.chooseDemo(runs(1).take(1), 0L), runs(1).headOption)
  }

  test("a leaf that ran more times keeps the last demo or one of the others, each with probability 0.5") {
    val steps = chosenSteps(0L)
    val last  = steps.count(_ == 2)
    assert(last > 160 && last < 240, s"the last demo was chosen $last times of 400")
    assert(steps.contains(0) && steps.contains(1), "each of the first N-1 demos must be a possible choice")
    assertEquals(steps, chosenSteps(0L))
    assertNotEquals(steps, chosenSteps(1L))
  }
