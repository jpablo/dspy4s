package dspy4s.core.runtime

import dspy4s.core.contracts.CodeInterpreterErrors
import dspy4s.core.contracts.ReplCodeInterpreter
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SandboxTool
import dspy4s.core.contracts.:=
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue
import zio.blocks.schema.Schema

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.util.control.NonFatal

/** Sandboxed [[CodeInterpreter]]: Python via Pyodide (WASM) inside a Deno subprocess — a port of Python DSPy's
  * `PythonInterpreter` (`dspy/primitives/python_interpreter.py`). The sandbox side is upstream's `runner.js`,
  * vendored VERBATIM as a resource (`dspy4s/core/runtime/runner.js`, MIT, adapted by upstream from Simon
  * Willison's Pyodide-in-Deno TIL); this class is the JSON-RPC 2.0 client speaking to it over the subprocess's
  * stdin/stdout, line by line. Code runs with no host filesystem / network / environment access except what the
  * `enable*` allowlists grant via Deno permission flags.
  *
  * Capabilities beyond [[SubprocessPythonInterpreter]] (the unsandboxed `python3 -c` impl):
  *   - '''Sandboxed''': untrusted LM-generated code cannot touch the host.
  *   - '''Stateful REPL''': globals persist across [[execute]] calls (one long-lived Pyodide instance).
  *   - '''Variable injection''': `execute(code, variables)` defines host values as Python variables first.
  *   - '''Host tools''': [[SandboxTool]]s are callable BY NAME from sandboxed code; the call bridges back to the
  *     host mid-execution (the seam RLM's `llm_query` uses).
  *   - '''SUBMIT''': sandboxed code calls `SUBMIT(field=value, ...)` to terminate with a structured result,
  *     surfaced as [[CodeResult.finalOutput]] (the submitted object as JSON).
  *
  * Error split (per [[CodeInterpreterErrors]]): user-code errors (`NameError`, `SyntaxError`, …) come back as a
  * `Right(CodeResult)` with `exitCode = 1` and the message in `stderr` — the interpreter stays usable. A
  * `Left(RuntimeError)` means the interpreter ITSELF failed (Deno missing, process died, protocol breakdown).
  *
  * ==Deltas from Python==
  *   - Variables are injected uniformly as `name = json.loads("<json>")` rather than upstream's Python-literal
  *     assignments + a >100MB file path — same semantics (JSON-compatible values), one mechanism.
  *   - Not thread-safe (like upstream, which raises on cross-thread use); use one instance per thread.
  *
  * '''First run downloads Pyodide''' from npm into Deno's cache (network needed once); subsequent runs are
  * offline. Prerequisite: a `deno` executable on PATH (or pass `denoCommand`).
  *
  * @param tools               host functions callable from sandboxed code (registered once, lazily)
  * @param outputFields        output names (+ optional Python types) for the typed `SUBMIT(...)` signature
  * @param enableReadPaths     host files/dirs the sandbox may read (mounted under `/sandbox/<basename>`)
  * @param enableWritePaths    host files/dirs the sandbox may write (also mounted; synced back when `syncFiles`)
  * @param enableEnvVars       environment variable names visible inside the sandbox
  * @param enableNetworkAccess domains/IPs the sandbox may reach
  * @param syncFiles           sync write-path changes back to the host after each successful execute
  * @param denoCommand         full launch command override (replaces the computed `deno run --allow-… runner.js`)
  */
final class DenoPyodideInterpreter(
    tools: Vector[SandboxTool] = Vector.empty,
    outputFields: Vector[DenoPyodideInterpreter.OutputField] = Vector.empty,
    enableReadPaths: Vector[String] = Vector.empty,
    enableWritePaths: Vector[String] = Vector.empty,
    enableEnvVars: Vector[String] = Vector.empty,
    enableNetworkAccess: Vector[String] = Vector.empty,
    syncFiles: Boolean = true,
    denoCommand: Option[Vector[String]] = None
) extends ReplCodeInterpreter:
  import DenoPyodideInterpreter.*

  private var process: Option[Process]       = None
  private var stdin: Option[BufferedWriter]  = None
  private var stdout: Option[BufferedReader] = None
  private var stderrReader: Option[BufferedReader] = None
  private var requestId                      = 0
  private var toolsRegistered                = false
  private var filesMounted                   = false

  override def execute(code: String): Either[DspyError, CodeResult] = execute(code, Map.empty)

  /** Like [[execute]], but first defines each of `variables` as a Python variable in the sandbox (JSON-compatible
    * values only). Variable names must be plain Python identifiers (and not `json`, which the injection uses). */
  override def execute(code: String, variables: Map[String, DynamicValue]): Either[DspyError, CodeResult] =
    for
      injected <- injectVariables(code, variables)
      _        <- ensureProcess()
      _        <- mountFiles()
      _        <- registerTools()
      result   <- executeRequest(injected)
    yield result

  override def close(): Unit =
    stdin.foreach { w =>
      try
        w.write(encodeNotification("shutdown", None))
        w.newLine()
        w.flush()
        w.close()
      catch case NonFatal(_) => ()
    }
    process.foreach { p =>
      if !p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) then { val _ = p.destroyForcibly() }
    }
    process = None
    stdin = None
    stdout = None
    stderrReader = None
    toolsRegistered = false
    filesMounted = false

  // ── Process lifecycle ───────────────────────────────────────────────────────────────────────────────────────

  private def ensureProcess(): Either[DspyError, Unit] =
    if process.exists(_.isAlive) then Right(())
    else
      toolsRegistered = false
      filesMounted = false
      val command = denoCommand.getOrElse(buildCommand())
      try
        val p = new ProcessBuilder(command*).start()
        process = Some(p)
        stdin = Some(new BufferedWriter(new OutputStreamWriter(p.getOutputStream, StandardCharsets.UTF_8)))
        stdout = Some(new BufferedReader(new InputStreamReader(p.getInputStream, StandardCharsets.UTF_8)))
        stderrReader = Some(new BufferedReader(new InputStreamReader(p.getErrorStream, StandardCharsets.UTF_8)))
        healthCheck()
      catch
        case _: java.io.IOException =>
          process = None
          Left(RuntimeError(
            CodeInterpreterErrors.Interpreter,
            "Deno executable not found. Install Deno (https://docs.deno.com/runtime/getting_started/installation/), e.g. `brew install deno`."
          ))

  private def buildCommand(): Vector[String] =
    val runner    = runnerPath.toString
    val readPaths = (Vector(runner) ++ denoCacheDir.toVector ++ enableReadPaths ++ enableWritePaths).map(canonical)
    val args      = Vector.newBuilder[String]
    args += "deno"
    args += "run"
    args += s"--allow-read=${readPaths.mkString(",")}"
    if enableEnvVars.nonEmpty then args += s"--allow-env=${enableEnvVars.mkString(",")}"
    if enableNetworkAccess.nonEmpty then args += s"--allow-net=${enableNetworkAccess.mkString(",")}"
    if enableWritePaths.nonEmpty then args += s"--allow-write=${enableWritePaths.map(canonical).mkString(",")}"
    args += canonical(runner)
    if enableEnvVars.nonEmpty then args += enableEnvVars.mkString(",") // runner.js reads Deno.args[0]
    args.result()

  private def healthCheck(): Either[DspyError, Unit] =
    executeRequest("print(1+1)").flatMap { result =>
      if result.stdout.trim == "2" then Right(())
      else Left(RuntimeError(CodeInterpreterErrors.Interpreter, s"Sandbox health check failed: ${result.stdout}"))
    }

  // ── Setup requests (mounts, tool/output registration) ──────────────────────────────────────────────────────

  private def mountFiles(): Either[DspyError, Unit] =
    if filesMounted then Right(())
    else
      val paths = enableReadPaths ++ enableWritePaths
      val outcome = paths.foldLeft[Either[DspyError, Unit]](Right(())) { (acc, path) =>
        acc.flatMap(_ => mountOne(path))
      }
      outcome.map(_ => filesMounted = true)

  private def mountOne(path: String): Either[DspyError, Unit] =
    val missing = !Files.exists(Paths.get(path))
    if missing && !enableWritePaths.contains(path) then
      Left(RuntimeError(CodeInterpreterErrors.Interpreter, s"Cannot mount non-existent file: $path"))
    else
      if missing then { val _ = Files.createFile(Paths.get(path)) }
      val params = DynamicValues.record(
        "host_path"    := canonical(path),
        "virtual_path" := s"/sandbox/${Paths.get(path).getFileName}"
      )
      sendRequest("mount_file", params).map(_ => ())

  private def registerTools(): Either[DspyError, Unit] =
    if toolsRegistered || (tools.isEmpty && outputFields.isEmpty) then
      toolsRegistered = true
      Right(())
    else
      val toolsInfo = tools.map { tool =>
        DynamicValues.record(
          "name" := tool.name,
          "parameters" -> DynamicValue.Sequence(zio.blocks.chunk.Chunk.from(tool.parameters.map { p =>
            val fields = Vector("name" := p.name) ++ p.pythonType.map(t => "type" := t).toVector
            DynamicValues.recordFromEntries(fields): DynamicValue
          }))
        )
      }
      val outputsInfo = outputFields.map { f =>
        val fields = Vector("name" := f.name) ++ f.pythonType.map(t => "type" := t).toVector
        DynamicValues.recordFromEntries(fields): DynamicValue
      }
      val entries = Vector.newBuilder[(String, DynamicValue)]
      if tools.nonEmpty then entries += ("tools" -> DynamicValue.Sequence(zio.blocks.chunk.Chunk.from(toolsInfo)))
      if outputFields.nonEmpty then entries += ("outputs" -> DynamicValue.Sequence(zio.blocks.chunk.Chunk.from(outputsInfo)))
      sendRequest("register", DynamicValues.recordFromEntries(entries.result())).map { _ =>
        toolsRegistered = true
      }

  // ── The execute request (with mid-flight tool-call handling) ───────────────────────────────────────────────

  private def executeRequest(code: String): Either[DspyError, CodeResult] =
    requestId += 1
    val id = requestId
    writeLine(encodeRequest("execute", DynamicValues.record("code" := code), id)).flatMap { _ =>
      readLoop(id).map {
        case ExecuteOutcome.Output(text)     => CodeResult(stdout = text, stderr = "", exitCode = 0)
        case ExecuteOutcome.Submitted(json)  => CodeResult(stdout = "", stderr = "", exitCode = 0, finalOutput = Some(json))
        case ExecuteOutcome.UserError(message) => CodeResult(stdout = "", stderr = message, exitCode = 1)
      }.map { result =>
        syncWriteFiles()
        result
      }
    }

  /** Read JSON-RPC lines until the response for `expectedId` arrives, servicing `tool_call` requests from the
    * sandbox along the way and skipping non-JSON noise (Pyodide package-loading messages), up to a cap. */
  private def readLoop(expectedId: Int): Either[DspyError, ExecuteOutcome] =
    var skipped = 0
    while skipped <= MaxSkippedLines do
      readLine() match
        case Left(err)   => return Left(err)
        case Right(line) =>
          decodeJson(line) match
            case None => skipped += 1
            case Some(msg: DynamicValue.Record) =>
              field(msg, "method").flatMap(asString) match
                case Some("tool_call") =>
                  handleToolCall(msg) match
                    case Left(err) => return Left(err)
                    case Right(()) => ()
                case Some(other) =>
                  return Left(RuntimeError(CodeInterpreterErrors.Interpreter, s"Unexpected sandbox request: $other"))
                case None =>
                  field(msg, "result") match
                    case Some(result: DynamicValue.Record) =>
                      if field(msg, "id").flatMap(asLong).contains(expectedId.toLong) then
                        field(result, "final") match
                          case Some(finalValue) => return Right(ExecuteOutcome.Submitted(encodeJson(finalValue)))
                          case None =>
                            val out = field(result, "output").flatMap(asString).getOrElse("")
                            return Right(ExecuteOutcome.Output(out))
                      else return Left(RuntimeError(CodeInterpreterErrors.Interpreter, "Response id mismatch"))
                    case _ =>
                      field(msg, "error") match
                        case Some(error: DynamicValue.Record) =>
                          // App-level errors (code -32000..-32099) are USER-CODE errors: the message/args carry
                          // the Python exception. Unsolicited errors (id null) are treated the same way.
                          val errType = field(error, "data").collect { case r: DynamicValue.Record => r }
                            .flatMap(field(_, "type")).flatMap(asString).getOrElse("Error")
                          val args = field(error, "data").collect { case r: DynamicValue.Record => r }
                            .flatMap(field(_, "args")).map(DynamicValues.renderText)
                          val message = field(error, "message").flatMap(asString).filter(_.nonEmpty)
                          return Right(ExecuteOutcome.UserError(s"$errType: ${args.orElse(message).getOrElse("Unknown error")}"))
                        case _ =>
                          return Left(RuntimeError(CodeInterpreterErrors.Interpreter, s"Unexpected sandbox message: ${line.take(200)}"))
            case Some(_) => skipped += 1
    Left(RuntimeError(CodeInterpreterErrors.Interpreter, s"Too many non-JSON lines from sandbox (> $MaxSkippedLines)"))

  /** Service one `tool_call` request: invoke the host tool and reply with its result (or an app error). The
    * request `id` is echoed VERBATIM — the sandbox uses string ids (`"tc_…"`) for tool calls. */
  private def handleToolCall(msg: DynamicValue.Record): Either[DspyError, Unit] =
    val callId = field(msg, "id").getOrElse(DynamicValue.Null)
    val params = field(msg, "params").collect { case r: DynamicValue.Record => r }.getOrElse(DynamicValue.Record.empty)
    val name   = field(params, "name").flatMap(asString).getOrElse("")
    val kwargs = field(params, "kwargs").collect { case r: DynamicValue.Record => r }.getOrElse(DynamicValue.Record.empty)

    val reply = tools.find(_.name == name) match
      case None => encodeError(callId, ToolErrorCode, s"Unknown tool: $name")
      case Some(tool) =>
        tool.invoke(kwargs) match
          case Left(err) => encodeError(callId, ToolErrorCode, err.message)
          case Right(DynamicValue.Primitive(PrimitiveValue.String(s))) =>
            encodeResult(callId, DynamicValues.record("value" := s, "type" := "string"))
          case Right(other) =>
            encodeResult(callId, DynamicValues.record("value" := encodeJson(other), "type" := "json"))
    writeLine(reply)

  private def syncWriteFiles(): Unit =
    if syncFiles && enableWritePaths.nonEmpty then
      enableWritePaths.foreach { path =>
        val params = DynamicValues.record(
          "virtual_path" := s"/sandbox/${Paths.get(path).getFileName}",
          "host_path"    := canonical(path)
        )
        val _ = writeLine(encodeNotification("sync_file", Some(params)))
      }

  /** Send a setup request (mount/register) and wait for its response, skipping noise. Tool calls cannot occur
    * during setup, so a plain matching-id read suffices. */
  private def sendRequest(method: String, params: DynamicValue.Record): Either[DspyError, DynamicValue.Record] =
    requestId += 1
    val id = requestId
    writeLine(encodeRequest(method, params, id)).flatMap(_ => readSetupResponse(method, id))

  private def readSetupResponse(method: String, id: Int): Either[DspyError, DynamicValue.Record] =
    var skipped = 0
    while skipped <= MaxSkippedLines do
      readLine() match
        case Left(err)   => return Left(err)
        case Right(line) =>
          decodeJson(line) match
            case Some(msg: DynamicValue.Record) if field(msg, "id").flatMap(asLong).contains(id.toLong) =>
              field(msg, "error") match
                case Some(error: DynamicValue.Record) =>
                  val message = field(error, "message").flatMap(asString).getOrElse("Unknown error")
                  return Left(RuntimeError(CodeInterpreterErrors.Interpreter, s"Sandbox '$method' failed: $message"))
                case _ =>
                  return Right(field(msg, "result").collect { case r: DynamicValue.Record => r }.getOrElse(DynamicValue.Record.empty))
            case _ => skipped += 1
    Left(RuntimeError(CodeInterpreterErrors.Interpreter, s"No response to '$method'"))

  // ── Low-level IO ────────────────────────────────────────────────────────────────────────────────────────────

  private def writeLine(line: String): Either[DspyError, Unit] =
    stdin match
      case None => Left(RuntimeError(CodeInterpreterErrors.Interpreter, "Sandbox process is not running"))
      case Some(w) =>
        try
          w.write(line)
          w.newLine()
          w.flush()
          Right(())
        catch case NonFatal(e) => Left(RuntimeError(CodeInterpreterErrors.Interpreter, s"Failed writing to sandbox: ${e.getMessage}"))

  private def readLine(): Either[DspyError, String] =
    stdout match
      case None => Left(RuntimeError(CodeInterpreterErrors.Interpreter, "Sandbox process is not running"))
      case Some(r) =>
        val line = try r.readLine() catch case NonFatal(_) => null
        if line != null then Right(line)
        else
          val exited = process.exists(p => !p.isAlive)
          val stderr = drainStderr()
          Left(RuntimeError(
            CodeInterpreterErrors.Interpreter,
            if exited then s"Deno exited unexpectedly: ${stderr.takeRight(500)}" else "No response from sandbox"
          ))

  private def drainStderr(): String =
    stderrReader match
      case None => ""
      case Some(r) =>
        val sb = new StringBuilder
        try while r.ready() do { val _ = sb.append(r.readLine()).append('\n') }
        catch case NonFatal(_) => ()
        sb.toString

object DenoPyodideInterpreter:

  /** An output field of the typed `SUBMIT(...)` signature (Python DSPy's `output_fields`). */
  final case class OutputField(name: String, pythonType: Option[String] = None)

  private val MaxSkippedLines = 100
  private val ToolErrorCode   = -32008 // upstream's CodeInterpreterError app-error code

  private val PythonKeywords: Set[String] = Set(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def",
    "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
    "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield"
  )
  private val IdentifierPattern = "^[A-Za-z_][A-Za-z0-9_]*$".r

  /** Prepend `name = json.loads("<json>")` assignments for each variable (plus `import json`). One uniform
    * JSON-based mechanism vs upstream's literal/file split; same JSON-compatible value semantics. */
  private def injectVariables(code: String, variables: Map[String, DynamicValue]): Either[DspyError, String] =
    if variables.isEmpty then Right(code)
    else
      val invalid = variables.keys.find(k => !IdentifierPattern.matches(k) || PythonKeywords.contains(k) || k == "json")
      invalid match
        case Some(k) => Left(RuntimeError(CodeInterpreterErrors.Interpreter, s"Invalid variable name: '$k'"))
        case None =>
          val assignments = variables.toVector.map { case (name, value) =>
            // Double JSON-encoding: the inner JSON text becomes a valid Python string literal.
            s"$name = json.loads(${encodeJson(DynamicValues.fromAny(encodeJson(value)))})"
          }
          Right((("import json" +: assignments) :+ code).mkString("\n"))

  // ── Runner resource + Deno discovery ────────────────────────────────────────────────────────────────────────

  /** Extract the vendored `runner.js` to a temp file once per JVM (Deno needs a real path it can --allow-read). */
  private lazy val runnerPath: Path =
    val stream = Option(getClass.getResourceAsStream("/dspy4s/core/runtime/runner.js"))
      .getOrElse(throw new IllegalStateException("runner.js resource missing from dspy4s-core"))
    val file = Files.createTempFile("dspy4s-runner", ".js")
    try Files.write(file, stream.readAllBytes())
    finally stream.close()
    file.toFile.deleteOnExit()
    file

  /** Deno's cache directory (`DENO_DIR` or `deno info --json`), allow-read'd so Pyodide can load its files. */
  private lazy val denoCacheDir: Option[String] =
    sys.env.get("DENO_DIR").orElse {
      try
        val p   = new ProcessBuilder("deno", "info", "--json").start()
        val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        if p.waitFor() == 0 then
          decodeJson(out).collect { case r: DynamicValue.Record => r }.flatMap(field(_, "denoDir")).flatMap(asString)
        else None
      catch case NonFatal(_) => None
    }

  private def canonical(path: String): String =
    try Paths.get(path).toRealPath().toString
    catch case NonFatal(_) => Paths.get(path).toAbsolutePath.normalize().toString

  private enum ExecuteOutcome:
    case Output(text: String)
    case Submitted(json: String)
    case UserError(message: String)

  // ── JSON-RPC encoding/decoding on the dynamic codec ─────────────────────────────────────────────────────────

  private lazy val jsonCodec = Schema.dynamic.jsonCodec

  private def encodeJson(value: DynamicValue): String =
    new String(jsonCodec.encode(value), StandardCharsets.UTF_8)

  private def decodeJson(line: String): Option[DynamicValue] =
    val trimmed = line.trim
    if !trimmed.startsWith("{") then None
    else jsonCodec.decode(trimmed.getBytes(StandardCharsets.UTF_8)).toOption

  private def encodeRequest(method: String, params: DynamicValue.Record, id: Int): String =
    encodeJson(DynamicValues.record("jsonrpc" := "2.0", "method" := method, "params" -> params, "id" := id))

  private def encodeNotification(method: String, params: Option[DynamicValue.Record]): String =
    val entries = Vector[(String, DynamicValue)]("jsonrpc" := "2.0", "method" := method) ++
      params.map(p => ("params", p: DynamicValue)).toVector
    encodeJson(DynamicValues.recordFromEntries(entries))

  private def encodeResult(id: DynamicValue, result: DynamicValue.Record): String =
    encodeJson(DynamicValues.recordFromEntries(Vector[(String, DynamicValue)](
      "jsonrpc" := "2.0", "result" -> result, "id" -> id
    )))

  private def encodeError(id: DynamicValue, code: Int, message: String): String =
    encodeJson(DynamicValues.recordFromEntries(Vector[(String, DynamicValue)](
      "jsonrpc" := "2.0",
      "error"   -> DynamicValues.record("code" := code, "message" := message),
      "id"      -> id
    )))

  private def field(record: DynamicValue.Record, name: String): Option[DynamicValue] =
    DynamicValues.recordGet(record, name)

  private def asString(dv: DynamicValue): Option[String] = dv match
    case DynamicValue.Primitive(PrimitiveValue.String(s)) => Some(s)
    case _                                                => None

  /** Tolerant number extraction: the dynamic JSON codec may decode numbers as Int/Long/Double/BigDecimal. */
  private def asLong(dv: DynamicValue): Option[Long] = dv match
    case DynamicValue.Primitive(p) =>
      p match
        case PrimitiveValue.Int(n)        => Some(n.toLong)
        case PrimitiveValue.Long(n)       => Some(n)
        case PrimitiveValue.Double(n)     => Some(n.toLong)
        case PrimitiveValue.Float(n)      => Some(n.toLong)
        case PrimitiveValue.BigDecimal(n) => Some(n.toLong)
        case PrimitiveValue.BigInt(n)     => Some(n.toLong)
        case PrimitiveValue.Short(n)      => Some(n.toLong)
        case PrimitiveValue.Byte(n)       => Some(n.toLong)
        case _                            => None
    case _ => None
