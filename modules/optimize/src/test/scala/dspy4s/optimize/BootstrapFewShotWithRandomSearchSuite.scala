package dspy4s.optimize

import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.evaluate.metrics.ExactMatch
import munit.FunSuite

class BootstrapFewShotWithRandomSearchSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  private val signature = SignatureDsl.parse("question: str -> answer: str").toOption.get

  private def oracleTeacher: DemoAwarePredictProgram =
    DemoAwarePredictProgram(
      layout = signature,
      answers = (1 to 10).map(i => s"q$i" -> s"a$i").toMap
    )

  private def zeroShotStudent: DemoAwarePredictProgram =
    DemoAwarePredictProgram(signature)

  test("BootstrapFewShotWithRandomSearch generates multiple candidates and reports the best") {
    val trainset = (1 to 6).map(i =>
      Example(Map("question" -> s"q$i", "answer" -> s"a$i"), inputKeys = Set("question"))
    ).toVector

    val metric = new ExactMatch(answerField = "answer")

    val optimizer = new BootstrapFewShotWithRandomSearch[DemoAwarePredictProgram](
      RandomSearchConfig(
        metric = metric,
        numCandidates = 3,
        maxBootstrappedDemos = 2,
        maxLabeledDemos = 2,
        maxErrors = 20
      )
    )
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(zeroShotStudent, trainset, teacher = Some(oracleTeacher))
    assert(result.isRight, s"compile failed: ${result.left.toOption}")
    val report = result.toOption.get
    assert(report.candidates.size >= 3, s"got only ${report.candidates.size} candidates")
    assert(report.bestProgram.demos.nonEmpty, "best program should have some demos")

    val bestScore = report.candidates.map(_.score).max
    assertEquals(report.bestProgram, report.candidates.find(_.score == bestScore).get.program)
  }

  test("BootstrapFewShotWithRandomSearch stops early when stopAtScore is reached") {
    val trainset = (1 to 4).map(i =>
      Example(Map("question" -> s"q$i", "answer" -> s"a$i"), inputKeys = Set("question"))
    ).toVector

    val metric = new ExactMatch(answerField = "answer")

    val optimizer = new BootstrapFewShotWithRandomSearch[DemoAwarePredictProgram](
      RandomSearchConfig(
        metric = metric,
        numCandidates = 20,
        maxBootstrappedDemos = 4,
        maxLabeledDemos = 4,
        maxErrors = 50,
        stopAtScore = Some(100.0)
      )
    )
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(zeroShotStudent, trainset, teacher = Some(oracleTeacher))
    assert(result.isRight)
    val report = result.toOption.get
    val stoppedEarly = report.metadata.get("stopped_early").collect { case b: Boolean => b }.getOrElse(false)
    assert(stoppedEarly, s"should have stopped early but metadata was: ${report.metadata}")
  }

  test("BootstrapFewShotWithRandomSearch handles empty trainset gracefully") {
    val metric = new ExactMatch(answerField = "answer")
    val optimizer = new BootstrapFewShotWithRandomSearch[DemoAwarePredictProgram](
      RandomSearchConfig(metric = metric, numCandidates = 2)
    )
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(zeroShotStudent, Vector.empty)
    assert(result.isRight)
    val report = result.toOption.get
    assertEquals(report.bestProgram.demos.size, 0)
  }
