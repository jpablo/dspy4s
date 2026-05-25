package dspy4s.programs

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.NotFoundError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.ToolEndEvent
import dspy4s.core.contracts.ToolStartEvent
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolFunction
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

class ReActSuite extends FunSuite:
  private final class ScriptedProgram extends PredictProgram:
    private val calls = AtomicInteger(0)
    override val moduleName: String = "scripted"

    override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      val idx = calls.incrementAndGet()
      if idx == 1 then
        Right(
          DynamicPrediction(
            values = Map(
              "tool_name" -> "search",
              "tool_args" -> Map("query" -> input.inputs.getOrElse("question", ""))
            )
          )
        )
      else
        Right(
          DynamicPrediction(
            values = Map(
              "answer" -> s"Final: ${input.inputs.getOrElse("tool_result", "")}"
            )
          )
        )

  private final class LoopingProgram extends PredictProgram:
    override val moduleName: String = "loop"
    override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      Right(DynamicPrediction(values = Map("tool_name" -> "search", "tool_args" -> Map("query" -> "q"))))

  private final class NativeToolCallsProgram extends PredictProgram:
    private val calls = AtomicInteger(0)
    override val moduleName: String = "native-tool-calls"

    override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      val idx = calls.incrementAndGet()
      if idx == 1 then
        Right(
          DynamicPrediction(
            values = Map(
              "tool_calls" -> Vector(
                Map("name" -> "search", "args" -> Map("query" -> input.inputs.getOrElse("question", "")))
              )
            )
          )
        )
      else
        Right(
          DynamicPrediction(
            values = Map(
              "answer" -> s"Final: ${input.inputs.getOrElse("tool_result", "")}"
            )
          )
        )

  private final class MultiToolCallsProgram extends PredictProgram:
    private val calls = AtomicInteger(0)
    override val moduleName: String = "multi-tool-calls"

    override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      val idx = calls.incrementAndGet()
      if idx == 1 then
        Right(
          DynamicPrediction(
            values = Map(
              "tool_calls" -> Vector(
                Map("name" -> "search", "args" -> Map("query" -> input.inputs.getOrElse("question", ""))),
                Map("name" -> "lookup", "args" -> Map("entity" -> "belgium"))
              )
            )
          )
        )
      else
        val batch = input.inputs.get("tool_results").collect {
          case entries: Vector[?] => entries
          case entries: Seq[?]    => entries.toVector
        }.getOrElse(Vector.empty)
        Right(
          DynamicPrediction(
            values = Map(
              "answer" -> s"Tools executed: ${batch.size}"
            )
          )
        )

  private final class AnswerAndToolCallsProgram extends PredictProgram:
    override val moduleName: String = "answer-and-tool-calls"

    override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      Right(
        DynamicPrediction(
          values = Map(
            "answer" -> "Final without tools",
            "tool_calls" -> Vector(
              Map("name" -> "search", "args" -> Map("query" -> "ignored"))
            )
          )
        )
      )

  private final class SearchTool(counter: AtomicInteger) extends ToolFunction:
    override val name: String = "search"
    override def invoke(args: Map[String, Any])(using RuntimeContext): Either[DspyError, Any] =
      counter.incrementAndGet()
      Right("Brussels")

  private final class LookupTool(counter: AtomicInteger) extends ToolFunction:
    override val name: String = "lookup"
    override def invoke(args: Map[String, Any])(using RuntimeContext): Either[DspyError, Any] =
      counter.incrementAndGet()
      Right("Europe")

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("react executes tool and returns final answer") {
    val counter = AtomicInteger(0)
    val react = ReAct(module = ScriptedProgram(), tools = Vector(SearchTool(counter)), maxIterations = 3)

    given RuntimeContext = RuntimeEnvironment.current
    val result = react.run(ProgramCall(inputs = Map("question" -> "What is the capital of Belgium?")))

    assert(result.isRight)
    assertEquals(result.toOption.get.values("answer"), "Final: Brussels")
    assertEquals(counter.get(), 1)
  }

  test("react fails when requested tool is missing") {
    val react = ReAct(module = ScriptedProgram(), tools = Vector.empty, maxIterations = 3)
    given RuntimeContext = RuntimeEnvironment.current
    val result = react.run(ProgramCall(inputs = Map("question" -> "x")))

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[NotFoundError])
  }

  test("react fails when max iterations are exhausted without answer") {
    val counter = AtomicInteger(0)
    val react = ReAct(module = LoopingProgram(), tools = Vector(SearchTool(counter)), maxIterations = 2)
    given RuntimeContext = RuntimeEnvironment.current
    val result = react.run(ProgramCall(inputs = Map("question" -> "x")))

    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[RuntimeError])
    assertEquals(counter.get(), 2)
  }

  test("react emits tool callback events with parent module call id") {
    val events = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        events += event

    val counter = AtomicInteger(0)
    val react = ReAct(module = ScriptedProgram(), tools = Vector(SearchTool(counter)), maxIterations = 3)

    RuntimeEnvironment.withCallbacks(Vector(callback)) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = react.run(ProgramCall(inputs = Map("question" -> "x")))
      assert(result.isRight)
    }

    val moduleStart = events.collectFirst { case e: dspy4s.core.contracts.ModuleStartEvent => e }.get
    val toolStart = events.collectFirst { case e: ToolStartEvent => e }.get
    val toolEnd = events.collectFirst { case e: ToolEndEvent => e }.get

    assertEquals(toolStart.parentCallId, Some(moduleStart.callId))
    assertEquals(toolEnd.parentCallId, Some(moduleStart.callId))
    assertEquals(toolStart.callId, toolEnd.callId)
  }

  test("react executes native tool_calls payloads") {
    val counter = AtomicInteger(0)
    val react = ReAct(module = NativeToolCallsProgram(), tools = Vector(SearchTool(counter)), maxIterations = 3)

    given RuntimeContext = RuntimeEnvironment.current
    val result = react.run(ProgramCall(inputs = Map("question" -> "What is the capital of Belgium?")))

    assert(result.isRight)
    assertEquals(result.toOption.get.values("answer"), "Final: Brussels")
    assertEquals(counter.get(), 1)
  }

  test("react executes multiple native tool calls in one iteration") {
    val searchCounter = AtomicInteger(0)
    val lookupCounter = AtomicInteger(0)
    val react = ReAct(
      module = MultiToolCallsProgram(),
      tools = Vector(SearchTool(searchCounter), LookupTool(lookupCounter)),
      maxIterations = 3
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = react.run(ProgramCall(inputs = Map("question" -> "What is the capital of Belgium?")))

    assert(result.isRight)
    assertEquals(result.toOption.get.values("answer"), "Tools executed: 2")
    assertEquals(searchCounter.get(), 1)
    assertEquals(lookupCounter.get(), 1)
  }

  test("react prioritizes direct answers over tool execution when both are present") {
    val counter = AtomicInteger(0)
    val react = ReAct(module = AnswerAndToolCallsProgram(), tools = Vector(SearchTool(counter)), maxIterations = 3)

    given RuntimeContext = RuntimeEnvironment.current
    val result = react.run(ProgramCall(inputs = Map("question" -> "x")))

    assert(result.isRight)
    assertEquals(result.toOption.get.values("answer"), "Final without tools")
    assertEquals(counter.get(), 0)
  }
