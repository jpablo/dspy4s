package dspy4s.programs

import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, RuntimeError, :=}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.{ToolCallRequest, ToolFunction}
import dspy4s.programs.runtime.ToolExecutor
import zio.blocks.schema.{DynamicValue, PrimitiveValue}
import munit.FunSuite

class ToolExecutorSuite extends FunSuite:

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
