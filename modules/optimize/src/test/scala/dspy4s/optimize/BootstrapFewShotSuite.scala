package dspy4s.optimize

import dspy4s.core.contracts.ExampleData
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import munit.FunSuite

class BootstrapFewShotSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  private val signature = SignatureDsl.parse("question: str -> answer: str").toOption.get

  test("BootstrapFewShot returns original student when trainset is empty") {
    val student = ScriptedPredictProgram(Map.empty, signature)
    val optimizer = new BootstrapFewShot[ScriptedPredictProgram]()
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(student, Vector.empty)
    assert(result.isRight)
    assertEquals(result.toOption.get.bestProgram.demos.size, 0)
  }

  test("BootstrapFewShot bootstraps demos from examples where teacher succeeds") {
    val teacher = ScriptedPredictProgram(Map("q1" -> "a1", "q2" -> "a2", "q3" -> "a3"), signature)
    val trainset = Vector(
      ExampleData(Map("question" -> "q1", "answer" -> "a1"), inputKeys = Set("question")),
      ExampleData(Map("question" -> "q2", "answer" -> "a2"), inputKeys = Set("question")),
      ExampleData(Map("question" -> "q3", "answer" -> "a3"), inputKeys = Set("question"))
    )
    val student = ScriptedPredictProgram(Map.empty, signature)
    val optimizer = new BootstrapFewShot[ScriptedPredictProgram](
      BootstrapFewShotConfig(maxBootstrappedDemos = 2, maxLabeledDemos = 1, maxRounds = 1)
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
    val teacher = ScriptedPredictProgram(Map("q1" -> "wrong", "q2" -> "expected", "q3" -> "also-wrong"), signature)
    val trainset = Vector(
      ExampleData(Map("question" -> "q1", "answer" -> "expected"), inputKeys = Set("question")),
      ExampleData(Map("question" -> "q2", "answer" -> "expected"), inputKeys = Set("question")),
      ExampleData(Map("question" -> "q3", "answer" -> "expected"), inputKeys = Set("question"))
    )

    val exactMatch = new dspy4s.evaluate.metrics.ExactMatch(answerField = "answer")

    val student = ScriptedPredictProgram(Map.empty, signature)
    val optimizer = new BootstrapFewShot[ScriptedPredictProgram](
      BootstrapFewShotConfig(
        metric = Some(exactMatch),
        maxBootstrappedDemos = 10,
        maxLabeledDemos = 0
      )
    )
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(student, trainset, teacher = Some(teacher))
    assert(result.isRight)
    val bootstrapped = result.toOption.get.bestProgram.demos.filter(_.augmented)
    assertEquals(bootstrapped.size, 1, "only q2 should pass the exact match metric")
    assertEquals(bootstrapped.head.values("question"), "q2")
  }

  test("BootstrapFewShot returns RuntimeError when teacher throws and errors exceed maxErrors") {
    val trainset = (1 to 5).map(i =>
      ExampleData(Map("question" -> s"q$i", "answer" -> s"a$i"), inputKeys = Set("question"))
    ).toVector

    val blowingUp = ScriptedPredictProgram(
      answers = Map.empty,
      signature = signature,
      failsWith = Some(new RuntimeException("boom"))
    )
    val optimizer = new BootstrapFewShot[ScriptedPredictProgram](
      BootstrapFewShotConfig(maxErrors = 2, maxBootstrappedDemos = 10)
    )
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(blowingUp, trainset)
    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[RuntimeError])
  }

  test("BootstrapFewShot fills remaining slots from labeled examples that failed bootstrap") {
    // Teacher knows only q1; other questions get "unknown" answers (mismatch),
    // so other examples go into the failed pool and fill labeled slots.
    val teacher = ScriptedPredictProgram(Map("q1" -> "a1"), signature)
    val trainset = (1 to 5).map(i =>
      ExampleData(Map("question" -> s"q$i", "answer" -> s"a$i"), inputKeys = Set("question"))
    ).toVector

    val student = ScriptedPredictProgram(Map.empty, signature)
    val optimizer = new BootstrapFewShot[ScriptedPredictProgram](
      BootstrapFewShotConfig(maxBootstrappedDemos = 1, maxLabeledDemos = 2, maxErrors = 100, seed = 7L)
    )
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(student, trainset, teacher = Some(teacher))
    assert(result.isRight)
    val demos = result.toOption.get.bestProgram.demos
    assert(demos.size <= 3, s"got ${demos.size} demos, expected at most 3")
    val augmentedCount = demos.count(_.augmented)
    val nonAugmentedCount = demos.size - augmentedCount
    assertEquals(augmentedCount, 1)
    assert(nonAugmentedCount <= 2, s"got $nonAugmentedCount labeled demos, expected <= 2")
  }
