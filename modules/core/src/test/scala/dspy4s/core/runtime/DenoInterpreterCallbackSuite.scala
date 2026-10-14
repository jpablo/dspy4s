package dspy4s.core.runtime

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.InterpreterEndEvent
import dspy4s.core.contracts.InterpreterOperation
import dspy4s.core.contracts.InterpreterStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SandboxTool
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

/** The interpreter events of the Deno interpreter, in the upstream order (upstream
  * `test_real_interpreter_callbacks_preserve_lazy_startup_tool_nesting_and_final_output` in
  * `tests/callback/test_interpreter_callback.py`). Live-gated: the suite skips when `deno` is not on PATH.
  */
class DenoInterpreterCallbackSuite extends FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(180, "s")

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private lazy val denoAvailable: Boolean =
    try new ProcessBuilder("deno", "--version").start().waitFor() == 0
    catch case NonFatal(_) => false

  private final class Recorder extends CallbackHandler:
    val events: ArrayBuffer[CallbackEvent]                                 = ArrayBuffer.empty
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = events += event

  private val echo = SandboxTool(
    name = "echo",
    parameters = Vector(SandboxTool.Param("value", Some("str"))),
    invoke = kwargs => Right(DynamicValues.recordGet(kwargs, "value").getOrElse(DynamicValue.Null))
  )

  private def label(event: CallbackEvent): String =
    event match
      case start: InterpreterStartEvent => s"${start.operation}-start"
      case end: InterpreterEndEvent     => s"${end.operation}-end"
      case other                        => other.getClass.getSimpleName

  test("execute, lazy startup, tool call, and shutdown events come in the upstream order and nest under execute") {
    assume(denoAvailable, "deno not installed")
    val recorder = Recorder()
    RuntimeEnvironment.withCallbacks(Vector(recorder)) {
      val interpreter = new DenoPyodideInterpreter(tools = Vector(echo))
      try
        assert(interpreter.execute(
          "print(echo(value=payload))",
          Map("payload" -> DynamicValues.fromAny("first"))
        ).isRight)
        assert(interpreter.execute("print('second')").isRight)
      finally
        interpreter.close()
        interpreter.close()
    }
    val events = recorder.events.toVector.filter {
      case _: InterpreterStartEvent | _: InterpreterEndEvent => true
      case _                                                 => false
    }
    assertEquals(
      events.map(label),
      Vector(
        "Execute-start",
        "Startup-start",
        "Startup-end",
        "ToolCall-start",
        "ToolCall-end",
        "Execute-end",
        "Execute-start",
        "Execute-end",
        "Shutdown-start",
        "Shutdown-end",
        "Shutdown-start",
        "Shutdown-end"
      )
    )
    val starts = events.collect { case start: InterpreterStartEvent => start }
    assertEquals(starts(1).parentCallId, Some(starts(0).callId))
    assertEquals(starts(2).parentCallId, Some(starts(0).callId))
    assertEquals(starts.map(_.interpreterName).distinct, Vector("DenoPyodideInterpreter"))
    assertEquals(
      DynamicValues.recordGet(starts(0).inputs, "code").map(DynamicValues.renderText),
      Some("print(echo(value=payload))")
    )
    assertEquals(DynamicValues.recordGet(starts(2).inputs, "tool").map(DynamicValues.renderText), Some("echo"))
    val toolEnd =
      events.collect { case end: InterpreterEndEvent if end.operation == InterpreterOperation.ToolCall => end }
    assertEquals(toolEnd.map(_.output), Vector(Right(DynamicValues.fromAny("first"))))
  }
