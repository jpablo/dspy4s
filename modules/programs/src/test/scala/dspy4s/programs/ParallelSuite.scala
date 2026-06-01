package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite

class ParallelSuite extends FunSuite:

  private final case class StubProgram(
      override val moduleName: String = "stub",
      behavior: Int => Either[DspyError, DynamicPrediction]
  ) extends PredictProgram:
    override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      val value = lookup(input.inputs, "value").get.asInstanceOf[Int]
      behavior(value)

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("parallel executes programs and preserves task ordering") {
    given RuntimeContext = RuntimeEnvironment.current
    val program = StubProgram(behavior = value => Right(DynamicPrediction(values = rec("output" := value * 2))))
    val tasks = (1 to 5).toVector.map { value =>
      program -> ProgramCall(inputs = rec("value" := value))
    }

    val result = Parallel(numThreads = Some(2), maxErrors = Some(2)).run(tasks)

    assert(result.isRight)
    val outputs = result.toOption.get.results.map(p => lookup(p.get.values, "output").get)
    assertEquals(outputs, Vector(2, 4, 6, 8, 10): Vector[Any])
  }

  test("parallel keeps partial results when failures are below threshold") {
    given RuntimeContext = RuntimeEnvironment.current
    val program = StubProgram(
      behavior = value =>
        if value == 3 then Left(ValidationError("boom"))
        else Right(DynamicPrediction(values = rec("output" := value)))
    )
    val tasks = (1 to 5).toVector.map { value =>
      program -> ProgramCall(inputs = rec("value" := value))
    }

    val result = Parallel(numThreads = Some(2), maxErrors = Some(2)).run(tasks)

    assert(result.isRight)
    val output = result.toOption.get
    assertEquals(output.failedIndices, Vector(2))
    assert(output.results(2).isEmpty)
    assert(output.errors.get(2).exists(_.isInstanceOf[ValidationError]))
  }

  test("parallel returns cancellation error when threshold is met") {
    given RuntimeContext = RuntimeEnvironment.current
    val program = StubProgram(
      behavior = value =>
        if value % 2 == 0 then Left(ValidationError("fail"))
        else Right(DynamicPrediction(values = rec("output" := value)))
    )
    val tasks = (1 to 5).toVector.map { value =>
      program -> ProgramCall(inputs = rec("value" := value))
    }

    val result = Parallel(numThreads = Some(2), maxErrors = Some(1)).run(tasks)

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[RuntimeError])
  }

  test("parallel inherits numThreads and maxErrors from settings when unspecified") {
    val program = StubProgram(
      behavior = value =>
        if value == 2 then Left(ValidationError("boom"))
        else Right(DynamicPrediction(values = rec("output" := value)))
    )
    val tasks = Vector(1, 2, 3).map(value => program -> ProgramCall(inputs = rec("value" := value)))

    RuntimeEnvironment.withSettings(
      RuntimeContext(
          numThreads = Some(2),
          maxErrors = Some(1)
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = Parallel().run(tasks)
      assert(result.isLeft)
      assert(result.left.toOption.get.isInstanceOf[RuntimeError])
    }
  }

  test("parallel workers preserve runtime context") {
    val program = StubProgram(
      behavior = _ =>
        Right(
          DynamicPrediction(
            values = rec("sample" := RuntimeEnvironment.current.numThreads.map(_.toString).getOrElse("missing"))
          )
        )
    )
    val tasks = Vector.fill(4)(program -> ProgramCall(inputs = rec("value" := 1)))

    RuntimeEnvironment.withSettings(RuntimeContext(numThreads = Some(42))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = Parallel(numThreads = Some(2), maxErrors = Some(2)).run(tasks)
      assert(result.isRight)
      val values = result.toOption.get.results.flatten.map(p => lookupString(p.values, "sample"))
      assertEquals(values, Vector("42", "42", "42", "42"))
    }
  }

  test("parallel preserves mixed answer and tool_calls outputs") {
    given RuntimeContext = RuntimeEnvironment.current
    val program = StubProgram(
      behavior = value =>
        Right(
          DynamicPrediction(
            values = rec(
              "answer" := s"answer-$value",
              "tool_calls" -> DynamicValues.fromAny(Vector(
                Map("name" -> "search", "args" -> Map("query" -> s"q-$value"))
              ))
            )
          )
        )
    )
    val tasks = Vector(1, 2).map(value => program -> ProgramCall(inputs = rec("value" := value)))

    val result = Parallel(numThreads = Some(2), maxErrors = Some(2)).run(tasks)

    assert(result.isRight)
    val outputs = result.toOption.get.results.flatten
    assertEquals(outputs.map(p => lookupString(p.values, "answer")), Vector("answer-1", "answer-2"))
    val toolCalls = outputs.map(p => lookup(p.values, "tool_calls").get.asInstanceOf[List[Map[String, Any]]])
    assertEquals(toolCalls.map(_.head("name")), Vector("search", "search"): Vector[Any])
    assertEquals(
      toolCalls.map(_.head("args")),
      Vector(Map("query" -> "q-1"), Map("query" -> "q-2")): Vector[Any]
    )
  }
