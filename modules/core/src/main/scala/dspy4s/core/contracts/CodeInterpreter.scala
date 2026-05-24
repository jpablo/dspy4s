package dspy4s.core.contracts

/** Structured result of executing a snippet of code via a [[CodeInterpreter]].
  *
  *   - `stdout`: bytes captured from the interpreter's standard out
  *   - `stderr`: bytes captured from the interpreter's standard error
  *   - `exitCode`: process / interpreter exit status; `0` on success
  *   - `finalOutput`: the structured value handed back via the interpreter's
  *     "submit" convention if the code chose to terminate early (Python
  *     DSPy's `FinalOutput`). `None` for the common case where the code
  *     just printed its result. */
final case class CodeResult(
    stdout: String,
    stderr: String,
    exitCode: Int,
    finalOutput: Option[String] = None
):
  def isSuccess: Boolean = exitCode == 0 && finalOutput.isEmpty || finalOutput.isDefined

/** Conventional component strings for [[RuntimeError]]s that originate from
  * a [[CodeInterpreter]]. User code errors — `NameError`, `SyntaxError`,
  * etc. — surface as a successful [[CodeResult]] with non-zero `exitCode`
  * and the traceback in `stderr`, *not* as a [[DspyError]]. This split
  * mirrors Python DSPy's `CodeInterpreterError` vs language exceptions.
  *
  * A [[RuntimeError]] from the interpreter means the interpreter *itself*
  * failed: process didn't start, exceeded its timeout, IO crashed. */
object CodeInterpreterErrors:
  val Interpreter: String = "code_interpreter"
  val Timeout: String = "code_interpreter_timeout"

/** Code execution primitive. Implementations execute a code string in some
  * sandbox and return structured output.
  *
  * Lifecycle:
  *   - Instances are created with whatever configuration their concrete
  *     constructor needs (interpreter path, timeout, etc.).
  *   - `execute(code)` may be called many times. Whether state persists
  *     across calls is implementation-specific (see each impl's docstring).
  *   - `close()` releases any held resources (long-lived subprocesses, REPL
  *     handles). Idempotent. After `close()`, behavior of further `execute`
  *     calls is implementation-defined — most impls should fail fast.
  *
  * Used by [[dspy4s.programs.CodeAct]] and (future) `ProgramOfThought` /
  * `RLM` ports.
  */
trait CodeInterpreter extends AutoCloseable:
  def execute(code: String): Either[DspyError, CodeResult]
  def close(): Unit
