package dspy4s.core.runtime

import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.CodeInterpreterErrors
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.InterpreterOperation
import dspy4s.core.contracts.RuntimeError
import zio.blocks.schema.DynamicValue

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.util.Using
import scala.util.control.NonFatal

/** Plain-`python3` [[CodeInterpreter]]: each `execute(code)` spawns a fresh `python3 -c "<code>"` subprocess, captures
  * stdout + stderr, returns the exit code.
  *
  * '''SECURITY NOTE.''' This implementation is **not sandboxed**. LM-generated code runs with the host user's full
  * filesystem, network, and environment access. Use only when:
  *   - The code source is trusted (your own programs, internal scripts), OR
  *   - You're running in an already-isolated environment (container, VM, ephemeral CI), OR
  *   - You're prototyping with examples you can audit.
  *
  * For untrusted LM output, use [[DenoPyodideInterpreter]] (sandboxed via WASM, ports Python DSPy's `PythonInterpreter`
  * 1:1) or supply your own sandboxed [[CodeInterpreter]] implementation.
  *
  * '''No REPL state across calls.''' Each `execute` is independent: variables defined in one call vanish before the
  * next. [[DenoPyodideInterpreter]] (sandboxed) and [[LocalPythonInterpreter]] (a persistent local subprocess, not
  * sandboxed) keep the state from one call to the next.
  *
  * '''Callback events.''' Each `execute` call is an `Execute` interpreter scope (its `variables` input is empty), and
  * each `close` call is a `Shutdown` interpreter scope. There is no long-lived process, so there are no `Startup`
  * scopes, and there are no host tools, so there are no `ToolCall` scopes.
  *
  * @param pythonCommand
  *   the executable to invoke (default `"python3"`; override for `python`, `python3.11`, virtualenv paths, etc.)
  * @param timeoutMillis
  *   kill the subprocess if it runs longer than this. Returns a `RuntimeError(CodeInterpreterErrors.Timeout, …)` and
  *   destroys the process. 0 = no timeout.
  */
final class SubprocessPythonInterpreter(
    pythonCommand: String = "python3",
    timeoutMillis: Long   = 30_000L
) extends CodeInterpreter:

  // The `interpreterName` of the callback events.
  private val interpreterName: String = getClass.getSimpleName

  @volatile private var closed = false

  override def execute(code: String): Either[DspyError, CodeResult] =
    val inputs = InterpreterSupport.executeInputs(code, Map.empty)
    CallbackDispatcher.withInterpreter(interpreterName, InterpreterOperation.Execute, inputs)(run(code))

  override def close(): Unit =
    val _ =
      CallbackDispatcher.withInterpreter(interpreterName, InterpreterOperation.Shutdown, DynamicValue.Record.empty) {
        closed = true
        Right(())
      }

  private def run(code: String): Either[DspyError, CodeResult] =
    if closed then CodeInterpreterErrors.failure("Interpreter is closed")
    else
      try
        val process = new ProcessBuilder(pythonCommand, "-c", code).start()
        process.getOutputStream.close() // we don't write to stdin

        val stdoutBuf = new ByteArrayOutputStream()
        val stderrBuf = new ByteArrayOutputStream()

        // Drain stdout/stderr on dedicated threads so neither pipe fills its
        // OS buffer and deadlocks the child.
        val stdoutThread = drainTo(process.getInputStream, stdoutBuf, "stdout-pump")
        val stderrThread = drainTo(process.getErrorStream, stderrBuf, "stderr-pump")

        val finishedInTime =
          if timeoutMillis > 0 then process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
          else
            process.waitFor(); true

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
            stdout = stdoutBuf.toString(StandardCharsets.UTF_8),
            stderr = stderrBuf.toString(StandardCharsets.UTF_8),
            exitCode = process.exitValue()
          ))
      catch
        case e: IOException => CodeInterpreterErrors.failure(
            s"Failed to spawn '$pythonCommand': ${e.getMessage}. Is Python installed and on PATH?"
          )
        case NonFatal(other) =>
          CodeInterpreterErrors.failure(Option(other.getMessage).getOrElse(other.getClass.getSimpleName))

  /** Start a daemon pump thread that copies the bytes of `in` to `out` until the end of the stream, then closes `in`.
    * The call returns at once; the caller joins the thread when it needs the bytes.
    */
  private def drainTo(in: InputStream, out: ByteArrayOutputStream, name: String): Thread =
    InterpreterSupport.startDaemon(name) {
      val _ = Using(in)(_.transferTo(out))
    }

object SubprocessPythonInterpreter:
  /** Cheap "does `python3` exist on PATH?" probe. Useful for tests to skip when no interpreter is available.
    */
  def isAvailable(command: String = "python3"): Boolean =
    try
      val pb = new ProcessBuilder(command, "--version")
      pb.redirectErrorStream(true)
      val process = pb.start()
      val ok      = process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
      if !ok then
        val _ = process.destroyForcibly()
      ok
    catch case NonFatal(_) => false
