package dspy4s.core.runtime

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SandboxOutputField
import dspy4s.core.contracts.SandboxTool
import dspy4s.core.contracts.ToolEndEvent
import dspy4s.core.contracts.ToolStartEvent
import dspy4s.core.contracts.:=
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

/** Live-gated suite for the Deno+Pyodide sandbox: skips (assume) when `deno` is not on PATH. The first-ever run on a
  * machine downloads Pyodide from npm into Deno's cache (network needed once); afterwards it's offline. Interpreters
  * are shared across tests where possible — sandbox startup costs seconds.
  */
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

  /** The cache directory of Deno, from `deno info --json`. */
  private lazy val denoDir: Option[String] =
    try
      val process = new ProcessBuilder("deno", "info", "--json").start()
      val output  = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      if process.waitFor() != 0 then None
      else
        DenoPyodideProtocol.decodeRecord(output).flatMap(DynamicValues.recordGet(_, "denoDir"))
          .flatMap(DynamicValues.asString)
    catch case NonFatal(_) => None

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

  test(
    "variable injection defines host values as Python variables (strings with quotes/newlines, numbers, lists, records)"
  ) {
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

  test("variables are re-assigned on every execute: a sandbox-side mutation does not survive to the next call") {
    assumeDeno()
    // The SAME map instance across calls, as RLM's loop does — this also exercises the interpreter's cached
    // assignment prelude, which must still be prepended (and re-run) on every call.
    val variables = Map[String, DynamicValue]("counter" -> DynamicValues.fromAny(10))
    assertEquals(plain.execute("counter = counter + 1\nprint(counter)", variables).toOption.get.stdout.trim, "11")
    // Upstream parity: the injection prelude resets `counter` to 10 before this block runs.
    assertEquals(plain.execute("print(counter)", variables).toOption.get.stdout.trim, "10")
    // Other sandbox globals still persist across calls (the REPL itself is stateful).
    assertEquals(plain.execute("keep = counter * 2", variables).map(_.exitCode), Right(0))
    assertEquals(plain.execute("print(keep)", variables).toOption.get.stdout.trim, "20")
  }

  test("host tools are callable from sandboxed code, and SUBMIT returns a structured finalOutput") {
    assumeDeno()
    val greet = SandboxTool(
      name = "greet",
      parameters = Vector(SandboxTool.Param("who", Some("str"))),
      invoke = kwargs =>
        Right(DynamicValues.fromAny(
          s"hello-${DynamicValues.recordGet(kwargs, "who").map(DynamicValues.renderText).getOrElse("?")}"
        ))
    )
    val interp = new DenoPyodideInterpreter(
      tools = Vector(greet),
      outputFields = Vector(SandboxOutputField("answer"))
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
    val events   = ArrayBuffer.empty[CallbackEvent]
    val recorder = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = events += event
    val shout = SandboxTool(
      name = "shout",
      parameters = Vector(SandboxTool.Param("text", Some("str"))),
      invoke = kwargs =>
        Right(DynamicValues.fromAny(DynamicValues.recordGet(
          kwargs,
          "text"
        ).map(DynamicValues.renderText).getOrElse("?").toUpperCase))
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

  test("an unhandled promise rejection in the sandbox does not replace the response, and the sandbox survives") {
    assumeDeno()
    // The runner reports the rejection as an error with a null id, before the response to the request (G-33).
    val code   = "from pyodide.code import run_js\nrun_js(\"Promise.reject(new Error('boom')); 1\")\nprint('first')"
    val interp = new DenoPyodideInterpreter()
    try
      assertEquals(interp.execute(code).map(result => (result.stdout.trim, result.exitCode)), Right(("first", 0)))
      assertEquals(interp.execute("print('second')").map(_.stdout.trim), Right("second"))
    finally interp.close()
  }

  test("a host tool that throws raises in the sandboxed code, and the sandbox survives") {
    assumeDeno()
    val explode = SandboxTool(
      name = "explode",
      parameters = Vector.empty,
      invoke = _ => throw new IllegalStateException("tool exploded")
    )
    val interp = new DenoPyodideInterpreter(tools = Vector(explode))
    try
      val failure = interp.execute("explode()").toOption.get
      assertEquals(failure.exitCode, 1)
      assert(failure.stderr.contains("tool exploded"), failure.stderr)
      assertEquals(interp.execute("print('alive')").map(_.stdout.trim), Right("alive"))
    finally interp.close()
  }

  // The tests below are for the current upstream runner and the launch rules (PORT_GAPS G-34).

  test("the sandbox runs the fixed Pyodide version") {
    assumeDeno()
    assertEquals(plain.execute("import pyodide\nprint(pyodide.__version__)").map(_.stdout.trim), Right("0.29.4"))
  }

  test("the sandboxed code cannot read the Deno cache directory after the start") {
    assumeDeno()
    assume(denoDir.isDefined, "deno info gave no cache directory")
    // `js.Deno` is the Deno API of the host process. The runner revokes the read permission for the cache after it
    // loaded Pyodide, so generated code cannot read the packages of other projects.
    val code = """import js
        |try:
        |    js.Deno.statSync(deno_dir)
        |    print("READ")
        |except Exception as error:
        |    print("DENIED")
        |""".stripMargin
    val interp = new DenoPyodideInterpreter()
    try
      val result = interp.execute(code, Map("deno_dir" -> DynamicValues.fromAny(denoDir.get)))
      assertEquals(result.map(_.stdout.trim), Right("DENIED"))
    finally interp.close()
  }

  test("an exception class of the user code with the name FinalOutput is a user-code error") {
    assumeDeno()
    // The runner uses a private control exception for SUBMIT. A class of the user code with the old name must not
    // look like a SUBMIT call.
    val code   = "class FinalOutput(Exception):\n    pass\nraise FinalOutput('mine')"
    val interp = new DenoPyodideInterpreter(outputFields = Vector(SandboxOutputField("answer")))
    try
      val result = interp.execute(code).toOption.get
      assertEquals(result.finalOutput, None)
      assertEquals(result.exitCode, 1)
      assert(result.stderr.contains("FinalOutput"), result.stderr)
      // SUBMIT works after the user code defined that class.
      val submitted = interp.execute("SUBMIT(answer='done')").toOption.get
      assert(submitted.finalOutput.exists(_.contains("done")), submitted.toString)
    finally interp.close()
  }

  test("a write path cannot overlap the Deno cache directory") {
    assumeDeno()
    assume(denoDir.isDefined, "deno info gave no cache directory")
    val interp = new DenoPyodideInterpreter(enableWritePaths = Vector(denoDir.get))
    try interp.execute("print(1)") match
        case Left(error) => assert(error.message.contains("overlap"), error.message)
        case Right(ok)   => fail(s"expected a failure, got $ok")
    finally interp.close()
  }

  test("the mount creates a write path that does not exist, and the sandbox writes to it") {
    assumeDeno()
    // The temporary directory of macOS is behind a symbolic link. The permission flags and the mount must name the
    // same real path, also when the file does not exist at the launch.
    val file   = Files.createTempDirectory("dspy4s-write").resolve("out.txt")
    val code   = "with open('/sandbox/out.txt', 'w') as out:\n    out.write('from the sandbox')\nprint('written')"
    val interp = new DenoPyodideInterpreter(enableWritePaths = Vector(file.toString))
    val result =
      try interp.execute(code).map(_.stdout.trim)
      finally interp.close()
    assertEquals(result, Right("written"))
    // The client sends the file sync as a notification after each call and does not wait for it. The runner handles
    // its messages in order, and `close` returns after the runner stopped. Thus the file is complete only after
    // `close`: a read before that can see the file while the runner writes it.
    assertEquals(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), "from the sandbox")
  }

  test("a Deno process that stops ends the session, and no new process starts") {
    assumeDeno()
    val interp = new DenoPyodideInterpreter()
    try
      assertEquals(interp.execute("kept = 41").map(_.exitCode), Right(0))
      // `js.Deno` is the Deno API of the host process. `exit` needs no permission.
      interp.execute("import js\njs.Deno.exit(7)") match
        case Left(error) => assert(error.message.contains("exited (code 7)"), error.message)
        case Right(ok)   => fail(s"expected a failure, got $ok")
      // A new process has no `kept` variable. The interpreter must not hide that loss.
      interp.execute("print(kept + 1)") match
        case Left(error) => assert(error.message.contains("session has ended"), error.message)
        case Right(ok)   => fail(s"expected a failure, got $ok")
    finally interp.close()
  }
