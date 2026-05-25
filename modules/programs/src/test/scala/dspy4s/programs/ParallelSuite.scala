package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SettingKey
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.Settings
import dspy4s.core.contracts.ValidationError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite

class ParallelSuite extends FunSuite:
  private val sampleKey = SettingKey[String]("sample")

  private final case class StubProgram(
      override val moduleName: String = "stub",
      behavior: Int => Either[DspyError, DynamicPrediction]
  ) extends PredictProgram:
    override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      val value = input.inputs("value").asInstanceOf[Int]
      behavior(value)

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("parallel executes programs and preserves task ordering") {
    given RuntimeContext = RuntimeEnvironment.current
    val program = StubProgram(behavior = value => Right(DynamicPrediction(values = Map("output" -> value * 2))))
    val tasks = (1 to 5).toVector.map { value =>
      program -> ProgramCall(inputs = Map("value" -> value))
    }

    val result = Parallel(numThreads = Some(2), maxErrors = Some(2)).run(tasks)

    assert(result.isRight)
    val outputs = result.toOption.get.results.map(_.get.values("output"))
    assertEquals(outputs, Vector(2, 4, 6, 8, 10))
  }

  test("parallel keeps partial results when failures are below threshold") {
    given RuntimeContext = RuntimeEnvironment.current
    val program = StubProgram(
      behavior = value =>
        if value == 3 then Left(ValidationError("boom"))
        else Right(DynamicPrediction(values = Map("output" -> value)))
    )
    val tasks = (1 to 5).toVector.map { value =>
      program -> ProgramCall(inputs = Map("value" -> value))
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
        else Right(DynamicPrediction(values = Map("output" -> value)))
    )
    val tasks = (1 to 5).toVector.map { value =>
      program -> ProgramCall(inputs = Map("value" -> value))
    }

    val result = Parallel(numThreads = Some(2), maxErrors = Some(1)).run(tasks)

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[RuntimeError])
  }

  test("parallel inherits numThreads and maxErrors from settings when unspecified") {
    val program = StubProgram(
      behavior = value =>
        if value == 2 then Left(ValidationError("boom"))
        else Right(DynamicPrediction(values = Map("output" -> value)))
    )
    val tasks = Vector(1, 2, 3).map(value => program -> ProgramCall(inputs = Map("value" -> value)))

    RuntimeEnvironment.withSettings(
      Settings(
        Map(
          SettingKeys.numThreads.name -> 2,
          SettingKeys.maxErrors.name -> 1
        )
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
            values = Map("sample" -> RuntimeEnvironment.currentSettings.get(sampleKey).getOrElse("missing"))
          )
        )
    )
    val tasks = Vector.fill(4)(program -> ProgramCall(inputs = Map("value" -> 1)))

    RuntimeEnvironment.withSettings(Settings(Map(sampleKey.name -> "scoped"))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = Parallel(numThreads = Some(2), maxErrors = Some(2)).run(tasks)
      assert(result.isRight)
      val values = result.toOption.get.results.flatten.map(_.values("sample"))
      assertEquals(values, Vector("scoped", "scoped", "scoped", "scoped"))
    }
  }

  test("parallel preserves mixed answer and tool_calls outputs") {
    given RuntimeContext = RuntimeEnvironment.current
    val program = StubProgram(
      behavior = value =>
        Right(
          DynamicPrediction(
            values = Map(
              "answer" -> s"answer-$value",
              "tool_calls" -> Vector(
                Map("name" -> "search", "args" -> Map("query" -> s"q-$value"))
              )
            )
          )
        )
    )
    val tasks = Vector(1, 2).map(value => program -> ProgramCall(inputs = Map("value" -> value)))

    val result = Parallel(numThreads = Some(2), maxErrors = Some(2)).run(tasks)

    assert(result.isRight)
    val outputs = result.toOption.get.results.flatten
    assertEquals(outputs.map(_.values("answer")), Vector("answer-1", "answer-2"))
    val toolCalls = outputs.map(_.values("tool_calls").asInstanceOf[Vector[Map[String, Any]]])
    assertEquals(toolCalls.map(_.head("name")), Vector("search", "search"))
    assertEquals(toolCalls.map(_.head("args")), Vector(Map("query" -> "q-1"), Map("query" -> "q-2")))
  }
