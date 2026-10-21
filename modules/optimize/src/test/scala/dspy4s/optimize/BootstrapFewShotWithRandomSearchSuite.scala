package dspy4s.optimize

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ValidationError
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
      Example(rec("question" := s"q$i", "answer" := s"a$i"), inputKeys = Set("question"))
    ).toVector

    val metric = new ExactMatch(answerField = "answer")

    val optimizer = new BootstrapFewShotWithRandomSearch[DemoAwarePredictProgram](
      RandomSearchConfig(
        metric = metric,
        numCandidates = SearchCandidateCount(3),
        maxBootstrappedDemos = DemoCount(2),
        maxLabeledDemos = DemoCount(2),
        maxErrors = ErrorLimit(20)
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
      Example(rec("question" := s"q$i", "answer" := s"a$i"), inputKeys = Set("question"))
    ).toVector

    val metric = new ExactMatch(answerField = "answer")

    val optimizer = new BootstrapFewShotWithRandomSearch[DemoAwarePredictProgram](
      RandomSearchConfig(
        metric = metric,
        numCandidates = SearchCandidateCount(20),
        maxBootstrappedDemos = DemoCount(4),
        maxLabeledDemos = DemoCount(4),
        maxErrors = ErrorLimit(50),
        stopAtScore = Some(100.0)
      )
    )
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(zeroShotStudent, trainset, teacher = Some(oracleTeacher))
    assert(result.isRight)
    val report       = result.toOption.get
    val stoppedEarly = report.metadata.get("stopped_early").collect { case b: Boolean => b }.getOrElse(false)
    assert(stoppedEarly, s"should have stopped early but metadata was: ${report.metadata}")
  }

  // PORT_GAPS G-46 (upstream c7ee4462a): `Evaluate` rejects an empty devset. With no valset, the trainset is the
  // evaluation set, so an empty trainset gives no candidate a score. Python raises `ValueError` at the first candidate.
  test("BootstrapFewShotWithRandomSearch rejects an empty trainset when there is no valset") {
    val metric    = new ExactMatch(answerField = "answer")
    val optimizer = new BootstrapFewShotWithRandomSearch[DemoAwarePredictProgram](
      RandomSearchConfig(metric = metric, numCandidates = SearchCandidateCount(2))
    )
    given RuntimeContext = RuntimeEnvironment.current

    optimizer.compile(zeroShotStudent, Vector.empty) match
      case Left(error: ValidationError) => assert(error.message.contains("empty"), error.message)
      case other                        => fail(s"expected a ValidationError, got $other")
  }

  // Upstream `self.valset = valset or trainset` (`dspy/teleprompt/random_search.py:59`): an empty valset selects the
  // trainset as the evaluation set.
  test("BootstrapFewShotWithRandomSearch scores the candidates on the trainset when the valset is empty") {
    val trainset = (1 to 3).map(i =>
      Example(rec("question" := s"q$i", "answer" := s"a$i"), inputKeys = Set("question"))
    ).toVector
    val optimizer = new BootstrapFewShotWithRandomSearch[DemoAwarePredictProgram](
      RandomSearchConfig(metric = new ExactMatch(answerField = "answer"), numCandidates = SearchCandidateCount(1))
    )
    given RuntimeContext = RuntimeEnvironment.current

    optimizer.compile(zeroShotStudent, trainset, teacher = Some(oracleTeacher), valset = Some(Vector.empty)) match
      case Right(report) =>
        assert(report.candidates.nonEmpty, s"no candidate: ${report.metadata}")
        report.candidates.foreach { candidate =>
          candidate.metadata.get("per_example") match
            case Some(scores: Vector[?]) => assertEquals(scores.size, trainset.size)
            case other                   => fail(s"no per-example scores: $other")
        }
      case Left(error) => fail(s"expected a report, got $error")
  }
