package dspy4s.core.runtime

import dspy4s.core.contracts.CodeInterpreterErrors.failure
import dspy4s.core.contracts.ReplCodeInterpreter
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.DynamicValues.asLong
import dspy4s.core.contracts.DynamicValues.asString
import dspy4s.core.contracts.DynamicValues.encodeJson
import dspy4s.core.contracts.DynamicValues.recordGet as field
import dspy4s.core.contracts.InterpreterOperation
import dspy4s.core.contracts.SandboxOutputField
import dspy4s.core.contracts.SandboxTool
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.InterpreterSupport.describe
import dspy4s.core.runtime.InterpreterSupport.exitedIn
import dspy4s.core.runtime.InterpreterSupport.nameAndType
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Sandboxed [[CodeInterpreter]]: Python via Pyodide (WASM) inside a Deno subprocess. It is a port of Python DSPy's
  * `PythonInterpreter` (`dspy/primitives/python_interpreter.py`). The sandbox side is upstream's `runner.js`, vendored
  * as a resource with three changed comment lines (`dspy4s/core/runtime/runner.js`, MIT, adapted by upstream from Simon
  * Willison's Pyodide-in-Deno TIL); this class is the JSON-RPC 2.0 client speaking to it over the subprocess's
  * stdin/stdout, line by line. Code runs with no host filesystem / network / environment access except what the
  * `enable*` allowlists grant via Deno permission flags.
  *
  * Capabilities beyond [[SubprocessPythonInterpreter]] (the unsandboxed `python3 -c` impl):
  *   - '''Sandboxed''': untrusted LM-generated code cannot touch the host.
  *   - '''Stateful REPL''': globals persist across [[execute]] calls (one long-lived Pyodide instance).
  *   - '''Variable injection''': `execute(code, variables)` defines host values as Python variables first.
  *   - '''Host tools''': [[SandboxTool]]s are callable BY NAME from sandboxed code; the call bridges back to the host
  *     mid-execution (RLM's `llm_query` uses this mechanism).
  *   - '''SUBMIT''': sandboxed code calls `SUBMIT(field=value, ...)` to terminate with a structured result, surfaced as
  *     [[CodeResult.finalOutput]] (the submitted object as JSON).
  *
  * Error split (per [[CodeInterpreterErrors]]): user-code errors (`NameError`, `SyntaxError`, …) come back as a
  * `Right(CodeResult)` with `exitCode = 1` and the message in `stderr`. The interpreter stays usable. A
  * `Left(RuntimeError)` means the interpreter ITSELF failed (Deno missing, process died, protocol breakdown).
  *
  * ==The session==
  * One interpreter is one session: one sandbox process and its REPL state. The session ends when [[close]] runs, or
  * when the process or the protocol fails (upstream `_raise_terminal_error`). Such a failure stops the process. The
  * process holds the REPL state, so a session does not start again: each later [[execute]] gives a `Left` that tells
  * the caller to create a new interpreter. These failures do not end the session: a user-code error, a tool error, an
  * invalid variable name, a variable value that the JSON writer cannot encode, a mount file that is absent, a write
  * path that the client cannot create, a mount collision, a recursive call, and a Deno version that is not supported.
  *
  * A thread reads the standard error of the process all the time and keeps its last characters for the failure
  * messages. Thus a process that writes much text to its standard error does not stop when the pipe is full.
  *
  * ==Deltas from Python==
  *   - Variables are injected uniformly as `name = json.loads("<json>")` rather than upstream's Python-literal
  *     assignments + a >100MB file path. The semantics are the same (JSON-compatible values), with one mechanism.
  *   - Not thread-safe: one [[execute]] runs at a time. A call from a different thread while a call is in progress
  *     gives a `Left`, and so does a tool that calls [[execute]] on its interpreter. Upstream binds an interpreter to
  *     the thread of its first call until `shutdown`. dspy4s permits calls from different threads when they do not
  *     overlap.
  *   - Upstream makes the checks of the mounts and of the write paths in its constructor. A Scala constructor cannot
  *     return an `Either`, so [[execute]] gives the `Left` before the process starts.
  *
  * ==Callback events==
  * The interpreter opens an interpreter scope (upstream `on_interpreter_<operation>_start`) for each of these
  * operations: an `Execute` scope for each [[execute]] call, a `Startup` scope inside the first `Execute` when the
  * process starts, a `ToolCall` scope for each host tool call from the sandbox, and a `Shutdown` scope for each
  * [[close]] call. A `ToolCall` scope holds the `ToolStartEvent` / `ToolEndEvent` pair of the tool.
  *
  * '''First run downloads Pyodide''' from npm into Deno's cache (network needed once); subsequent runs are offline.
  * Prerequisite: a `deno` executable on PATH, version 2.x (or pass `denoCommand`, which gets no version check).
  *
  * @param tools
  *   host functions callable from sandboxed code (registered once, lazily)
  * @param outputFields
  *   output names (+ optional Python types) for the `SUBMIT(...)` signature
  * @param enableReadPaths
  *   host files/dirs the sandbox may read (mounted under `/sandbox/<basename>`). Two different host files cannot have
  *   the same basename.
  * @param enableWritePaths
  *   host files/dirs the sandbox may write (also mounted; synced back when `syncFiles`). A write path cannot overlap
  *   the runner file or the Deno cache directory.
  * @param enableEnvVars
  *   environment variable names visible inside the sandbox
  * @param enableNetworkAccess
  *   domains/IPs the sandbox may reach
  * @param syncFiles
  *   sync write-path changes back to the host after each successful execute. The client sends the sync as a
  *   notification and does not wait for it, as upstream. A host file is complete after [[close]] returns; a read before
  *   that can see the file while the sandbox writes it.
  * @param denoCommand
  *   full launch command override (replaces the computed `deno run --allow-… runner.js`)
  */
final class DenoPyodideInterpreter(
    tools              : Vector[SandboxTool]        = Vector.empty,
    outputFields       : Vector[SandboxOutputField] = Vector.empty,
    enableReadPaths    : Vector[String]             = Vector.empty,
    enableWritePaths   : Vector[String]             = Vector.empty,
    enableEnvVars      : Vector[String]             = Vector.empty,
    enableNetworkAccess: Vector[String]             = Vector.empty,
    syncFiles          : Boolean                    = true,
    denoCommand        : Option[Vector[String]]     = None
) extends ReplCodeInterpreter:
  import DenoPyodideInterpreter.*
  import DenoPyodideLaunch.canonical
  import DenoPyodideLaunch.sandboxPath
  import DenoPyodideProtocol.*

  // The `interpreterName` of the callback events.
  private val interpreterName: String = getClass.getSimpleName
  // `close` can run on a different thread while a call is in progress, so the session state is one atomic value.
  private val session = new AtomicReference[Session](Session.NotStarted)
  // The thread of the call in progress holds this lock. The state below belongs to that thread.
  private val executeLock     = new ReentrantLock()
  private var toolsRegistered = false
  private var filesMounted    = false
  // The last out-of-band message of the current request (upstream `_last_diagnostic`).
  private var lastDiagnostic: Option[String] = None
  // Single-entry, identity-keyed cache of the variable-assignment prelude. The prelude depends only on the
  // `variables` map, and RLM's iteration loop passes the SAME immutable map on every execute while the values can
  // be an entire long context. With the cache, the serialization runs once per map instance instead of once per call.
  // The prelude is still prepended to (and re-run with) every code block, so re-assignment semantics are unchanged.
  private var cachedPrelude: Option[(Map[String, DynamicValue], String)] = None

  /** Like [[execute]], but first defines each of `variables` as a Python variable in the sandbox (JSON-compatible
    * values only). Variable names must be plain Python identifiers (and not `json`, which the injection uses).
    * Variables are re-assigned at the top of every call's code block (upstream parity), so a sandbox-side mutation of
    * an injected variable does not survive to the next `execute`.
    *
    * One call runs at a time. A tool that calls `execute` on its interpreter gets a `Left`, and the outer call
    * continues. A call from a different thread while a call is in progress gets a `Left` too.
    */
  override def execute(code: String, variables: Map[String, DynamicValue]): Either[DspyError, CodeResult] =
    val inputs = InterpreterSupport.executeInputs(code, variables)
    CallbackDispatcher.withInterpreter(interpreterName, InterpreterOperation.Execute, inputs) {
      if executeLock.isHeldByCurrentThread then
        failure("DenoPyodideInterpreter cannot execute recursively from one of its tools.")
      else if !executeLock.tryLock() then
        failure(
          "DenoPyodideInterpreter is not thread-safe: a call is in progress on a different thread. Create a separate interpreter for each thread."
        )
      else
        try
          for
            _       <- checkSessionActive()
            prelude <- injectionPrelude(variables)
            _       <- ensureProcess()
            _       <- mountFiles()
            _       <- registerTools()
            result  <- executeRequest(if prelude.isEmpty then code else s"$prelude\n$code")
          yield result
        finally executeLock.unlock()
    }

  private def injectionPrelude(variables: Map[String, DynamicValue]): Either[DspyError, String] =
    cachedPrelude match
      case Some((cached, prelude)) if cached eq variables => Right(prelude)
      case _                                              => DenoPyodideVariables.prelude(variables).map { prelude =>
          cachedPrelude = Some((variables, prelude))
          prelude
        }

  /** Ends the session and stops the sandbox process. The session does not start again. A different thread can call
    * `close` while a call is in progress. That call then gives a `Left`. Each call opens a `Shutdown` scope, also when
    * the session has ended (upstream `shutdown`).
    */
  override def close(): Unit =
    val _ =
      CallbackDispatcher.withInterpreter(interpreterName, InterpreterOperation.Shutdown, DynamicValue.Record.empty) {
        endSession(shutdown = true)
        Right(())
      }

  // ── Session lifecycle ───────────────────────────────────────────────────────────────────────────────────────

  /** End the session and give the failure (upstream `_raise_terminal_error`). The process or the protocol failed, so
    * the REPL state is lost or not known.
    */
  private def terminal[A](message: String): Either[DspyError, A] =
    endSession(shutdown = false)
    failure(message)

  private def checkSessionActive(): Either[DspyError, Unit] =
    if session.get() == Session.Ended then
      failure("The DenoPyodideInterpreter session has ended. Create a new interpreter for a new session.")
    else Right(())

  /** Move the session to its end and stop its process. No effect on a session that ended. */
  private def endSession(shutdown: Boolean): Unit =
    session.getAndSet(Session.Ended) match
      case running: Session.Running => stop(running, shutdown)
      case _                        => ()

  /** Stop the process of a session. `shutdown` asks the runner to stop. Without it, the process gets a stop signal. The
    * client kills a process that does not exit in `StopTimeoutMillis`.
    */
  private def stop(running: Session.Running, shutdown: Boolean): Unit =
    if shutdown then
      try
        running.stdin.write(encodeNotification("shutdown", None))
        running.stdin.newLine()
        running.stdin.flush()
        running.stdin.close()
      catch case NonFatal(_) => ()
    else running.process.destroy()
    if !exitedIn(running.process, StopTimeoutMillis) then
      val _ = running.process.destroyForcibly()

  // ── Process lifecycle ───────────────────────────────────────────────────────────────────────────────────────

  private def ensureProcess(): Either[DspyError, Unit] =
    session.get() match
      case Session.NotStarted =>
        // Upstream `start`: the start of the process is a `Startup` scope inside the `Execute` scope.
        CallbackDispatcher.withInterpreter(interpreterName, InterpreterOperation.Startup, DynamicValue.Record.empty)(
          startProcess()
        )
      case running: Session.Running =>
        if running.process.isAlive then Right(())
        else terminal(ioFailure(running, "Sandbox process is not running"))
      case Session.Ended => checkSessionActive()

  private def startProcess(): Either[DspyError, Unit] =
    for
      _       <- DenoPyodideLaunch.checkMounts(enableReadPaths ++ enableWritePaths)
      builder <- processBuilder()
      _       <- spawn(builder)
      _       <- healthCheck()
    yield ()

  /** The process to start. The checks of the write paths and of the Deno version are for the default launch command
    * only (upstream `_uses_default_deno_command`).
    */
  private def processBuilder(): Either[DspyError, ProcessBuilder] =
    denoCommand.filter(_.nonEmpty) match
      case Some(command) => Right(new ProcessBuilder(command*))
      case None          =>
        DenoPyodideLaunch.defaultProcess(enableReadPaths, enableWritePaths, enableEnvVars, enableNetworkAccess)

  /** Start the process and the thread that reads its standard error. A launch command that cannot run gives a `Left`
    * with the cause.
    */
  private def spawn(builder: ProcessBuilder): Either[DspyError, Unit] =
    try
      val p       = builder.start()
      val stderr  = new StderrTail(DiagnosticLength)
      val _       = InterpreterSupport.startDaemon(s"dspy4s-deno-stderr-${p.pid()}")(stderr.readAll(p.getErrorStream))
      val running = new Session.Running(
        process = p,
        stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream, StandardCharsets.UTF_8)),
        stdout = new BufferedReader(new InputStreamReader(p.getInputStream, StandardCharsets.UTF_8)),
        stderr = stderr
      )
      if session.compareAndSet(Session.NotStarted, running) then Right(())
      else
        // `close` ran on a different thread while the process started.
        stop(running, shutdown = false)
        checkSessionActive()
    catch
      case error: IOException => failure(startFailure(error))
      case NonFatal(error)    => failure(s"Unable to start the sandbox process: ${describe(error)}")

  /** Upstream `_health_check`: a setup request, so an error response ends the session and no file sync follows. */
  private def healthCheck(): Either[DspyError, Unit] =
    sendRequest("execute", DynamicValues.record("code" := "print(1+1)")).flatMap { result =>
      if field(result, "output").flatMap(asString).exists(_.trim == "2") then Right(())
      else terminal(s"Sandbox health check failed: ${snippet(encodeJson(result))}")
    }

  // ── Setup requests (mounts, tool/output registration) ──────────────────────────────────────────────────────

  private def mountFiles(): Either[DspyError, Unit] =
    if filesMounted then Right(())
    else
      val paths   = enableReadPaths ++ enableWritePaths
      val outcome = paths.foldLeft[Either[DspyError, Unit]](Right(())) { (acc, path) =>
        acc.flatMap(_ => mountOne(path))
      }
      outcome.map(_ => filesMounted = true)

  private def mountOne(path: String): Either[DspyError, Unit] =
    val missing = !Files.exists(Paths.get(path))
    if missing && !enableWritePaths.contains(path) then failure(s"Cannot mount non-existent file: $path")
    else
      val created = if missing then createWriteFile(path) else Right(())
      created.flatMap { _ =>
        val params = DynamicValues.record("host_path" := canonical(path), "virtual_path" := sandboxPath(path))
        sendRequest("mount_file", params).map(_ => ())
      }

  /** Create the absent file of a write path before the mount. A file that the client cannot create, for example in a
    * directory that does not exist, gives a `Left` with the cause. The session continues.
    */
  private def createWriteFile(path: String): Either[DspyError, Unit] =
    try
      val _ = Files.createFile(Paths.get(path))
      Right(())
    catch case NonFatal(error) => failure(s"Cannot create the write path $path: ${describe(error)}")

  private def registerTools(): Either[DspyError, Unit] =
    if toolsRegistered || (tools.isEmpty && outputFields.isEmpty) then
      toolsRegistered = true
      Right(())
    else
      val toolsInfo = tools.map { tool =>
        DynamicValues.record(
          "name"       := tool.name,
          "parameters" -> DynamicValue.Sequence(Chunk.from(tool.parameters.map(p => nameAndType(p.name, p.pythonType))))
        ): DynamicValue
      }
      val outputsInfo = outputFields.map(f => nameAndType(f.name, f.pythonType))
      val entries     = Option.when(tools.nonEmpty)("tools" -> DynamicValue.Sequence(Chunk.from(toolsInfo))).toVector ++
        Option.when(outputFields.nonEmpty)("outputs" -> DynamicValue.Sequence(Chunk.from(outputsInfo))).toVector
      sendRequest("register", DynamicValues.recordFromEntries(entries)).map { _ =>
        toolsRegistered = true
      }

  // ── The execute request (with mid-flight tool-call handling) ───────────────────────────────────────────────

  private def executeRequest(code: String): Either[DspyError, CodeResult] =
    val id = nextRequestId()
    for
      _      <- writeLine(encodeRequest("execute", DynamicValues.record("code" := code), id))
      result <- nextMessage(serveTools = true).flatMap {
                  case Some((msg, line)) => executeOutcome(msg, id, line)
                  case None              => terminal(s"Too many skipped lines from sandbox (> $MaxSkippedLines)")
                }
      _ <- syncWriteFiles()
    yield result

  /** A new random id for a request (upstream `_next_request_id`). The diagnostic of the request before it is no longer
    * current.
    */
  private def nextRequestId(): String =
    lastDiagnostic = None
    newRequestId()

  /** Read JSON-RPC lines until a message arrives that is not out of band: the response to a request, or a request of
    * the sandbox. With `serveTools`, the reader serves each `tool_call` request along the way. Non-JSON noise (Pyodide
    * package-loading messages) and out-of-band messages are skipped. Each skipped line counts against one
    * `MaxSkippedLines` bound per request, so a flood cannot block the read forever. A tool call does not count. `None`
    * when the bound is exceeded.
    */
  @tailrec
  private def nextMessage(
      serveTools: Boolean,
      skipped   : Int = 0
  ): Either[DspyError, Option[(DynamicValue.Record, String)]] =
    if skipped > MaxSkippedLines then Right(None)
    else
      readLine() match
        case Left(err)   => Left(err)
        case Right(line) => decodeRecord(line) match
            case None                                       => nextMessage(serveTools, skipped + 1)
            case Some(msg) if serveTools && isToolCall(msg) =>
              handleToolCall(msg) match
                case Left(err) => Left(err)
                case Right(()) => nextMessage(serveTools, skipped)
            case Some(msg) => skipOutOfBand(msg) match
                case Left(err)    => Left(err)
                case Right(true)  => nextMessage(serveTools, skipped + 1)
                case Right(false) => Right(Some((msg, line)))

  private def isToolCall(msg: DynamicValue.Record): Boolean =
    field(msg, "method").flatMap(asString).contains("tool_call")

  /** Decode the response to the execute request `expectedId`. `msg` is not a tool call and is not out of band. A
    * response with a different id is a protocol breakdown, and so is a result or an error that is not an object.
    */
  private def executeOutcome(
      msg       : DynamicValue.Record,
      expectedId: String,
      line      : String
  ): Either[DspyError, CodeResult] =
    field(msg, "method").flatMap(asString) match
      case Some(other) => terminal(s"Unexpected sandbox request: $other")
      case None        => (field(msg, "result"), field(msg, "error")) match
          case (None, None)                           => terminal(s"Unexpected sandbox message: ${snippet(line)}")
          case _ if !hasId(msg, expectedId)           => terminal(idMismatch(msg, expectedId))
          case (Some(result: DynamicValue.Record), _) => field(result, "final") match
              case Some(finalValue) =>
                Right(CodeResult(stdout = "", stderr = "", exitCode = 0, finalOutput = Some(encodeJson(finalValue))))
              case None =>
                val output = field(result, "output").flatMap(asString).getOrElse("")
                Right(CodeResult(stdout = output, stderr = "", exitCode = 0))
          case (Some(_), _)                             => terminal(s"Malformed execution result: ${snippet(line)}")
          case (None, Some(error: DynamicValue.Record)) => executionError(error, line)
          case (None, Some(_))                          => terminal(s"Malformed execution error: ${snippet(line)}")

  /** Decode an error response to the execute request. A code in the application range is a USER-CODE error: the
    * message/args carry the Python exception. Each other code is a failure of the sandbox, so the session ends. An
    * error without a code gets the code for an unknown application error, as upstream.
    */
  private def executionError(error: DynamicValue.Record, line: String): Either[DspyError, CodeResult] =
    val data = field(error, "data") match
      case None                              => Right(DynamicValue.Record.empty)
      case Some(record: DynamicValue.Record) => Right(record)
      case Some(_)                           => terminal(s"Malformed execution error data: ${snippet(line)}")
    data.flatMap { record =>
      val errType = field(record, "type").flatMap(asString).getOrElse("Error")
      val args    = field(record, "args").map(DynamicValues.renderText)
      val message = field(error, "message").flatMap(asString).filter(_.nonEmpty)
      val text    = s"$errType: ${args.orElse(message).getOrElse("Unknown error")}"
      val code    = field(error, "code").fold(Option(UnknownErrorCode))(asLong)
      if code.exists(isApplicationError) then Right(CodeResult(stdout = "", stderr = text, exitCode = 1))
      else terminal(s"Sandbox error: $text")
    }

  private def hasId(msg: DynamicValue.Record, expectedId: String): Boolean =
    field(msg, "id").flatMap(asString).contains(expectedId)

  private def idMismatch(msg: DynamicValue.Record, expectedId: String): String =
    val got = field(msg, "id").fold("no id")(encodeJson(_).take(DiagnosticLength))
    s"Response id mismatch: expected $expectedId, got $got"

  /** Consume `msg` when no request waits for it. `Right(true)`: the caller skips the message and counts it.
    * `Right(false)`: the message is a request or a response. `Left`: the sandbox reported a protocol error without an
    * id, so the response to the pending request does not arrive.
    */
  private def skipOutOfBand(msg: DynamicValue.Record): Either[DspyError, Boolean] =
    outOfBand(msg) match
      case OutOfBand.No           => Right(false)
      case OutOfBand.Notification =>
        lastDiagnostic = Some(notificationMessage(msg))
        Right(true)
      case OutOfBand.UnsolicitedError(message) =>
        lastDiagnostic = Some(message)
        RuntimeEnvironment.warn("DenoPyodideInterpreter", s"skipped an out-of-band sandbox error: $message")
        Right(true)
      case OutOfBand.ProtocolError(message) => terminal(s"Sandbox protocol error: $message")

  /** Service one `tool_call` request: invoke the host tool and reply with its result (or an app error). The reply
    * echoes the request `id` VERBATIM, because the sandbox uses string ids (`"tc_…"`) for tool calls. A tool that
    * throws also gets an error reply (upstream `_handle_tool_call`): the sandbox waits for a reply to each tool call,
    * so an exception that leaves `execute` here blocks the session.
    *
    * Each tool call is a `ToolCall` interpreter scope (upstream `invoke_tool`), also for an unknown tool. The inputs
    * are `tool` and `args`, and the output is the tool result.
    */
  private def handleToolCall(msg: DynamicValue.Record): Either[DspyError, Unit] =
    val callId = field(msg, "id").getOrElse(DynamicValue.Null)
    val params = field(msg, "params").collect { case r: DynamicValue.Record => r }.getOrElse(DynamicValue.Record.empty)
    val name   = field(params, "name").flatMap(asString).getOrElse("")
    val kwargs =
      field(params, "kwargs").collect { case r: DynamicValue.Record => r }.getOrElse(DynamicValue.Record.empty)

    // Same observability as ReAct's ToolExecutor: callbacks on the active context see every host-tool invocation as
    // a ToolStart/ToolEnd scope, inside the `ToolCall` interpreter scope. handleToolCall runs on the thread that
    // called `execute`, so the events land on (and nest under) the `Execute` scope. The dispatcher emits the end
    // events before it rethrows a tool exception, so the catch stays outside the scopes.
    val reply =
      try
        InterpreterSupport.invokeTool(interpreterName, tools, name, kwargs, Right(kwargs)) match
          case Left(err)                                               => encodeError(callId, ToolErrorCode, err.message)
          case Right(DynamicValue.Primitive(PrimitiveValue.String(s))) =>
            encodeResult(callId, DynamicValues.record("value" := s, "type" := "string"))
          case Right(other) =>
            encodeResult(callId, DynamicValues.record("value" := encodeJson(other), "type" := "json"))
      catch
        case NonFatal(error) =>
          encodeError(callId, ToolErrorCode, Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
    writeLine(reply)

  /** Tell the runner to copy each write path from the sandbox to the host. A write that fails ends the session. */
  private def syncWriteFiles(): Either[DspyError, Unit] =
    if !syncFiles then Right(())
    else
      enableWritePaths.foldLeft[Either[DspyError, Unit]](Right(())) { (acc, path) =>
        acc.flatMap { _ =>
          val params = DynamicValues.record("virtual_path" := sandboxPath(path), "host_path" := canonical(path))
          writeLine(encodeNotification("sync_file", Some(params)))
        }
      }

  /** Send a setup request (mount/register/health check) and wait for its response, skipping noise. Tool calls cannot
    * occur during setup, so a plain matching-id read suffices.
    */
  private def sendRequest(method: String, params: DynamicValue.Record): Either[DspyError, DynamicValue.Record] =
    val id = nextRequestId()
    for
      _      <- writeLine(encodeRequest(method, params, id))
      result <- nextMessage(serveTools = false).flatMap {
                  case Some((msg, line)) => setupOutcome(msg, method, id, line)
                  case None              => terminal(s"No response to '$method'")
                }
    yield result

  /** Decode the response to the setup request `id` (upstream `_send_request`). `msg` is not out of band. Each response
    * that is not a result with the expected id ends the session: the state of the sandbox is not known after it.
    */
  private def setupOutcome(
      msg   : DynamicValue.Record,
      method: String,
      id    : String,
      line  : String
  ): Either[DspyError, DynamicValue.Record] =
    if !hasId(msg, id) then terminal(idMismatch(msg, id))
    else
      field(msg, "error") match
        case Some(error: DynamicValue.Record) =>
          val message = field(error, "message").flatMap(asString).filter(_.nonEmpty).getOrElse(encodeJson(error))
          terminal(s"Sandbox '$method' failed: ${message.take(DiagnosticLength)}")
        case Some(_) => terminal(s"Malformed error response to '$method': ${snippet(line)}")
        case None    => field(msg, "result") match
            case Some(result: DynamicValue.Record) => Right(result)
            case _                                 => terminal(s"Unexpected response to '$method': ${snippet(line)}")

  // ── Low-level IO ────────────────────────────────────────────────────────────────────────────────────────────

  private def writeLine(line: String): Either[DspyError, Unit] =
    session.get() match
      case running: Session.Running =>
        try
          running.stdin.write(line)
          running.stdin.newLine()
          running.stdin.flush()
          Right(())
        catch
          case NonFatal(e) => terminal(ioFailure(running, s"Failed writing to sandbox: ${e.getMessage}"))
      case _ => terminal("Sandbox process is not running")

  private def readLine(): Either[DspyError, String] =
    session.get() match
      case running: Session.Running =>
        val line =
          try running.stdout.readLine()
          catch case NonFatal(_) => null
        if line != null then Right(line)
        else terminal(ioFailure(running, "No response from sandbox"))
      case _ => terminal("Sandbox process is not running")

  /** The failure text after the end of the sandbox output or after a write that failed. The pipes of a process close a
    * short time before the process state changes, so the check waits `ExitGraceMillis` for the exit. A process that
    * exited gives its exit code and the end of its standard error. The check waits `ExitGraceMillis` again at most for
    * the end of the standard error. The text ends with the last out-of-band message of the request, when one arrived
    * (upstream `_read_response_line`).
    */
  private def ioFailure(running: Session.Running, otherwise: String): String =
    val diagnostic = lastDiagnostic.fold("")(text => s" (last sandbox diagnostic: ${text.take(DiagnosticLength)})")
    if exitedIn(running.process, ExitGraceMillis) then
      val stderr = running.stderr.end(ExitGraceMillis)
      s"Deno exited (code ${running.process.exitValue()}): $stderr$diagnostic"
    else s"$otherwise$diagnostic"

private object DenoPyodideInterpreter:

  /** The time for a process to exit after the request to stop. After this time the client kills the process. */
  private val StopTimeoutMillis = 5000L

  /** The time for a process to exit after its output ended or after a write failed. After the exit, the same time limit
    * applies to the end of the standard error.
    */
  private val ExitGraceMillis = 1000L

  /** The length limit for text from the sandbox in a failure message. */
  private val DiagnosticLength = 500

  /** The length limit for a sandbox line or a result in a protocol failure message. */
  private val SnippetLength = 200

  private def snippet(text: String): String = text.take(SnippetLength)

  /** The number of characters of one read of the standard error. */
  private val ReadSize = 4096

  /** The failure text of a launch command that cannot run. The text has the message of the `IOException`, for example
    * "error=13, Permission denied". An executable that does not exist (`error=2`) also gets the install hint.
    */
  private def startFailure(error: IOException): String =
    val cause = describe(error)
    if cause.contains("error=2,") then
      s"Deno executable not found ($cause). Install Deno (https://docs.deno.com/runtime/getting_started/installation/), e.g. `brew install deno`."
    else s"Unable to start the sandbox process: $cause"

  /** The last characters of the standard error of a process. [[readAll]] reads the stream to its end on a daemon
    * thread. Thus a process that writes much text to its standard error does not stop when the pipe is full. The tail
    * keeps the last `limit` characters of the text, and not more than `2 * limit + ReadSize` characters in memory.
    */
  private final class StderrTail(limit: Int):
    // The holder of the monitor of this object reads and changes `text`.
    private val text = new java.lang.StringBuilder()
    private val done = new CountDownLatch(1)

    /** Read `stream` to its end and keep its last characters. The call closes the stream. */
    def readAll(stream: InputStream): Unit =
      val reader = new InputStreamReader(stream, StandardCharsets.UTF_8)
      val buffer = new Array[Char](ReadSize)
      try Iterator.continually(reader.read(buffer)).takeWhile(_ >= 0).foreach(count => append(buffer, count))
      catch case NonFatal(_) => ()
      finally
        try reader.close()
        catch case NonFatal(_) => ()
        done.countDown()

    private def append(buffer: Array[Char], count: Int): Unit =
      synchronized {
        val _ = text.append(buffer, 0, count)
        if text.length > 2 * limit then
          val _ = text.delete(0, text.length - limit)
      }

    /** The last `limit` characters of the stream. The call waits `millis` at most for the end of the stream. An
      * interrupt stops the wait, and the thread keeps its interrupt state. The text does not start with the second half
      * of a UTF-16 surrogate pair.
      */
    def end(millis: Long): String =
      try
        val _ = done.await(millis, TimeUnit.MILLISECONDS)
      catch case _: InterruptedException => Thread.currentThread().interrupt()
      val tail = synchronized(text.toString).takeRight(limit)
      if tail.headOption.exists(_.isLowSurrogate) then tail.drop(1) else tail

  /** The life of one sandbox session. A session goes from `NotStarted` to `Running` to `Ended`, or from `NotStarted` to
    * `Ended`. It does not go back.
    */
  private enum Session derives CanEqual:
    case NotStarted
    case Running(process: Process, stdin: BufferedWriter, stdout: BufferedReader, stderr: StderrTail)
    case Ended
