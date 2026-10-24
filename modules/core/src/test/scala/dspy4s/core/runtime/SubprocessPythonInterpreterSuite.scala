package dspy4s.core.runtime

import dspy4s.core.contracts.CodeInterpreterErrors
import dspy4s.core.contracts.RuntimeError
import munit.FunSuite

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Tests for [[SubprocessPythonInterpreter]]. Auto-skipped (`assume(...)`) when `python3` isn't installed, so
  * `sbt test` passes on systems without Python.
  */
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
      val _      = interp.execute("x = 42")
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

  test("a spawn failure has the interpreter component") {
    val interpreter = new SubprocessPythonInterpreter(pythonCommand = "/nonexistent/dspy4s/python3")
    interpreter.execute("print(1)") match
      case Left(dspy4s.core.contracts.RuntimeError(component, _)) =>
        assertEquals(component, dspy4s.core.contracts.CodeInterpreterErrors.Interpreter)
      case other => fail(s"expected a RuntimeError, got $other")
  }

  /** Python code that starts a child process that sleeps 30 seconds and writes the pid of the child to `pidFile`. The
    * child gets the standard output and the standard error of the code.
    */
  private def startChild(pidFile: Path): String =
    "import subprocess, sys; " +
      "child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)']); " +
      s"open('$pidFile', 'w').write(str(child.pid))"

  private def readPid(pidFile: Path): Option[Long] = Files.readString(pidFile).trim.toLongOption

  test("execute returns when a child process keeps the output open after the code exits") {
    val pidFile = Files.createTempFile("dspy4s-child", ".pid")
    val interp  = new SubprocessPythonInterpreter(timeoutMillis = 3000L)
    try
      val result = BoundedWait.within(15)(interp.execute(startChild(pidFile)))
      assert(result.isDefined, "execute did not return while a child process held the output open")
    finally
      interp.close()
      readPid(pidFile).foreach(BoundedWait.killProcess)
  }

  test("a timeout kills the child processes of the code") {
    val pidFile = Files.createTempFile("dspy4s-child", ".pid")
    val interp  = new SubprocessPythonInterpreter(timeoutMillis = 1500L)
    try
      val result = BoundedWait.within(15)(interp.execute(startChild(pidFile) + "; import time; time.sleep(30)"))
      assert(result.exists(_.isLeft), s"expected a timeout Left, got $result")
      val pid = readPid(pidFile)
      assert(pid.isDefined, "the code did not start its child process")
      assert(pid.forall(BoundedWait.processEnds(_, 5)), s"the child process ${pid.get} is still alive")
    finally
      interp.close()
      readPid(pidFile).foreach(BoundedWait.killProcess)
  }

  test("an interrupt of the caller gives a Left and stops the Python process") {
    val pidFile = Files.createTempFile("dspy4s-python", ".pid")
    val interp  = new SubprocessPythonInterpreter(timeoutMillis = 60000L)
    val outcome = new CompletableFuture[Either[Throwable, (Boolean, Boolean)]]()
    val caller  = new Thread(() =>
      val run =
        try
          val result = interp.execute(s"import os, time; open('$pidFile', 'w').write(str(os.getpid())); time.sleep(30)")
          Right((result.isLeft, Thread.currentThread().isInterrupted))
        catch case error: Throwable => Left(error)
      val _ = outcome.complete(run)
    )
    caller.setDaemon(true)
    try
      caller.start()
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      while readPid(pidFile).isEmpty && System.nanoTime() < deadline do Thread.sleep(50)
      val pid = readPid(pidFile)
      assert(pid.isDefined, "the Python process did not start")
      caller.interrupt()
      val result = outcome.get(10, TimeUnit.SECONDS)
      assertEquals(result.map(_._1), Right(true), s"expected execute to give a Left, got $result")
      assertEquals(result.map(_._2), Right(true), "the caller thread lost its interrupt state")
      assert(pid.forall(BoundedWait.processEnds(_, 5)), s"the Python process ${pid.get} is still alive")
    finally
      interp.close()
      readPid(pidFile).foreach(BoundedWait.killProcess)
  }
