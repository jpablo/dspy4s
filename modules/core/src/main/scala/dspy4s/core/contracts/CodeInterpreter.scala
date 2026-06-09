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

/** A [[CodeInterpreter]] with the REPL surface `RLM` drives: executing code with host VARIABLES defined first
  * (long contexts enter the sandbox as variables, never the prompt). Implementations are expected to be stateful
  * across `execute` calls and to support the `SUBMIT(...)` convention ([[CodeResult.finalOutput]]) plus host
  * [[SandboxTool]]s — [[dspy4s.core.runtime.DenoPyodideInterpreter]] is the canonical one. */
trait ReplCodeInterpreter extends CodeInterpreter:
  /** Like `execute(code)`, but first defines each of `variables` as a variable in the sandbox. */
  def execute(
      code: String,
      variables: Map[String, zio.blocks.schema.DynamicValue]
  ): Either[DspyError, CodeResult]

/** A host-side function callable BY NAME from inside a sandboxed interpreter (Python DSPy's
  * `PythonInterpreter(tools=...)`): sandboxed code calls `name(kwarg=...)`, the sandbox bridges the call back
  * to the host, [[invoke]] runs, and its result returns into the sandbox. This is the seam RLM's `llm_query` /
  * `llm_query_batched` use — sub-LM calls made from inside generated code.
  *
  * @param name       the Python identifier the sandbox exposes
  * @param parameters declared parameters (name + optional Python type like `"str"`/`"list"`), used by the
  *                   sandbox to generate a typed wrapper signature
  * @param invoke     the host implementation, given the call's keyword arguments. A string result crosses into
  *                   the sandbox as a Python `str`; any other value crosses as JSON.
  */
final case class SandboxTool(
    name: String,
    parameters: Vector[SandboxTool.Param],
    invoke: zio.blocks.schema.DynamicValue.Record => Either[DspyError, zio.blocks.schema.DynamicValue]
)

object SandboxTool:
  final case class Param(name: String, pythonType: Option[String] = None)
