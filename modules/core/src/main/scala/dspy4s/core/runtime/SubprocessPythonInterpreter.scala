package dspy4s.core.runtime

import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.CodeInterpreterErrors
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeError

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal

/** Plain-`python3` [[CodeInterpreter]]: each `execute(code)` spawns a fresh
  * `python3 -c "<code>"` subprocess, captures stdout + stderr, returns the
  * exit code.
  *
  * '''SECURITY NOTE.''' This implementation is **not sandboxed**. LM-generated
  * code runs with the host user's full filesystem, network, and environment
  * access. Use only when:
  *   - The code source is trusted (your own programs, internal scripts), OR
  *   - You're running in an already-isolated environment (container, VM,
  *     ephemeral CI), OR
  *   - You're prototyping with examples you can audit.
  *
  * For untrusted LM output, swap in the future `DenoPyodideInterpreter`
  * (sandboxed via WASM, ports Python DSPy's `PythonInterpreter` 1:1) or
  * supply your own sandboxed [[CodeInterpreter]] impl.
  *
  * '''No REPL state across calls.''' Each `execute` is independent — variables
  * defined in one call vanish before the next. [[dspy4s.programs.CodeAct]]
  * accumulates code across iterations on its side so this limitation is
  * transparent at the program level. A stateful interpreter would have to
  * keep a long-lived `python3 -i` subprocess and shuttle code via stdin; the
  * Deno+Pyodide impl planned for v2 does this properly.
  *
  * @param pythonCommand the executable to invoke (default `"python3"`;
  *                      override for `python`, `python3.11`, virtualenv
  *                      paths, etc.)
  * @param timeoutMillis kill the subprocess if it runs longer than this.
  *                      Returns a `RuntimeError(CodeInterpreterErrors.Timeout, …)` and
  *                      destroys the process. 0 = no timeout.
  */
final class SubprocessPythonInterpreter(
    pythonCommand: String = "python3",
    timeoutMillis: Long = 30_000L
) extends CodeInterpreter:

  @volatile private var closed = false

  override def execute(code: String): Either[DspyError, CodeResult] =
    if closed then Left(RuntimeError(CodeInterpreterErrors.Interpreter, "Interpreter is closed"))
    else
      try
        val pb = new ProcessBuilder(pythonCommand, "-c", code)
        pb.redirectErrorStream(false)
        val process = pb.start()
        process.getOutputStream.close() // we don't write to stdin

        val stdoutBuf = new ByteArrayOutputStream()
        val stderrBuf = new ByteArrayOutputStream()

        // Drain stdout/stderr on dedicated threads so neither pipe fills its
        // OS buffer and deadlocks the child.
        val stdoutThread = drainTo(process.getInputStream, stdoutBuf, "stdout-pump")
        val stderrThread = drainTo(process.getErrorStream, stderrBuf, "stderr-pump")

        val finishedInTime =
          if timeoutMillis > 0 then process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
          else { process.waitFor(); true }

        if !finishedInTime then
          process.destroyForcibly()
          stdoutThread.join(500)
          stderrThread.join(500)
          Left(RuntimeError(
            CodeInterpreterErrors.Timeout,
            s"Python subprocess exceeded ${timeoutMillis}ms timeout and was forcibly terminated"
          ))
        else
          stdoutThread.join()
          stderrThread.join()
          Right(CodeResult(
            stdout = stdoutBuf.toString("UTF-8"),
            stderr = stderrBuf.toString("UTF-8"),
            exitCode = process.exitValue()
          ))
      catch
        case e: IOException =>
          Left(RuntimeError(
            "interpreter",
            s"Failed to spawn '$pythonCommand': ${e.getMessage}. Is Python installed and on PATH?"
          ))
        case NonFatal(other) =>
          Left(RuntimeError(CodeInterpreterErrors.Interpreter, Option(other.getMessage).getOrElse(other.getClass.getSimpleName)))

  override def close(): Unit =
    closed = true

  /** Spawn a daemon pump thread that copies bytes from `in` into `out` until
    * EOF. Returns immediately; caller `.join()`s when ready. */
  private def drainTo(in: java.io.InputStream, out: ByteArrayOutputStream, name: String): Thread =
    val thread = new Thread(() => {
      val buf = new Array[Byte](4096)
      try
        var n = in.read(buf)
        while n >= 0 do
          out.write(buf, 0, n)
          n = in.read(buf)
      catch case NonFatal(_) => ()
      finally try in.close() catch case NonFatal(_) => ()
    }, name)
    thread.setDaemon(true)
    thread.start()
    thread

object SubprocessPythonInterpreter:
  /** Cheap "does `python3` exist on PATH?" probe. Useful for tests to skip
    * when no interpreter is available. */
  def isAvailable(command: String = "python3"): Boolean =
    try
      val pb = new ProcessBuilder(command, "--version")
      pb.redirectErrorStream(true)
      val process = pb.start()
      val ok = process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
      if !ok then process.destroyForcibly()
      ok
    catch case NonFatal(_) => false
