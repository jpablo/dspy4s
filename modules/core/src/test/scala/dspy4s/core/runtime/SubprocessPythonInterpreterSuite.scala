package dspy4s.core.runtime

import dspy4s.core.contracts.CodeInterpreterErrors
import dspy4s.core.contracts.RuntimeError
import munit.FunSuite

/** Tests for [[SubprocessPythonInterpreter]]. Auto-skipped (`assume(...)`)
  * when `python3` isn't installed, so `sbt test` passes on systems
  * without Python. */
class SubprocessPythonInterpreterSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    assume(SubprocessPythonInterpreter.isAvailable(), "python3 not on PATH — skipping")

  test("execute prints a value to stdout and returns exitCode=0") {
    val interp = new SubprocessPythonInterpreter()
    try
      val result = interp.execute("print('hello, world!')").toOption.get
      assertEquals(result.exitCode, 0)
      assertEquals(result.stdout.trim, "hello, world!")
      assertEquals(result.stderr, "")
    finally interp.close()
  }

  test("execute captures multi-line stdout in order") {
    val interp = new SubprocessPythonInterpreter()
    try
      val result = interp.execute("for i in range(3): print(i)").toOption.get
      assertEquals(result.exitCode, 0)
      assertEquals(result.stdout.split('\n').toList.filter(_.nonEmpty), List("0", "1", "2"))
    finally interp.close()
  }

  test("execute surfaces a NameError as exitCode != 0 with traceback in stderr (not a DspyError)") {
    val interp = new SubprocessPythonInterpreter()
    try
      // User-level Python error is NOT a CodeInterpreter failure — it's a
      // successful execution with a non-zero exit code. The Left path is
      // reserved for interpreter-itself failures.
      val result = interp.execute("print(undefined_variable)").toOption.get
      assert(result.exitCode != 0, s"expected non-zero exit code, got ${result.exitCode}")
      assert(result.stderr.contains("NameError"), s"expected NameError in stderr, got: ${result.stderr}")
    finally interp.close()
  }

  test("execute surfaces a SyntaxError the same way (non-zero exit, traceback in stderr)") {
    val interp = new SubprocessPythonInterpreter()
    try
      val result = interp.execute("def broken(:").toOption.get
      assert(result.exitCode != 0)
      assert(result.stderr.toLowerCase.contains("syntax"), result.stderr)
    finally interp.close()
  }

  test("execute kills a runaway subprocess on timeout") {
    val interp = new SubprocessPythonInterpreter(timeoutMillis = 200L)
    try
      val result = interp.execute("import time; time.sleep(5)")
      assert(result.isLeft, s"expected timeout left, got: $result")
      val err = result.left.toOption.get.asInstanceOf[RuntimeError]
      assertEquals(err.component, CodeInterpreterErrors.Timeout, err.toString)
    finally interp.close()
  }

  test("execute after close() returns a 'closed' RuntimeError") {
    val interp = new SubprocessPythonInterpreter()
    interp.close()
    val result = interp.execute("print(1)")
    assert(result.isLeft)
    val err = result.left.toOption.get.asInstanceOf[RuntimeError]
    assertEquals(err.component, CodeInterpreterErrors.Interpreter)
    assert(err.message.toLowerCase.contains("closed"))
  }

  test("each execute is independent — no REPL state across calls") {
    val interp = new SubprocessPythonInterpreter()
    try
      interp.execute("x = 42")
      val result = interp.execute("print(x)").toOption.get
      // x is gone — this is the documented limitation of the subprocess impl.
      assert(result.exitCode != 0, "expected NameError on second call")
      assert(result.stderr.contains("NameError"), result.stderr)
    finally interp.close()
  }

  test("close() is idempotent") {
    val interp = new SubprocessPythonInterpreter()
    interp.close()
    interp.close() // no exception
  }

  test("isAvailable returns false for a definitely-missing command") {
    assert(!SubprocessPythonInterpreter.isAvailable("definitely-not-a-real-binary-xyz"))
  }
