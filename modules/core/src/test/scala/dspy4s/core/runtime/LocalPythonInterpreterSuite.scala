package dspy4s.core.runtime

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.CodeInterpreterErrors
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.InterpreterEndEvent
import dspy4s.core.contracts.InterpreterStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SandboxOutputField
import dspy4s.core.contracts.SandboxTool
import dspy4s.core.contracts.ValidationError
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

/** The persistent local Python interpreter (upstream `LocalInterpreter` in `dspy/primitives/local_interpreter.py`;
  * PORT_GAPS G-36, upgrade item I6). The suite skips when `python3` (3.10 or later) is not on PATH.
  */
class LocalPythonInterpreterSuite extends FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(120, "s")

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private lazy val pythonAvailable: Boolean =
    try
      val process = new ProcessBuilder("python3", "-c", "import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)")
        .start()
      process.waitFor() == 0
    catch case NonFatal(_) => false

  private def assumePython(): Unit = assume(pythonAvailable, "python3 3.10+ not installed")

  private def withInterpreter[A](interpreter: LocalPythonInterpreter)(body: LocalPythonInterpreter => A): A =
    try body(interpreter)
    finally interpreter.close()

  private def ok(result: Either[DspyError, CodeResult]): CodeResult =
    result.fold(error => fail(s"execute failed: $error"), identity)

  /** The submitted values of a result, each as text. */
  private def submitted(result: CodeResult): Map[String, String] =
    result.finalOutput.flatMap(DynamicValues.parseJsonRecord)
      .map(record =>
        DynamicValues.recordEntries(record).map((name, value) => name -> DynamicValues.renderText(value)).toMap
      )
      .getOrElse(fail(s"no submitted values: $result"))

  private val greet = SandboxTool(
    name = "greet",
    parameters = Vector(SandboxTool.Param("who", Some("str"))),
    invoke = kwargs =>
      DynamicValues.recordGet(kwargs, "who").map(DynamicValues.renderText) match
        case Some("nobody") => Left(ValidationError("no one to greet"))
        case Some(who)      => Right(DynamicValues.fromAny(s"hello-$who"))
        case None           => Right(DynamicValues.fromAny("hello-?"))
  )

  // ── Code and state ──────────────────────────────────────────────────────────────────────────────────────────

  test("printed output, state across calls, and variables") {
    assumePython()
    withInterpreter(LocalPythonInterpreter()) { interpreter =>
      assertEquals(ok(interpreter.execute("print(1 + 2)")).stdout.trim, "3")
      assertEquals(ok(interpreter.execute("x = 40")).exitCode, 0)
      assertEquals(ok(interpreter.execute("print(x + 2)")).stdout.trim, "42")
      assertEquals(
        ok(interpreter.execute("print(len(ctx))", Map("ctx" -> DynamicValues.fromAny("abcd")))).stdout.trim,
        "4"
      )
    }
  }

  test("the value of the last expression is the output, as upstream") {
    assumePython()
    withInterpreter(LocalPythonInterpreter()) { interpreter =>
      assertEquals(ok(interpreter.execute("1 + 2")).stdout.trim, "3")
      assertEquals(ok(interpreter.execute("'text'")).stdout.trim, "text")
      assertEquals(ok(interpreter.execute("print('shown')\nNone")).stdout.trim, "shown")
    }
  }

  test("an exception or a syntax error of the code is a result with an error, and the session continues") {
    assumePython()
    withInterpreter(LocalPythonInterpreter()) { interpreter =>
      val divide = ok(interpreter.execute("1 / 0"))
      assertNotEquals(divide.exitCode, 0)
      assert(divide.stderr.contains("ZeroDivisionError"), divide.toString)
      val syntax = ok(interpreter.execute("def broken(:"))
      assertNotEquals(syntax.exitCode, 0)
      assert(syntax.stderr.contains("SyntaxError"), syntax.toString)
      assertEquals(ok(interpreter.execute("print('alive')")).stdout.trim, "alive")
    }
  }

  // ── SUBMIT ──────────────────────────────────────────────────────────────────────────────────────────────────

  test("SUBMIT with output fields gives the values of the fields") {
    assumePython()
    val fields = Some(Vector(SandboxOutputField("answer"), SandboxOutputField("score")))
    withInterpreter(LocalPythonInterpreter(outputFields = fields)) { interpreter =>
      val keyword    = ok(interpreter.execute("SUBMIT(answer='Paris', score=3)"))
      val positional = ok(interpreter.execute("SUBMIT('Rome', 4)"))
      assertEquals(submitted(keyword), Map("answer" -> "Paris", "score" -> "3"))
      assertEquals(submitted(positional), Map("answer" -> "Rome", "score" -> "4"))
    }
  }

  test("SUBMIT with no output fields gives the one value under output") {
    assumePython()
    withInterpreter(LocalPythonInterpreter()) { interpreter =>
      val result = ok(interpreter.execute("SUBMIT(5)"))
      assertEquals(submitted(result), Map("output" -> "5"))
    }
  }

  // ── Host tools ──────────────────────────────────────────────────────────────────────────────────────────────

  test("a host tool runs with keyword or positional arguments, and a tool failure raises in the code") {
    assumePython()
    withInterpreter(LocalPythonInterpreter(tools = Vector(greet))) { interpreter =>
      assertEquals(ok(interpreter.execute("print(greet(who='ann'))")).stdout.trim, "hello-ann")
      assertEquals(ok(interpreter.execute("print(greet('bob'))")).stdout.trim, "hello-bob")
      val failed = ok(interpreter.execute("greet(who='nobody')"))
      assertNotEquals(failed.exitCode, 0)
      assert(failed.stderr.contains("no one to greet"), failed.toString)
    }
  }

  test("a variable with a reserved name is a Left, and the session continues") {
    assumePython()
    withInterpreter(LocalPythonInterpreter(tools = Vector(greet))) { interpreter =>
      Vector("greet", "SUBMIT", "class", "not-a-name").foreach { name =>
        assert(interpreter.execute("pass", Map(name -> DynamicValues.fromAny(1))).isLeft, name)
      }
      assertEquals(ok(interpreter.execute("print('alive')")).stdout.trim, "alive")
    }
  }

  // ── The end of the session ──────────────────────────────────────────────────────────────────────────────────

  test("an execute over the time limit is a timeout Left, and the session ends") {
    assumePython()
    withInterpreter(LocalPythonInterpreter(executionTimeout = Some(500.millis))) { interpreter =>
      interpreter.execute("while True: pass") match
        case Left(RuntimeError(component, _)) => assertEquals(component, CodeInterpreterErrors.Timeout)
        case other                            => fail(s"expected a timeout, got $other")
      assert(interpreter.execute("print(1)").isLeft)
    }
  }

  test("after close, an execute is a Left, and a second close is fine") {
    assumePython()
    val interpreter = LocalPythonInterpreter()
    assertEquals(ok(interpreter.execute("print(1)")).stdout.trim, "1")
    interpreter.close()
    interpreter.close()
    assert(interpreter.execute("print(1)").isLeft)
  }

  test("the factory gives a new interpreter with the tools and the output fields of the call") {
    assumePython()
    val factory = LocalPythonInterpreter.factory()
    val first   = factory(Vector(greet), Vector(SandboxOutputField("answer")))
    val second  = factory(Vector.empty, Vector(SandboxOutputField("answer")))
    try
      assertEquals(ok(first.execute("x = 1\nprint(greet('ann'))")).stdout.trim, "hello-ann")
      val other = ok(second.execute("print('x' in globals())"))
      assertEquals(other.stdout.trim, "False")
      assertEquals(submitted(ok(second.execute("SUBMIT(answer='a')"))), Map("answer" -> "a"))
    finally
      first.close()
      second.close()
  }

  // ── Callbacks ───────────────────────────────────────────────────────────────────────────────────────────────

  test("execute, lazy startup, tool call, and shutdown scopes come in the upstream order") {
    assumePython()
    val events  = ArrayBuffer.empty[CallbackEvent]
    val handler = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = events += event
    RuntimeEnvironment.withCallbacks(Vector(handler)) {
      val interpreter = LocalPythonInterpreter(tools = Vector(greet))
      try assert(interpreter.execute("print(greet('ann'))").isRight)
      finally interpreter.close()
    }
    val labels = events.toVector.collect {
      case start: InterpreterStartEvent => s"${start.operation}-start"
      case end: InterpreterEndEvent     => s"${end.operation}-end"
    }
    assertEquals(
      labels,
      Vector(
        "Execute-start",
        "Startup-start",
        "Startup-end",
        "ToolCall-start",
        "ToolCall-end",
        "Execute-end",
        "Shutdown-start",
        "Shutdown-end"
      )
    )
    val starts = events.toVector.collect { case start: InterpreterStartEvent => start }
    assertEquals(starts.map(_.interpreterName).distinct, Vector("LocalPythonInterpreter"))
  }

  // ── The Python version ──────────────────────────────────────────────────────────────────────────────────────

  /** An executable shell script in place of a Python executable. For each argument list, it writes `output` and exits
    * with `exitCode`. The tests thus do not need an old Python.
    */
  private def fakePython(output: String, exitCode: Int = 0): Path =
    val directory = Files.createTempDirectory("dspy4s-fake-python")
    val script    = directory.resolve("python3")
    val _         = Files.writeString(script, s"#!/bin/sh\necho '$output'\nexit $exitCode\n")
    val _         = script.toFile.setExecutable(true)
    directory.toFile.deleteOnExit()
    script.toFile.deleteOnExit()
    script

  /** The message of the `Left` of a first `execute` on `python`. The session then has ended. */
  private def startFailure(python: Path): String =
    val interpreter = LocalPythonInterpreter(python = python.toString)
    try interpreter.execute("print(1)") match
        case Left(RuntimeError(component, message)) =>
          assertEquals(component, CodeInterpreterErrors.Interpreter)
          assert(interpreter.execute("print(1)").left.exists(_.message.contains("session has ended")))
          message
        case other => fail(s"expected a Left, got $other")
    finally interpreter.close()

  // `3.9.6` is the output of the version check with the macOS `/usr/bin/python3`. On Python 3.9 the worker starts,
  // and a positional `SUBMIT` fails later with "TypeError: zip() takes no keyword arguments". Upstream starts
  // `sys.executable`, and dspy needs Python 3.10 or later (`pyproject.toml`, `requires-python`).
  test("a Python older than 3.10 is a Left that names the version found and the version needed") {
    val python = fakePython("3.9.6")
    assertEquals(
      startFailure(python),
      s"The Python executable '$python' is Python 3.9.6. LocalPythonInterpreter needs Python 3.10 or later."
    )
  }

  test("a Python executable whose version output is not a version is a Left") {
    val python = fakePython("not a version")
    assertEquals(
      startFailure(python),
      s"Unable to read the version of the Python executable '$python': not a version"
    )
  }

  test("a Python executable that fails the version check is a Left with its exit code and its output") {
    val python = fakePython("broken", exitCode = 3)
    assertEquals(
      startFailure(python),
      s"The version check of the Python executable '$python' failed with the exit code 3: broken"
    )
  }

  test("a variable with a lone surrogate is a Left, not an exception") {
    assumePython()
    val interpreter = LocalPythonInterpreter()
    try
      // The first half of an emoji, for example from a text that was cut at a fixed length.
      val cut = DynamicValue.Primitive(zio.blocks.schema.PrimitiveValue.String("cut \uD83D"))
      assert(interpreter.execute("ctx", Map("ctx" -> cut)).isLeft)
    finally interpreter.close()
  }

  test("a Python executable that does not exist is a Left") {
    val python = Files.createTempDirectory("dspy4s-no-python").resolve("python3")
    assert(startFailure(python).startsWith(s"Unable to start the Python worker '$python': "))
  }

  // The tests below are for data of the code that JSON cannot hold. Each one ends with a check that the session is alive.

  /** The code that defines `deep`, a list with too many levels for `json.dumps` and for repr. */
  private def deepList = "deep = []\nfor _ in range(100000):\n    deep = [deep]\n"

  /** The code that defines the class `Foo`. Its `__repr__` raises. */
  private def badRepr = "class Foo:\n    def __repr__(self):\n        raise RuntimeError('boom')\n"

  /** Run `body` on an interpreter with the output field `answer`. Also check that the session is alive after `body`. */
  private def withSession(body: LocalPythonInterpreter => Unit): Unit =
    assumePython()
    val fields = Some(Vector(SandboxOutputField("answer")))
    withInterpreter(LocalPythonInterpreter(tools = Vector(greet), outputFields = fields)) { interpreter =>
      body(interpreter)
      assertEquals(ok(interpreter.execute("print('alive')")).stdout.trim, "alive")
    }

  /** Check that `code` is an error of the code with `text` in the standard error. */
  private def assertCodeError(interpreter: LocalPythonInterpreter, code: String, text: String*): Unit =
    val result = ok(interpreter.execute(code))
    assertEquals(result.exitCode, 1, result.toString)
    text.foreach(part => assert(result.stderr.contains(part), result.stderr))

  private def finalOf(interpreter: LocalPythonInterpreter, code: String): Option[String] =
    ok(interpreter.execute(code)).finalOutput

  test("SUBMIT with a value that JSON cannot hold gives its repr as text, and the session continues") {
    withSession { interpreter =>
      assertEquals(finalOf(interpreter, "SUBMIT(answer=float('nan'))"), Some("\"{'answer': nan}\""))
      assertEquals(finalOf(interpreter, "SUBMIT(answer=float('inf'))"), Some("\"{'answer': inf}\""))
      assertEquals(finalOf(interpreter, "SUBMIT(answer=float('-inf'))"), Some("\"{'answer': -inf}\""))
      assertEquals(finalOf(interpreter, "SUBMIT(answer={1})"), Some("\"{'answer': {1}}\""))
      val plain = "class Plain:\n    def __repr__(self):\n        return 'Plain()'\nSUBMIT(answer=Plain())"
      assertEquals(finalOf(interpreter, plain), Some("\"{'answer': Plain()}\""))
      assertEquals(
        finalOf(interpreter, "loop = []\nloop.append(loop)\nSUBMIT(answer=loop)"),
        Some("\"{'answer': [[...]]}\"")
      )
    }
  }

  test("SUBMIT with a very deep value gives a fixed text with the type name, and the session continues") {
    // `json.dumps` raises a RecursionError here. The upstream `jsonable` runs in an `except` clause, and it does not
    // catch that error, so the error ends the session.
    withSession { interpreter =>
      // The value of a SUBMIT is the dict of the fields, so the text names `dict`.
      assertEquals(finalOf(interpreter, deepList + "SUBMIT(answer=deep)"), Some("\"<unprintable dict>\""))
    }
  }

  test("SUBMIT with an object whose __repr__ raises gives a fixed text with the type name, and the session continues") {
    withSession { interpreter =>
      assertEquals(finalOf(interpreter, badRepr + "SUBMIT(answer=Foo())"), Some("\"<unprintable dict>\""))
    }
  }

  test("an exception with a value that JSON cannot hold is an error of the code, and the session continues") {
    withSession { interpreter =>
      assertCodeError(interpreter, "raise ValueError(float('nan'))", "ValueError: nan")
      assertCodeError(interpreter, "raise ValueError({1})", "ValueError: {1}")
      assertCodeError(interpreter, "loop = []\nloop.append(loop)\nraise ValueError(loop)", "ValueError: [[...]]")
    }
  }

  test("an exception whose text raises is an error of the code with a fixed text, and the session continues") {
    // The upstream `describe` calls `str` on the exception with no `try`, so these exceptions end the session.
    withSession { interpreter =>
      assertCodeError(interpreter, deepList + "raise ValueError(deep)", "ValueError: <unprintable ValueError>")
      assertCodeError(interpreter, badRepr + "raise ValueError(Foo())", "ValueError: <unprintable ValueError>")
      val badStr = "class Odd(Exception):\n    def __str__(self):\n        raise RuntimeError('boom')\nraise Odd()"
      assertCodeError(interpreter, badStr, "Odd: Odd()")
    }
  }

  test("the last expression with a value that JSON cannot hold gives its repr or the fixed text") {
    withSession { interpreter =>
      def output(code: String) =
        val result = ok(interpreter.execute(code))
        assertEquals(result.exitCode, 0, result.toString)
        result.stdout
      assertEquals(output("float('nan')"), "nan")
      assertEquals(output("{1}"), "{1}")
      assertEquals(output("[float('nan')]"), "[nan]")
      assertEquals(output("loop = []\nloop.append(loop)\nloop"), "[[...]]")
      assertEquals(output(deepList + "deep"), "<unprintable list>")
      assertEquals(output(badRepr + "Foo()"), "<unprintable Foo>")
    }
  }

  test("a tool call with an argument that JSON cannot hold is an error of the code, and the tool does not run") {
    val invoked = new AtomicInteger
    val counter = SandboxTool(
      name = "count",
      parameters = Vector(SandboxTool.Param("who", Some("str"))),
      invoke = _ =>
        val _ = invoked.incrementAndGet()
        Right(DynamicValues.string("counted"))
    )
    assumePython()
    withInterpreter(LocalPythonInterpreter(tools = Vector(counter))) { interpreter =>
      assertCodeError(interpreter, "count(who=float('nan'))", "ValueError", "Out of range float")
      assertCodeError(interpreter, "count(who={1})", "TypeError")
      assertCodeError(interpreter, "loop = []\nloop.append(loop)\ncount(who=loop)", "ValueError", "Circular reference")
      assertEquals(invoked.get(), 0)
      assertEquals(ok(interpreter.execute("print(count(who='ann'))")).stdout.trim, "counted")
    }
  }

  test("a name of the code does not change the worker, because the code has its own namespace") {
    withSession { interpreter =>
      assertCodeError(interpreter, "json = None\nraise ValueError('kept')", "ValueError: kept")
      assertEquals(finalOf(interpreter, "json = None\nSUBMIT(answer=1)"), Some("{\"answer\":1}"))
      assertEquals(finalOf(interpreter, "json = None\nSUBMIT(answer=float('nan'))"), Some("\"{'answer': nan}\""))
    }
  }
