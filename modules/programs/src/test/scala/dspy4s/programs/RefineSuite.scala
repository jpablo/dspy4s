package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger

class RefineSuite extends FunSuite:
  private final class StubProgram(
      results: Vector[Either[DspyError, DynamicPrediction]]
  ) extends Module:
    private val counter = AtomicInteger(0)
    val calls: AtomicInteger = AtomicInteger(0)
    override val moduleName: String = "stub"

    override protected def forward(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      calls.incrementAndGet()
      val idx = counter.getAndIncrement()
      results(Math.min(idx, results.size - 1))

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("refine returns best prediction among attempts") {
    val module = StubProgram(
      Vector(
        Right(DynamicPrediction(values = rec("answer" := "Brussels", "score" := 0.4))),
        Right(DynamicPrediction(values = rec("answer" := "City of Brussels", "score" := 0.2))),
        Right(DynamicPrediction(values = rec("answer" := "Brussels", "score" := 0.9)))
      )
    )
    val refine = Refine(
      module = module,
      n = 3,
      rewardFn = (_, pred) => pred.asDouble("score").toOption.getOrElse(0.0),
      threshold = 1.0
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = refine.apply(ProgramCall(inputs = rec("question" := "What is the capital of Belgium?")))

    assert(result.isRight)
    assertEquals(lookupString(result.toOption.get.values, "answer"), "Brussels")
    assertEquals(module.calls.get(), 3)
  }

  test("refine default fail count raises after repeated failures") {
    val module = StubProgram(
      Vector(
        Left(RuntimeError("stub", "f1")),
        Left(RuntimeError("stub", "f2")),
        Left(RuntimeError("stub", "f3"))
      )
    )
    val refine = Refine(
      module = module,
      n = 3,
      rewardFn = (_, _) => 1.0,
      threshold = 0.0
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = refine.apply(ProgramCall(inputs = rec("q" := "x")))
    assert(result.isLeft)
    assertEquals(module.calls.get(), 3)
    assertEquals(result.left.toOption.get.message, "f3")
  }

  test("refine custom fail count raises earlier") {
    val module = StubProgram(
      Vector(
        Left(RuntimeError("stub", "f1")),
        Left(RuntimeError("stub", "f2")),
        Right(DynamicPrediction(values = rec("answer" := "ok", "score" := 1.0)))
      )
    )
    val refine = Refine(
      module = module,
      n = 3,
      rewardFn = (_, _) => 1.0,
      threshold = 0.0,
      failCount = Some(1)
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = refine.apply(ProgramCall(inputs = rec("q" := "x")))
    assert(result.isLeft)
    assertEquals(module.calls.get(), 2)
    assertEquals(result.left.toOption.get.message, "f2")
  }
