package dspy4s.core.runtime

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SandboxTool
import dspy4s.core.contracts.ToolEndEvent
import dspy4s.core.contracts.ToolStartEvent
import dspy4s.core.contracts.:=
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

/** Live-gated suite for the Deno+Pyodide sandbox: skips (assume) when `deno` is not on PATH. The first-ever run
  * on a machine downloads Pyodide from npm into Deno's cache (network needed once); afterwards it's offline.
  * Interpreters are shared across tests where possible — sandbox startup costs seconds. */
class DenoPyodideInterpreterSuite extends FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(180, "s")

  private lazy val denoAvailable: Boolean =
    try new ProcessBuilder("deno", "--version").start().waitFor() == 0
    catch case NonFatal(_) => false

  /** Shared plain interpreter (no tools/outputs) for the basic-execution tests. */
  private lazy val plain = new DenoPyodideInterpreter()

  override def afterAll(): Unit =
    if denoAvailable then
      try plain.close()
      catch case NonFatal(_) => ()

  private def assumeDeno(): Unit = assume(denoAvailable, "deno not installed — skipping sandbox tests")

  test("executes code in the sandbox and captures stdout") {
    assumeDeno()
    val result = plain.execute("print(1 + 2)").toOption.get
    assertEquals(result.stdout.trim, "3")
    assertEquals(result.exitCode, 0)
    assertEquals(result.finalOutput, None)
  }

  test("REPL state persists across execute calls (one long-lived Pyodide instance)") {
    assumeDeno()
    assertEquals(plain.execute("state_x = 21").map(_.exitCode), Right(0))
    val result = plain.execute("print(state_x * 2)").toOption.get
    assertEquals(result.stdout.trim, "42")
  }

  test("a user-code error returns CodeResult(exitCode=1) with the Python error in stderr, and the sandbox survives") {
    assumeDeno()
    val failure = plain.execute("print(undefined_variable_xyz)").toOption.get
    assertEquals(failure.exitCode, 1)
    assert(failure.stderr.contains("NameError"), failure.stderr)

    // The interpreter is still usable after a user-code error.
    assertEquals(plain.execute("print('alive')").toOption.get.stdout.trim, "alive")
  }

  test("variable injection defines host values as Python variables (strings with quotes/newlines, numbers, lists, records)") {
    assumeDeno()
    val variables = Map[String, DynamicValue](
      "v_text" -> DynamicValues.fromAny("it's a \"test\"\nline two"),
      "v_num"  -> DynamicValues.fromAny(7),
      "v_list" -> DynamicValues.fromAny(List(1, 2, 3)),
      "v_rec"  -> DynamicValues.record("inner" := "ok")
    )
    val result = plain.execute("print(len(v_text), v_num * 6, sum(v_list), v_rec['inner'])", variables).toOption.get
    assertEquals(result.stdout.trim, s"${"it's a \"test\"\nline two".length} 42 6 ok")
  }

  test("an invalid variable name is rejected before reaching the sandbox") {
    assumeDeno()
    assert(plain.execute("print(1)", Map("not-an-identifier" -> DynamicValues.fromAny(1))).isLeft)
    assert(plain.execute("print(1)", Map("json" -> DynamicValues.fromAny(1))).isLeft)
  }

  test("host tools are callable from sandboxed code, and SUBMIT returns a structured finalOutput") {
    assumeDeno()
    val greet = SandboxTool(
      name = "greet",
      parameters = Vector(SandboxTool.Param("who", Some("str"))),
      invoke = kwargs =>
        Right(DynamicValues.fromAny(s"hello-${DynamicValues.recordGet(kwargs, "who").map(DynamicValues.renderText).getOrElse("?")}"))
    )
    val interp = new DenoPyodideInterpreter(
      tools = Vector(greet),
      outputFields = Vector(DenoPyodideInterpreter.OutputField("answer"))
    )
    try
      // Tool call: sandbox -> host -> back into the sandbox, mid-execution.
      val toolResult = interp.execute("print(greet(who='world'))").toOption.get
      assertEquals(toolResult.stdout.trim, "hello-world")

      // SUBMIT terminates with the structured final output (JSON of the submitted fields).
      val submitted = interp.execute("SUBMIT(answer=6 * 7)").toOption.get
      assertEquals(submitted.exitCode, 0)
      assert(submitted.finalOutput.isDefined, submitted.toString)
      assert(submitted.finalOutput.get.contains("42"), submitted.finalOutput.get)
      assert(submitted.finalOutput.get.contains("answer"), submitted.finalOutput.get)
    finally interp.close()
  }

  test("a sandbox-to-host tool call emits a ToolStartEvent/ToolEndEvent pair on the active callbacks") {
    assumeDeno()
    val events = ArrayBuffer.empty[CallbackEvent]
    val recorder = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = events += event
    val shout = SandboxTool(
      name = "shout",
      parameters = Vector(SandboxTool.Param("text", Some("str"))),
      invoke = kwargs =>
        Right(DynamicValues.fromAny(DynamicValues.recordGet(kwargs, "text").map(DynamicValues.renderText).getOrElse("?").toUpperCase))
    )
    val interp = new DenoPyodideInterpreter(tools = Vector(shout))
    try
      RuntimeEnvironment.withCallbacks(Vector(recorder)) {
        val result = interp.execute("print(shout(text='quiet'))").toOption.get
        assertEquals(result.stdout.trim, "QUIET")
      }
      val starts = events.collect { case e: ToolStartEvent => e }.toVector
      val ends   = events.collect { case e: ToolEndEvent => e }.toVector
      assertEquals(starts.map(_.toolName), Vector("shout"))
      assertEquals(ends.map(_.toolName), Vector("shout"))
      assertEquals(starts.head.callId, ends.head.callId)
      assertEquals(DynamicValues.recordGet(starts.head.args, "text").map(DynamicValues.renderText), Some("quiet"))
      assert(ends.head.output.exists(v => DynamicValues.renderText(v) == "QUIET"), ends.head.toString)
    finally interp.close()
  }
