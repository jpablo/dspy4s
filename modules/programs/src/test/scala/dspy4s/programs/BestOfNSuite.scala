package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

class BestOfNSuite extends FunSuite:
  private final class StubProgram(
      results: Vector[Either[DspyError, DynamicPrediction]]
  ) extends PredictProgram:
    private val counter = AtomicInteger(0)
    val rolloutIds: ArrayBuffer[Int] = ArrayBuffer.empty
    val calls: AtomicInteger = AtomicInteger(0)
    override val moduleName: String = "stub"

    override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      calls.incrementAndGet()
      val rollout = input.config.get("rollout_id").collect { case v: Int => v }.getOrElse(-1)
      rolloutIds += rollout

      val idx = counter.getAndIncrement()
      val result = results(Math.min(idx, results.size - 1))
      result.foreach { prediction =>
        RuntimeEnvironment.appendTrace(
          TraceEntry(component = moduleName, inputs = input.inputs, outputs = prediction.values)
        )
      }
      result

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("best of n returns highest reward candidate and rolls out n attempts") {
    val module = StubProgram(
      Vector(
        Right(DynamicPrediction(values = Map("answer" -> "A", "score" -> 0.1))),
        Right(DynamicPrediction(values = Map("answer" -> "B", "score" -> 0.9))),
        Right(DynamicPrediction(values = Map("answer" -> "C", "score" -> 0.5)))
      )
    )
    val bestOfN = BestOfN(
      module = module,
      n = 3,
      rewardFn = (_, pred) => pred.asDouble("score").toOption.getOrElse(0.0),
      threshold = 1.0
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = bestOfN.run(ProgramCall(inputs = Map("q" -> "x"), config = Map("rollout_id" -> 7)))

    assert(result.isRight)
    assertEquals(result.toOption.get.values("answer"), "B")
    assertEquals(module.calls.get(), 3)
    assertEquals(module.rolloutIds.toVector, Vector(7, 8, 9))
    assertEquals(RuntimeEnvironment.current.trace.size, 1)
    assertEquals(RuntimeEnvironment.current.trace.head.outputs("answer"), "B")
  }

  test("best of n default fail count raises after repeated failures") {
    val module = StubProgram(
      Vector(
        Left(RuntimeError("stub", "f1")),
        Left(RuntimeError("stub", "f2")),
        Left(RuntimeError("stub", "f3"))
      )
    )
    val bestOfN = BestOfN(
      module = module,
      n = 3,
      rewardFn = (_, _) => 1.0,
      threshold = 0.0
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = bestOfN.run(ProgramCall(inputs = Map("q" -> "x")))
    assert(result.isLeft)
    assertEquals(module.calls.get(), 3)
    assertEquals(result.left.toOption.get.message, "f3")
  }

  test("best of n custom fail count raises earlier") {
    val module = StubProgram(
      Vector(
        Left(RuntimeError("stub", "f1")),
        Left(RuntimeError("stub", "f2")),
        Right(DynamicPrediction(values = Map("answer" -> "ok", "score" -> 1.0)))
      )
    )
    val bestOfN = BestOfN(
      module = module,
      n = 3,
      rewardFn = (_, _) => 1.0,
      threshold = 0.0,
      failCount = Some(1)
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = bestOfN.run(ProgramCall(inputs = Map("q" -> "x")))
    assert(result.isLeft)
    assertEquals(module.calls.get(), 2)
    assertEquals(result.left.toOption.get.message, "f2")
  }

  test("best of n preserves mixed answer and tool_calls for best candidate") {
    val module = StubProgram(
      Vector(
        Right(
          DynamicPrediction(
            values = Map(
              "answer" -> "A",
              "score" -> 0.2,
              "tool_calls" -> Vector(Map("name" -> "search", "args" -> Map("query" -> "a")))
            )
          )
        ),
        Right(
          DynamicPrediction(
            values = Map(
              "answer" -> "B",
              "score" -> 0.9,
              "tool_calls" -> Vector(Map("name" -> "lookup", "args" -> Map("entity" -> "b")))
            )
          )
        )
      )
    )
    val bestOfN = BestOfN(
      module = module,
      n = 2,
      rewardFn = (_, pred) => pred.asDouble("score").toOption.getOrElse(0.0),
      threshold = 1.0
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = bestOfN.run(ProgramCall(inputs = Map("q" -> "x")))

    assert(result.isRight)
    val prediction = result.toOption.get
    assertEquals(prediction.values("answer"), "B")
    val toolCalls = prediction.values("tool_calls").asInstanceOf[Vector[Map[String, Any]]]
    assertEquals(toolCalls.head("name"), "lookup")
    assertEquals(RuntimeEnvironment.current.trace.head.outputs("tool_calls"), toolCalls)
  }
