package dspy4s.core.runtime

import dspy4s.core.contracts.CodeInterpreterErrors
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.InterpreterOperation
import dspy4s.core.contracts.ReplCodeInterpreter
import dspy4s.core.contracts.ReplInterpreterFactory
import dspy4s.core.contracts.RuntimeDelta
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SandboxOutputField
import dspy4s.core.contracts.SandboxTool
import dspy4s.core.contracts.:=
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/** Runs Python in a persistent local subprocess (upstream `LocalInterpreter` in `dspy/primitives/local_interpreter.py`,
  * d58c3007e and 453823209). The worker script is the vendored upstream `local_interpreter_worker.py` (resource
  * `/dspy4s/core/runtime/local_interpreter_worker.py`), started as `<python> -I <script>`.
  *
  * This interpreter separates the generated code from the memory and the output of the JVM, but it is not a security
  * sandbox: the worker has the files, the environment, the credentials, the subprocesses, and the network of the user.
  *
  * Rules (upstream behavior):
  *   - The first `execute` starts the worker and waits 10 seconds at most for its `ready` message. A worker that does
  *     not start is a `Left`, and the session ends.
  *   - State, imports, functions, and variables persist from one `execute` to the next.
  *   - `execute(code, variables)` first defines `variables` (JSON values) in the namespace. A variable name that is not
  *     a Python identifier, that is a keyword, that is `SUBMIT` or `__builtins__`, or that is the name of a tool is a
  *     `Left` with no effect on the session.
  *   - The `stdout` of the result is the text of the value of the last expression of the code when that value is not
  *     `None` (a string value as its text, each other value as JSON), else the captured output (stdout and stderr of
  *     the code). Upstream returns the value in place of the output in the same way.
  *   - `SUBMIT(...)` ends the code and gives `finalOutput`, the JSON text of the submitted values: with output fields,
  *     an object with one key for each field (positional or keyword values); with no output fields, `{"output": v}` for
  *     the one value.
  *   - An exception of the code, or a syntax error, is a `Right` with a non-zero `exitCode` and the text
  *     `<Type>: <message>` in `stderr`. The session continues.
  *   - A host tool runs when the code calls it. Positional arguments take the names of the tool parameters in order. A
  *     tool `Left` raises a `RuntimeError` in the code with its message.
  *   - `executionTimeout` bounds one `execute`, the host tool calls included. When the time is over, the interpreter
  *     kills the worker and its descendants (see the process below), the result is a `Left` with the component
  *     [[dspy4s.core.contracts.CodeInterpreterErrors.Timeout]], and the session ends.
  *   - After the session ends (a timeout, a worker that exits, a protocol error, or `close`), each `execute` is a
  *     `Left`. `close` kills the worker, is idempotent, and is a `Shutdown` scope each time (see the callbacks below).
  *   - A second `execute` while one runs (for example from a tool) is a `Left`.
  *
  * Callback scopes (see [[dspy4s.core.contracts.InterpreterOperation]]): an `Execute` scope for each `execute`, a
  * `Startup` scope inside the first `Execute`, a `ToolCall` scope for each host tool call, and a `Shutdown` scope for
  * each `close`, with the interpreter name `LocalPythonInterpreter`.
  *
  * ==The process==
  *   - The worker gets its standard input and its standard output as pipes. Its standard error goes to the null device
  *     (upstream `DEVNULL`), so a full pipe cannot stop the worker.
  *   - A reader thread puts each line of the standard output in a queue. The thread of `execute` takes the lines from
  *     the queue with the time limit.
  *   - Each host tool call runs on a new daemon thread, as upstream. The thread of `execute` thus continues to read,
  *     and the time limit stops the wait for a tool that does not return. The tool thread gets the runtime context of
  *     the `execute` call. After the tool calls end, `execute` adds their trace and history to its own context, in the
  *     order of the calls. A tool that throws a fatal error (not `NonFatal`) ends the session, and `execute` throws the
  *     error again (upstream 453823209).
  *   - The JVM cannot start a process in a new process group, and it cannot send a signal to a process group. Thus, to
  *     kill the worker, the interpreter kills each descendant of the worker (`ProcessHandle.descendants`), then the
  *     worker. A process that the worker started, and whose parent exited before the kill, continues to run.
  *
  * ==Deltas from upstream==
  *   - Upstream checks the tool names and the output fields in its constructor. A Scala constructor cannot give an
  *     `Either`, so `execute` gives the `Left` before the worker starts. The session continues.
  *   - The JSON text to the worker escapes each character that is not ASCII. The worker thus reads the same text with
  *     each locale.
  *   - An interrupt of the thread of `execute` while it waits for the worker ends the session. The result is a `Left`,
  *     and the thread keeps its interrupt state.
  *   - Before the worker starts, the first `execute` checks that the executable is Python 3.10 or later, with a time
  *     limit of 10 seconds. An older Python is a `Left` that names the version found and the version needed, and the
  *     session ends. Upstream has no check, because it starts its own Python (see `PythonVersion`).
  *
  * @param tools
  *   the host tools that the code can call by name
  * @param outputFields
  *   the fields of `SUBMIT`, or `None` for a `SUBMIT` with one value
  * @param executionTimeout
  *   the limit of one `execute`, or `None` for no limit
  * @param python
  *   the Python executable, Python 3.10 or later
  */
final class LocalPythonInterpreter(
    tools           : Vector[SandboxTool]                = Vector.empty,
    outputFields    : Option[Vector[SandboxOutputField]] = None,
    executionTimeout: Option[FiniteDuration]             = None,
    python          : String                             = "python3"
) extends ReplCodeInterpreter:
  import LocalPythonInterpreter.*
  import DenoPyodideProtocol.asString
  import DenoPyodideProtocol.decodeJson
  import DenoPyodideProtocol.encodeJson
  import DenoPyodideProtocol.field

  // The `interpreterName` of the callback events.
  private val interpreterName: String = getClass.getSimpleName
  // `close` and a tool thread can end the session while a call is in progress, so the session state is one atomic
  // value.
  private val session = new AtomicReference[Session](Session.NotStarted)
  // True while an `execute` call runs.
  private val active = new AtomicBoolean(false)

  override def execute(code: String): Either[DspyError, CodeResult] = execute(code, Map.empty)

  override def execute(code: String, variables: Map[String, DynamicValue]): Either[DspyError, CodeResult] =
    val inputs =
      DynamicValues.record("code" := code, "variables" -> DynamicValues.recordFromEntries(variables.toVector))
    CallbackDispatcher.withInterpreter(interpreterName, InterpreterOperation.Execute, inputs) {
      if !active.compareAndSet(false, true) then failure("LocalPythonInterpreter already has an active execution.")
      else
        try
          for
            _      <- checkSessionActive()
            _      <- checkToolNames()
            _      <- checkOutputFields()
            _      <- checkVariables(variables)
            worker <- ensureWorker()
            result <- run(worker, executeRequest(code, variables))
          yield result
        finally active.set(false)
    }

  /** Ends the session and kills the worker (upstream `shutdown`). The session does not start again. Each call opens a
    * `Shutdown` scope, also when the session has ended.
    */
  override def close(): Unit =
    val _ =
      CallbackDispatcher.withInterpreter(interpreterName, InterpreterOperation.Shutdown, DynamicValue.Record.empty) {
        endSession()
        Right(())
      }

  // ── Checks ──────────────────────────────────────────────────────────────────────────────────────────────────

  private def failure[A](message: String): Either[DspyError, A] =
    Left(RuntimeError(CodeInterpreterErrors.Interpreter, message))

  private def checkSessionActive(): Either[DspyError, Unit] =
    if session.get() == Session.Ended then sessionEnded() else Right(())

  private def sessionEnded[A](): Either[DspyError, A] =
    failure("The LocalPythonInterpreter session has ended. Create a new interpreter for a new session.")

  /** Upstream `_validate_tool_names`. */
  private def checkToolNames(): Either[DspyError, Unit] =
    val invalid = tools.map(_.name).filterNot(name => isPythonName(name) && !ReservedNames.contains(name))
    if invalid.isEmpty then Right(())
    else failure(s"Tool names must be non-reserved Python identifiers; invalid names: ${invalid.mkString(", ")}")

  /** Upstream `_validate_output_fields`. */
  private def checkOutputFields(): Either[DspyError, Unit] =
    val names = outputFields.getOrElse(Vector.empty).map(_.name)
    if !names.forall(isPythonName) then failure("Output field names must be non-keyword Python identifiers.")
    else if names.distinct.sizeIs != names.size then failure("Output field names must be unique.")
    else Right(())

  /** The variable check of upstream `execute`. */
  private def checkVariables(variables: Map[String, DynamicValue]): Either[DspyError, Unit] =
    val toolNames = tools.map(_.name).toSet
    val invalid   = variables.keys.toVector.sorted
      .filterNot(name => isPythonName(name) && !ReservedNames.contains(name) && !toolNames.contains(name))
    if invalid.isEmpty then Right(())
    else
      failure(
        s"Variable names must be non-reserved Python identifiers distinct from tool names; invalid names: ${invalid.mkString(", ")}"
      )

  // ── Session lifecycle ───────────────────────────────────────────────────────────────────────────────────────

  /** End the session and give the failure (upstream `_raise_terminal_error`). */
  private def terminal[A](message: String): Either[DspyError, A] =
    endSession()
    failure(message)

  /** Move the session to its end and kill its worker. No effect on a session that ended. */
  private def endSession(): Unit =
    session.getAndSet(Session.Ended) match
      case Session.Running(worker) => worker.kill()
      case _                       => ()

  private def ensureWorker(): Either[DspyError, Worker] =
    session.get() match
      case Session.Running(worker) => Right(worker)
      case Session.NotStarted      =>
        // Upstream `start`: the start of the worker is a `Startup` scope inside the `Execute` scope.
        CallbackDispatcher.withInterpreter(interpreterName, InterpreterOperation.Startup, DynamicValue.Record.empty)(
          startWorker()
        )
      case Session.Ended => sessionEnded()

  /** Check the Python version, start the worker, and wait for its `ready` message. Each failure ends the session. */
  private def startWorker(): Either[DspyError, Worker] =
    WorkerScript.path().flatMap(script => PythonVersion.check(python).map(_ => script)) match
      case Left(message) => terminal(message)
      case Right(script) =>
        val builder                          = new ProcessBuilder(python, "-I", script.toString)
        val started: Either[String, Process] =
          try Right(builder.redirectError(ProcessBuilder.Redirect.DISCARD).start())
          catch case NonFatal(error) => Left(describe(error))
        started match
          case Left(message)  => terminal(s"Unable to start the Python worker '$python': $message")
          case Right(process) =>
            val worker = new Worker(process)
            if session.compareAndSet(Session.NotStarted, Session.Running(worker)) then
              worker.startReader()
              awaitReady(worker)
            else
              // `close` ran on a different thread while the worker started.
              worker.kill()
              sessionEnded()

  private def awaitReady(worker: Worker): Either[DspyError, Worker] =
    val deadline = Some(System.nanoTime() + StartupTimeout.toNanos)
    receive(worker, deadline, () => terminal("Python worker did not start within 10 seconds.")).flatMap { message =>
      if message.kind == "ready" then Right(worker)
      else terminal(s"Python worker returned an invalid startup message: ${clip(message.line)}")
    }

  // ── The execute request ─────────────────────────────────────────────────────────────────────────────────────

  /** The `execute` request: the code, the variables, the tool names, and the output fields (or `null`). */
  private def executeRequest(code: String, variables: Map[String, DynamicValue]): String =
    val fields = outputFields.fold[DynamicValue](DynamicValue.Null) { all =>
      DynamicValue.Sequence(Chunk.from(all.map { f =>
        val entries = Vector("name" := f.name) ++ f.pythonType.map(t => "type" := t).toVector
        DynamicValues.recordFromEntries(entries): DynamicValue
      }))
    }
    encode(DynamicValues.record(
      "type"          := "execute",
      "code"          := code,
      "variables"     -> DynamicValues.recordFromEntries(variables.toVector),
      "tools"         := tools.map(_.name),
      "output_fields" -> fields
    ))

  /** Send the request, serve the tool requests, wait for the tool calls, and decode the reply (upstream `execute`). */
  private def run(worker: Worker, request: String): Either[DspyError, CodeResult] =
    val deadline = executionTimeout.map(limit => System.nanoTime() + limit.toNanos)
    val captured = ContextPropagation.captureAll
    for
      _      <- send(worker, request)
      reply  <- awaitReply(worker, deadline, captured, Vector.empty)
      deltas <- awaitToolCalls(reply.toolCalls, deadline)
      _       = deltas.foreach(RuntimeEnvironment.propagate)
      result <- decodeReply(reply.message)
    yield result

  /** Upstream `_send`: a request that the worker does not get ends the session. */
  private def send(worker: Worker, request: String): Either[DspyError, Unit] =
    if worker.send(request) then Right(()) else terminal("Unable to send the request to the Python worker.")

  /** Read messages until a message that is not a tool request arrives. Each tool request starts a tool call. */
  @tailrec
  private def awaitReply(
      worker   : Worker,
      deadline : Option[Long],
      captured : ContextPropagation.Captured,
      toolCalls: Vector[CompletableFuture[ToolOutcome]]
  ): Either[DspyError, Reply] =
    receive(worker, deadline, () => timedOut()) match
      case Left(error)                                      => Left(error)
      case Right(message) if message.kind == "tool_request" =>
        awaitReply(worker, deadline, captured, toolCalls :+ startToolCall(worker, message.record, captured))
      case Right(message) => Right(Reply(message, toolCalls))

  /** Map the reply to the request to a result. */
  private def decodeReply(message: Message): Either[DspyError, CodeResult] =
    val stdout = field(message.record, "stdout").flatMap(asString).getOrElse("")
    val error  = field(message.record, "error").fold("")(text)
    val value  = field(message.record, "value").filterNot(isNull)
    message.kind match
      case "result" => Right(CodeResult(stdout = value.fold(stdout)(text), stderr = "", exitCode = 0))
      case "final"  =>
        val submitted = encodeJson(value.getOrElse(DynamicValue.Null))
        Right(CodeResult(stdout = stdout, stderr = "", exitCode = 0, finalOutput = Some(submitted)))
      case "execution_error" => Right(CodeResult(stdout = stdout, stderr = error, exitCode = 1))
      case "syntax"          => Right(CodeResult(stdout = stdout, stderr = s"SyntaxError: $error", exitCode = 1))
      case "terminal_error"  => terminal(s"Python worker failed: $error")
      case _                 => terminal(s"Python worker returned an unknown message: ${clip(message.line)}")

  private def timedOut[A](): Either[DspyError, A] =
    endSession()
    val limit = executionTimeout.fold("")(limit => s" of $limit")
    Left(RuntimeError(CodeInterpreterErrors.Timeout, s"Python worker exceeded the execution timeout$limit."))

  // ── Host tool calls ─────────────────────────────────────────────────────────────────────────────────────────

  /** Run one tool request on a new daemon thread (upstream `_run_in_thread`), with the runtime context of `execute`.
    * The thread sends the reply to the worker. The outcome has the trace and the history that the tool added.
    */
  private def startToolCall(
      worker  : Worker,
      request : DynamicValue.Record,
      captured: ContextPropagation.Captured
  ): CompletableFuture[ToolOutcome] =
    val outcome      = new CompletableFuture[ToolOutcome]()
    def body(): Unit =
      try
        val delta = captured.run {
          RuntimeEnvironment.withContext(RuntimeEnvironment.current.withDelta(RuntimeDelta.empty)) {
            // A reply that the worker does not get blocks the code, so the session ends (upstream `_send`).
            if !worker.send(toolReply(request)) then endSession()
            RuntimeEnvironment.current.delta
          }
        }
        val _ = outcome.complete(ToolOutcome.Done(delta))
      catch
        case error: Throwable =>
          // Upstream 453823209: wake `execute`, because the worker cannot reply while this tool has no answer.
          worker.post(Inbound.Fatal(error))
          val _ = outcome.complete(ToolOutcome.Fatal(error))
    val thread = new Thread(() => body(), "dspy4s-local-python-tool")
    thread.setDaemon(true)
    thread.start()
    outcome

  /** The `tool_result` or `tool_error` reply to one tool request (upstream `_handle_tool`). Each call is a `ToolCall`
    * scope (upstream `invoke_tool`), also for an unknown tool. The scope holds the `ToolStartEvent` / `ToolEndEvent`
    * pair of the tool. The inputs are `tool` and `args`, and `args` has the keyword record of the call.
    */
  private def toolReply(request: DynamicValue.Record): String =
    val id   = field(request, "id").getOrElse(DynamicValue.Null)
    val name = field(request, "name").flatMap(asString).getOrElse("")
    val args = field(request, "args").collect { case s: DynamicValue.Sequence => s.elements.toVector }
      .getOrElse(Vector.empty)
    val kwargs =
      field(request, "kwargs").collect { case r: DynamicValue.Record => r }.getOrElse(DynamicValue.Record.empty)
    val tool   = tools.find(_.name == name)
    val bound  = tool.fold[Either[DspyError, DynamicValue.Record]](Right(kwargs))(bind(_, args, kwargs))
    val inputs = DynamicValues.record("tool" := name, "args" -> bound.getOrElse(kwargs))
    val result =
      try
        CallbackDispatcher.withInterpreter[DynamicValue](interpreterName, InterpreterOperation.ToolCall, inputs) {
          tool match
            case None        => failure(s"Unknown tool: $name")
            case Some(found) =>
              bound.flatMap(record => CallbackDispatcher.withTool(toolName = name, args = record)(found.invoke(record)))
        }.left.map(_.message)
      catch case NonFatal(error) => Left(describe(error))
    result match
      case Right(value)  => encode(DynamicValues.record("type" := "tool_result", "id" -> id, "value" -> value))
      case Left(message) => encode(DynamicValues.record("type" := "tool_error", "id" -> id, "error" := message))

  /** The keyword record of a tool call: each positional argument takes the name of the tool parameter at its position.
    */
  private def bind(
      tool  : SandboxTool,
      args  : Vector[DynamicValue],
      kwargs: DynamicValue.Record
  ): Either[DspyError, DynamicValue.Record] =
    val names = tool.parameters.map(_.name)
    if args.sizeIs > names.size then
      val noun = if names.sizeIs == 1 then "argument" else "arguments"
      failure(s"TypeError: ${tool.name}() takes ${names.size} positional $noun but ${args.size} were given")
    else
      val positional = names.zip(args)
      positional.collectFirst { case (name, _) if DynamicValues.recordGet(kwargs, name).isDefined => name } match
        case Some(name) => failure(s"TypeError: ${tool.name}() got multiple values for argument '$name'")
        case None       => Right(DynamicValues.recordFromEntries(positional ++ kwargs.fields))

  /** Wait for the tool calls of the request, with the time limit (upstream `tool_call.result(timeout)`). */
  private def awaitToolCalls(
      toolCalls: Vector[CompletableFuture[ToolOutcome]],
      deadline : Option[Long]
  ): Either[DspyError, Vector[RuntimeDelta]] =
    toolCalls.foldLeft[Either[DspyError, Vector[RuntimeDelta]]](Right(Vector.empty)) { (acc, toolCall) =>
      acc.flatMap(deltas => awaitToolCall(toolCall, deadline).map(deltas :+ _))
    }

  private def awaitToolCall(
      toolCall: CompletableFuture[ToolOutcome],
      deadline: Option[Long]
  ): Either[DspyError, RuntimeDelta] =
    val outcome: Either[DspyError, Option[ToolOutcome]] =
      try
        deadline match
          case None      => Right(Some(toolCall.get()))
          case Some(end) => Right(Some(toolCall.get(remaining(end), TimeUnit.NANOSECONDS)))
      catch
        case _: TimeoutException     => Right(None)
        case _: InterruptedException => interrupted()
    outcome.flatMap {
      case None                           => timedOut()
      case Some(ToolOutcome.Done(delta))  => Right(delta)
      case Some(ToolOutcome.Fatal(error)) =>
        endSession()
        throw error
    }

  // ── Messages ────────────────────────────────────────────────────────────────────────────────────────────────

  /** The next message of the worker (upstream `_receive`). `overtime` gives the result when the deadline passes. A
    * worker that exits, a line that is not a tagged JSON object, and a fatal tool error end the session.
    */
  private def receive(
      worker  : Worker,
      deadline: Option[Long],
      overtime: () => Either[DspyError, Message]
  ): Either[DspyError, Message] =
    val next: Either[DspyError, Option[Inbound]] =
      try
        deadline match
          case None      => Right(Some(worker.inbound.take()))
          case Some(end) => Right(Option(worker.inbound.poll(remaining(end), TimeUnit.NANOSECONDS)))
      catch case _: InterruptedException => interrupted()
    next.flatMap {
      case None                       => overtime()
      case Some(Inbound.End)          => terminal("Python worker exited unexpectedly.")
      case Some(Inbound.Fatal(error)) =>
        endSession()
        throw error
      case Some(Inbound.Line(line)) => decodeMessage(line)
    }

  private def decodeMessage(line: String): Either[DspyError, Message] =
    decodeJson(line) match
      case Some(record: DynamicValue.Record) => field(record, "type").flatMap(asString) match
          case Some(kind) => Right(Message(kind, record, line))
          case None       => terminal(s"Python worker returned an invalid message: ${clip(line)}")
      case _ => terminal(s"Python worker returned invalid JSON: ${clip(line)}")

  /** An interrupt of the waiting thread ends the session. The thread keeps its interrupt state. */
  private def interrupted[A](): Either[DspyError, A] =
    Thread.currentThread().interrupt()
    terminal("The thread was interrupted while it waited for the Python worker.")

object LocalPythonInterpreter:

  /** A factory for `RLM` (`RuntimeContext.interpreterFactory`): a new interpreter for each call, with the tools and the
    * output fields of the call. An empty list of output fields gives a `SUBMIT` with one value. The factory has the
    * [[ExecutionInstructions]] of this interpreter.
    */
  def factory(
      executionTimeout: Option[FiniteDuration] = None,
      python          : String                 = "python3"
  ): ReplInterpreterFactory =
    new ReplInterpreterFactory:
      override def apply(tools: Vector[SandboxTool], outputFields: Vector[SandboxOutputField]): ReplCodeInterpreter =
        LocalPythonInterpreter(
          tools = tools,
          outputFields = Option.when(outputFields.nonEmpty)(outputFields),
          executionTimeout = executionTimeout,
          python = python
        )

      override val executionInstructions: String = ExecutionInstructions

  /** The text that tells the model how the code of this interpreter runs (upgrade item I7, upstream
    * `LocalInterpreter.execution_instructions` in `dspy/primitives/local_interpreter.py`).
    */
  val ExecutionInstructions: String =
    "Code runs as Python in a persistent local subprocess. State, imports, functions, and JSON-compatible " +
      "variables persist for this session. Host tools and SUBMIT are available as global functions. All threads " +
      "must finish before each code execution returns; use context-managed executors for parallel work."

  /** The time limit for the `ready` message of the worker (upstream `start`). */
  private val StartupTimeout: FiniteDuration = 10.seconds

  /** The names that the worker owns. A tool or a variable cannot have one of these names. */
  private val ReservedNames: Set[String] = Set("SUBMIT", "__builtins__")

  /** The time that `kill` waits for the worker to exit (upstream `process.wait(timeout=1)`). */
  private val ExitWaitMillis = 1000L

  /** The length limit for text from the worker in a failure message. */
  private val DiagnosticLength = 500

  /** The life of one worker session. A session goes from `NotStarted` to `Running` to `Ended`, or from `NotStarted` to
    * `Ended`. It does not go back.
    */
  private enum Session derives CanEqual:
    case NotStarted
    case Running(worker: Worker)
    case Ended

  /** An item of the queue that the thread of `execute` reads. */
  private enum Inbound derives CanEqual:
    /** One line of the standard output of the worker. */
    case Line(text: String)

    /** The end of the standard output of the worker. */
    case End

    /** A fatal error of a tool thread (upstream 453823209). */
    case Fatal(error: Throwable)

  /** The result of one tool thread: the trace and the history that the tool added, or a fatal error. */
  private enum ToolOutcome:
    case Done(delta: RuntimeDelta)
    case Fatal(error: Throwable)

  /** A message of the worker: its `type`, the full object, and the line. */
  private final case class Message(kind: String, record: DynamicValue.Record, line: String)

  /** The reply to an `execute` request and the tool calls that the request started. */
  private final case class Reply(message: Message, toolCalls: Vector[CompletableFuture[ToolOutcome]])

  /** One worker process: the pipe to its standard input and the queue of its output lines. */
  private final class Worker(process: Process):
    val inbound: LinkedBlockingQueue[Inbound] = new LinkedBlockingQueue[Inbound]()

    private val stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8))
    // The holder of this lock writes to `stdin` (upstream `_send_lock`).
    private val sendLock = new ReentrantLock()
    // Set by `kill`. A send after the kill does nothing.
    @volatile private var stopped = false

    def post(item: Inbound): Unit =
      val _ = inbound.offer(item)

    /** Start the daemon thread that puts each output line in `inbound`, then `End` at the end of the output. */
    def startReader(): Unit =
      val reader = new Thread(() => read(), s"dspy4s-local-python-reader-${process.pid()}")
      reader.setDaemon(true)
      reader.start()

    private def read(): Unit =
      val stdout = new BufferedReader(new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
      try Iterator.continually(stdout.readLine()).takeWhile(_ != null).foreach(line => post(Inbound.Line(line)))
      catch case NonFatal(_) => ()
      finally
        post(Inbound.End)
        try stdout.close()
        catch case NonFatal(_) => ()

    /** Write one line to the worker. `false` when the worker was killed or when the write failed. One write runs at a
      * time. A write that ends after a kill closes `stdin`.
      */
    def send(line: String): Boolean =
      sendLock.lock()
      try
        !stopped && {
          try
            stdin.write(line)
            stdin.write('\n')
            stdin.flush()
            true
          catch case NonFatal(_) => false
        }
      finally
        if stopped then closeStdin()
        sendLock.unlock()

    /** Kill each descendant of the worker, then the worker (upstream `_kill`), and wait 1 second at most for the exit.
      * The reader thread then gets the end of the output. `kill` does not wait for a write in progress: that write
      * closes `stdin` when it ends.
      */
    def kill(): Unit =
      stopped = true
      try
        process.descendants().forEach { child =>
          val _ = child.destroyForcibly()
        }
      catch case NonFatal(_) => ()
      val _ = process.destroyForcibly()
      try
        val _ = process.waitFor(ExitWaitMillis, TimeUnit.MILLISECONDS)
      catch case _: InterruptedException => Thread.currentThread().interrupt()
      if sendLock.tryLock() then
        try closeStdin()
        finally sendLock.unlock()

    private def closeStdin(): Unit =
      try stdin.close()
      catch case NonFatal(_) => ()

  /** The check of the version of the Python executable before the worker starts. The vendored worker needs Python 3.10
    * or later: it calls `zip(..., strict=False)`, so on Python 3.9 the worker starts, and a positional `SUBMIT` fails
    * with "TypeError: zip() takes no keyword arguments". Upstream starts the worker with `sys.executable`
    * (`dspy/primitives/local_interpreter.py`), and dspy needs Python 3.10 or later (`pyproject.toml`,
    * `requires-python`), so upstream has no such check. The port starts `python3` from `PATH`, which can be older, for
    * example the macOS `/usr/bin/python3` (3.9.6).
    *
    * The check runs `<python> -I -c <Probe>`. The output and the standard error of the process go to a temporary file.
    * The results:
    *   - an executable that does not start: "Unable to start the Python worker '<python>': <error>";
    *   - no exit in 10 seconds: the check kills the process and its descendants, and the message names the time limit;
    *   - an exit code that is not 0: the message has the exit code and the output;
    *   - no output line with `<major>.<minor>.<micro>`: the message has the output;
    *   - a version before 3.10: the message names the version found and the version needed.
    *
    * The check keeps each executable name that passed, for the life of the JVM. Thus the next interpreters on that
    * executable (one for each RLM call) do not start a second process.
    */
  private object PythonVersion:
    private val NeededMajor = 3
    private val NeededMinor = 10

    /** The Python code that prints `<major>.<minor>.<micro>`. */
    private val Probe = "import sys; print('.'.join(str(n) for n in sys.version_info[:3]))"

    private val VersionLine = """(\d{1,9})\.(\d{1,9})\.(\d{1,9})""".r

    /** The largest part of the output that the check reads. */
    private val MaxOutputBytes = 4096

    private val passed = ConcurrentHashMap.newKeySet[String]()

    /** `Right` when `python` is Python 3.10 or later, else the message of the failure. */
    def check(python: String): Either[String, Unit] =
      if passed.contains(python) then Right(())
      else
        val result = output(python).flatMap(text => accept(python, text))
        if result.isRight then
          val _ = passed.add(python)
        result

    private def accept(python: String, text: String): Either[String, Unit] =
      text.linesIterator.map(_.trim).collectFirst { case VersionLine(major, minor, micro) =>
        (major.toInt, minor.toInt, micro.toInt)
      } match
        case None                        => Left(s"Unable to read the version of the Python executable '$python': ${clip(text)}")
        case Some((major, minor, micro)) =>
          if major > NeededMajor || (major == NeededMajor && minor >= NeededMinor) then Right(())
          else
            Left(
              s"The Python executable '$python' is Python $major.$minor.$micro. " +
                s"LocalPythonInterpreter needs Python $NeededMajor.$NeededMinor or later."
            )

    /** The output of the probe, with its standard error, when the process exits with the code 0. */
    private def output(python: String): Either[String, String] =
      val file: Either[String, Path] =
        try Right(Files.createTempFile("dspy4s-python-version", ".txt"))
        catch case NonFatal(error) => Left(s"Unable to check the version of '$python': ${describe(error)}")
      file.flatMap { path =>
        try
          for
            code <- run(python, path)
            text <- read(python, path)
            _    <- Either.cond(
                   code == 0,
                   (),
                   s"The version check of the Python executable '$python' failed with the exit code $code: " +
                     clip(text)
                 )
          yield text
        finally
          try
            val _ = Files.deleteIfExists(path)
          catch case NonFatal(_) => ()
      }

    /** Run the probe with its output in `path`, and give its exit code. */
    private def run(python: String, path: Path): Either[String, Int] =
      val builder                          = new ProcessBuilder(python, "-I", "-c", Probe).redirectErrorStream(true).redirectOutput(path.toFile)
      val started: Either[String, Process] =
        try Right(builder.start())
        catch case NonFatal(error) => Left(s"Unable to start the Python worker '$python': ${describe(error)}")
      started.flatMap { process =>
        // The probe reads no input.
        try process.getOutputStream.close()
        catch case NonFatal(_) => ()
        val exited: Either[String, Boolean] =
          try Right(process.waitFor(StartupTimeout.toMillis, TimeUnit.MILLISECONDS))
          catch
            case _: InterruptedException =>
              Thread.currentThread().interrupt()
              Left("The thread was interrupted while it waited for the Python worker.")
        exited match
          case Right(true)  => Right(process.exitValue())
          case Right(false) =>
            kill(process)
            Left(s"The Python executable '$python' did not report its version within $StartupTimeout.")
          case Left(message) =>
            kill(process)
            Left(message)
      }

    /** The start of the output in `path`, with no white space at the two ends. */
    private def read(python: String, path: Path): Either[String, String] =
      try
        val stream = Files.newInputStream(path)
        try Right(new String(stream.readNBytes(MaxOutputBytes), StandardCharsets.UTF_8).trim)
        finally stream.close()
      catch case NonFatal(error) => Left(s"Unable to read the version of '$python': ${describe(error)}")

    private def kill(process: Process): Unit =
      try
        process.descendants().forEach { child =>
          val _ = child.destroyForcibly()
        }
      catch case NonFatal(_) => ()
      val _ = process.destroyForcibly()

  /** The vendored worker script as a file. The script is written once per JVM, and again when the file was removed. */
  private object WorkerScript:
    private val Resource             = "/dspy4s/core/runtime/local_interpreter_worker.py"
    private var cached: Option[Path] = None

    def path(): Either[String, Path] =
      synchronized {
        cached.filter(Files.isRegularFile(_)) match
          case Some(file) => Right(file)
          case None       => extract().map { file =>
              cached = Some(file)
              file
            }
      }

    private def extract(): Either[String, Path] =
      Option(getClass.getResourceAsStream(Resource)) match
        case None         => Left(s"The resource $Resource is missing from dspy4s-core.")
        case Some(stream) =>
          try
            val file = Files.createTempFile("dspy4s-local-interpreter-worker", ".py")
            file.toFile.deleteOnExit()
            try
              val _ = Files.write(file, stream.readAllBytes())
            finally stream.close()
            Right(file)
          catch case NonFatal(error) => Left(s"Unable to write the Python worker script: ${describe(error)}")

  /** A Python identifier that is not a keyword. */
  private def isPythonName(name: String): Boolean =
    PythonNames.isIdentifier(name) && !PythonNames.Keywords.contains(name)

  /** The text of a value: a string as its text, each other value as JSON. */
  private def text(value: DynamicValue): String =
    DynamicValues.asString(value).getOrElse(DenoPyodideProtocol.encodeJson(value))

  private def isNull(value: DynamicValue): Boolean =
    value match
      case _: DynamicValue.Null.type => true
      case _                         => false

  /** The JSON text of `value`, with a JSON escape for each character that is not ASCII. Such a character can only occur
    * in a JSON string, so the escape keeps the value.
    */
  private def encode(value: DynamicValue): String =
    val json = DenoPyodideProtocol.encodeJson(value)
    if json.forall(_ < 0x80) then json
    else
      val builder = new StringBuilder(json.length + 16)
      json.foreach(c => if c < 0x80 then builder.append(c) else builder.append(f"\\u${c.toInt}%04x"))
      builder.toString

  /** `<Type>: <message>` (upstream `describe`). */
  private def describe(error: Throwable): String =
    val name = error.getClass.getSimpleName
    Option(error.getMessage).filter(_.nonEmpty).fold(name)(message => s"$name: $message")

  private def clip(line: String): String = line.take(DiagnosticLength)

  private def remaining(deadline: Long): Long = math.max(0L, deadline - System.nanoTime())
