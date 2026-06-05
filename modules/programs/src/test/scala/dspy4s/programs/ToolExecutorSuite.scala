package dspy4s.programs

import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, RuntimeError, TypeRef, :=}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.{ToolCallRequest, ToolFunction, description}
import dspy4s.programs.runtime.ToolExecutor
import zio.blocks.schema.{DynamicValue, PrimitiveValue}
import munit.FunSuite

object ToolExecutorSuite:
  /** A plain typed method, the dspy4s analogue of Python's `def get_weather(city: str) -> str`. */
  @description("Get the current weather for a city.")
  def getWeather(city: String): String = s"The weather in $city is sunny and 75°F"

  /** Two typed params, one numeric — exercises arg-schema reporting and LM-shaped coercion ("3" → Int). */
  @description("Repeat a phrase N times.")
  def repeat(phrase: String, times: Int): String = Vector.fill(times)(phrase).mkString(" ")

class ToolExecutorSuite extends FunSuite:
  import ToolExecutorSuite.{getWeather, repeat}

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  /** The point of typing tool args/results as `DynamicValue` rather than `Map[String, Any]`/`Any`:
    * a typed primitive arrives at the tool with its type intact (an Int stays an Int, not collapsed to
    * a String through an Any round-trip), and a structured result flows back through `ToolCallResult`
    * verbatim — no `fromAny` re-guessing. */
  test("tool args arrive as a typed Record and a structured result returns verbatim") {
    val doubler = new ToolFunction:
      override val name: String = "doubler"
      override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
        DynamicValues.recordGet(args, "n") match
          case Some(DynamicValue.Primitive(PrimitiveValue.Int(n))) =>
            Right(DynamicValues.recordFromEntries(Seq("doubled" := n * 2, "label" := "even")))
          case other =>
            Left(RuntimeError("doubler", s"expected an Int 'n', got: $other"))

    RuntimeEnvironment.withSettings(RuntimeContext()) {
      given RuntimeContext = RuntimeEnvironment.current
      val request = ToolCallRequest("doubler", DynamicValues.recordFromEntries(Seq("n" := 21)))
      val result  = ToolExecutor.invoke(request, Vector(doubler))

      assertEquals(
        result.map(_.result),
        Right(Right(DynamicValues.recordFromEntries(Seq("doubled" := 42, "label" := "even"))))
      )
    }
  }

  test("ToolFunction.of / apply build a tool from a function (no anonymous class)") {
    val weather = ToolFunction.of("get_weather", "Get the current weather for a city.") { args =>
      s"The weather in ${DynamicValues.recordGet(args, "city").map(DynamicValues.renderText).getOrElse("?")} is sunny"
    }
    val failing = ToolFunction("always_fails", "demo") { _ => Left(RuntimeError("always_fails", "nope")) }

    assertEquals(weather.name, "get_weather")
    assertEquals(weather.description, "Get the current weather for a city.")
    assertEquals(failing.name, "always_fails")

    RuntimeEnvironment.withSettings(RuntimeContext()) {
      given RuntimeContext = RuntimeEnvironment.current
      assertEquals(
        weather.invoke(DynamicValues.recordFromEntries(Seq("city" := "Tokyo"))),
        Right(ToolFunction.result("The weather in Tokyo is sunny"))
      )
      assert(failing.invoke(DynamicValue.Record.empty).isLeft)
    }
  }

  test("ToolFunction.fromMethod derives name, description, argSchema, and decodes args from the call record") {
    val weather = ToolFunction.fromMethod(getWeather)
    val rep     = ToolFunction.fromMethod(repeat)

    assertEquals(weather.name, "getWeather")
    assertEquals(weather.description, "Get the current weather for a city.")
    assertEquals(weather.argSchema, Vector("city" -> TypeRef.string))

    assertEquals(rep.name, "repeat")
    assertEquals(rep.argSchema, Vector("phrase" -> TypeRef.string, "times" -> TypeRef.int))

    RuntimeEnvironment.withSettings(RuntimeContext()) {
      given RuntimeContext = RuntimeEnvironment.current
      assertEquals(
        weather.invoke(DynamicValues.recordFromEntries(Seq("city" := "Tokyo"))),
        Right(ToolFunction.result("The weather in Tokyo is sunny and 75°F"))
      )
      // The numeric arg arrives LM-shaped as the string "3" and is coerced to Int before applying the method.
      assertEquals(
        rep.invoke(DynamicValues.recordFromEntries(Seq("phrase" := "hi", "times" := "3"))),
        Right(ToolFunction.result("hi hi hi"))
      )
      // A missing required argument surfaces as a Left rather than throwing.
      assert(weather.invoke(DynamicValue.Record.empty).isLeft)
    }
  }

  test("a tool's structured failure surfaces as a Left inside the ToolCallResult") {
    val picky = new ToolFunction:
      override val name: String = "picky"
      override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
        Left(RuntimeError("picky", "no"))

    RuntimeEnvironment.withSettings(RuntimeContext()) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = ToolExecutor.invoke(ToolCallRequest("picky", DynamicValue.Record.empty), Vector(picky))
      result match
        case Right(callResult) =>
          assert(callResult.result.isLeft, s"expected the tool failure to be carried as Left, got: ${callResult.result}")
        case Left(err) => fail(s"dispatch itself should succeed; got: $err")
    }
  }
