package dspy4s.optimize

import dspy4s.core.contracts.ExampleData
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.programs.DynamicPredict
import munit.FunSuite

class LabeledFewShotSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  private val signature = SignatureDsl.parse("question: str -> answer: str").toOption.get

  test("LabeledFewShot samples k demos from trainset with seed-based determinism") {
    val trainset = (1 to 20).map(i => ExampleData(Map("question" -> s"q$i", "answer" -> s"a$i"))).toVector
    val student = DynamicPredict(layout = signature)
    val optimizer = LabeledFewShot[DynamicPredict](LabeledFewShotConfig(k = 5, seed = 42L))
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(student, trainset)
    assert(result.isRight, s"compile failed: ${result.left.toOption}")
    val compiled = result.toOption.get.bestProgram
    assertEquals(compiled.demos.size, 5)

    val firstRunDemos = compiled.demos.map(_.values("question"))
    val rerun = optimizer.compile(student, trainset).toOption.get.bestProgram.demos.map(_.values("question"))
    assertEquals(firstRunDemos, rerun, "sampling should be deterministic under the same seed")
  }

  test("LabeledFewShot returns no demos when trainset is empty") {
    val student = DynamicPredict(layout = signature)
    val optimizer = LabeledFewShot[DynamicPredict]()
    given RuntimeContext = RuntimeEnvironment.current

    val result = optimizer.compile(student, Vector.empty)
    assert(result.isRight)
    assertEquals(result.toOption.get.bestProgram.demos.size, 0)
  }

  test("LabeledFewShot with sample=false takes the first k examples in input order") {
    val trainset = (1 to 10).map(i => ExampleData(Map("question" -> s"q$i", "answer" -> s"a$i"))).toVector
    val student = DynamicPredict(layout = signature)
    val optimizer = LabeledFewShot[DynamicPredict](LabeledFewShotConfig(k = 3, sample = false))
    given RuntimeContext = RuntimeEnvironment.current

    val compiled = optimizer.compile(student, trainset).toOption.get.bestProgram
    assertEquals(compiled.demos.size, 3)
    assertEquals(compiled.demos.map(_.values("question")).toList, List("q1", "q2", "q3"))
  }

  test("LabeledFewShot caps demo count at trainset size when k exceeds it") {
    val trainset = Vector(ExampleData(Map("question" -> "only", "answer" -> "one")))
    val student = DynamicPredict(layout = signature)
    val optimizer = LabeledFewShot[DynamicPredict](LabeledFewShotConfig(k = 10))
    given RuntimeContext = RuntimeEnvironment.current

    val compiled = optimizer.compile(student, trainset).toOption.get.bestProgram
    assertEquals(compiled.demos.size, 1)
  }

  test("LabeledFewShot preserves student signature") {
    val student = DynamicPredict(layout = signature)
    val trainset = Vector(
      ExampleData(Map("question" -> "q1", "answer" -> "a1")),
      ExampleData(Map("question" -> "q2", "answer" -> "a2"))
    )
    val optimizer = LabeledFewShot[DynamicPredict](LabeledFewShotConfig(k = 1, sample = false))
    given RuntimeContext = RuntimeEnvironment.current

    val compiled = optimizer.compile(student, trainset).toOption.get.bestProgram
    assert(compiled.layout.equalsByStructure(signature), "signature should be preserved")
    assertEquals(compiled.demos.size, 1)
    assertEquals(compiled.demos.head.values("question"), "q1")
  }
