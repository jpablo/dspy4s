package dspy4s.core.runtime

import dspy4s.core.contracts.CodeInterpreterErrors
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.InterpreterOperation
import dspy4s.core.contracts.SandboxTool
import dspy4s.core.contracts.:=
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema
import zio.blocks.schema.json.WriterConfig

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal

/** The process helpers and the callback scopes that the code interpreters share. */
private[runtime] object InterpreterSupport:

  // ── Processes ───────────────────────────────────────────────────────────────────────────────────────────────

  /** Kill each descendant of `process`, then `process`. The call does not wait for the exit. */
  def killTree(process: Process): Unit =
    try
      process.descendants().forEach { child =>
        val _ = child.destroyForcibly()
      }
    catch case NonFatal(_) => ()
    val _ = process.destroyForcibly()

  /** `Process.waitFor` with a time limit. An interrupt gives `false`, and the thread keeps its interrupt state. */
  def exitedIn(process: Process, millis: Long): Boolean =
    try process.waitFor(millis, TimeUnit.MILLISECONDS)
    catch
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        false

  /** Start a daemon thread that runs `body`. */
  def startDaemon(name: String)(body: => Unit): Thread =
    val thread = new Thread(() => body, name)
    thread.setDaemon(true)
    thread.start()
    thread

  /** The exit code and the start of the standard output of a probe process. */
  final case class ProbeRun(exitCode: Int, output: String)

  /** The reason that a probe gave no [[ProbeRun]]. */
  enum ProbeFailure derives CanEqual:
    case NoTempFile(error: Throwable)
    case NotStarted(error: Throwable)
    case TimedOut
    case NotRead(error: Throwable)

  /** Start `builder` with its standard output in a temporary file, close its standard input, and wait `timeoutMillis`
    * at most for the exit. The result has the first `maxBytes` bytes of the output. The output goes to a file, so a
    * process that holds the output open cannot block the read. A process that is alive after the call (no exit in the
    * time limit, or an interrupt) is killed with its descendants. An interrupt of the wait goes to the caller as an
    * `InterruptedException`.
    */
  def probe(builder: ProcessBuilder, timeoutMillis: Long, maxBytes: Int): Either[ProbeFailure, ProbeRun] =
    val file: Either[ProbeFailure, Path] =
      try Right(Files.createTempFile("dspy4s-probe", ".txt"))
      catch case NonFatal(error) => Left(ProbeFailure.NoTempFile(error))
    file.flatMap { path =>
      try
        val started: Either[ProbeFailure, Process] =
          try Right(builder.redirectOutput(path.toFile).start())
          catch case NonFatal(error) => Left(ProbeFailure.NotStarted(error))
        started.flatMap { process =>
          try
            // The probe reads no input.
            try process.getOutputStream.close()
            catch case NonFatal(_) => ()
            if !process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) then Left(ProbeFailure.TimedOut)
            else readStart(path, maxBytes).map(ProbeRun(process.exitValue(), _))
          finally if process.isAlive then killTree(process)
        }
      finally
        try
          val _ = Files.deleteIfExists(path)
        catch case NonFatal(_) => ()
    }

  private def readStart(path: Path, maxBytes: Int): Either[ProbeFailure, String] =
    try
      val stream = Files.newInputStream(path)
      try Right(new String(stream.readNBytes(maxBytes), StandardCharsets.UTF_8))
      finally stream.close()
    catch case NonFatal(error) => Left(ProbeFailure.NotRead(error))

  /** A class-path resource as a temporary file. The first call of [[path]] writes the file. A later call writes the
    * file again when the file was removed, for example by a cleanup of the temporary directory. The JVM deletes each
    * file that it wrote when it exits. One instance is thread-safe.
    *
    * @param writeFailure
    *   gives the message when the file cannot be written
    */
  final class ResourceFile(resource: String, prefix: String, suffix: String, writeFailure: Throwable => String):
    private var cached: Option[Path] = None

    /** The path of the file. `Left` has the message of the failure. */
    def path(): Either[String, Path] =
      synchronized {
        cached.filter(Files.isRegularFile(_)) match
          case Some(file) => Right(file)
          case None       => extractResource(resource, prefix, suffix, writeFailure).map { file =>
              cached = Some(file)
              file
            }
      }

  /** Write the class-path resource `resource` to a new temporary file that the JVM deletes when it exits. `Left` has
    * the message of the failure. `writeFailure` gives the message when the file cannot be written.
    */
  private def extractResource(
      resource    : String,
      prefix      : String,
      suffix      : String,
      writeFailure: Throwable => String
  ): Either[String, Path] =
    Option(getClass.getResourceAsStream(resource)) match
      case None         => Left(s"The resource $resource is missing from dspy4s-core.")
      case Some(stream) =>
        try
          val file = Files.createTempFile(prefix, suffix)
          file.toFile.deleteOnExit()
          try
            val _ = Files.write(file, stream.readAllBytes())
          finally stream.close()
          Right(file)
        catch case NonFatal(error) => Left(writeFailure(error))

  /** `<Type>: <message>` (upstream `describe`), or `<Type>` when the error has no message. */
  def describe(error: Throwable): String =
    val name = error.getClass.getSimpleName
    Option(error.getMessage).filter(_.nonEmpty).fold(name)(message => s"$name: $message")

  // ── JSON text ───────────────────────────────────────────────────────────────────────────────────────────────

  private val jsonCodec = Schema.dynamic.jsonCodec

  /** The JSON text of `value`, written with `config`. `Left` has the cause when the JSON writer cannot write the value.
    * The JSON writer of zio-blocks throws on a text with a lone UTF-16 surrogate, for example the first half of an
    * emoji in a text that was cut at a fixed length.
    */
  def tryEncodeJson(value: DynamicValue, config: WriterConfig = WriterConfig): Either[String, String] =
    try Right(jsonCodec.encodeToString(value, config))
    catch case NonFatal(error) => Left(describe(error))

  // ── Callback scopes and request records ─────────────────────────────────────────────────────────────────────

  /** The inputs of an `Execute` scope: the code and the variables. */
  def executeInputs(code: String, variables: Map[String, DynamicValue]): DynamicValue.Record =
    DynamicValues.record("code" := code, "variables" -> DynamicValues.recordFromEntries(variables.toVector))

  /** Run the tool `name` of `tools` with the keyword record `args` (upstream `invoke_tool`). The call is a `ToolCall`
    * scope, also for an unknown tool, and the scope holds the `ToolStartEvent` / `ToolEndEvent` pair of the tool. The
    * inputs of the scope are `tool` and `shownArgs`. A `Left` in `args` is the result of the call.
    */
  def invokeTool(
      interpreterName: String,
      tools          : Vector[SandboxTool],
      name           : String,
      shownArgs      : DynamicValue.Record,
      args           : Either[DspyError, DynamicValue.Record]
  ): Either[DspyError, DynamicValue] =
    val inputs = DynamicValues.record("tool" := name, "args" -> shownArgs)
    CallbackDispatcher.withInterpreter[DynamicValue](interpreterName, InterpreterOperation.ToolCall, inputs) {
      tools.find(_.name == name) match
        case None       => CodeInterpreterErrors.failure(s"Unknown tool: $name")
        case Some(tool) =>
          args.flatMap(record => CallbackDispatcher.withTool(toolName = name, args = record)(tool.invoke(record)))
    }

  /** The record of a tool parameter or of an output field: the name, and the Python type when it is known. */
  def nameAndType(name: String, pythonType: Option[String]): DynamicValue =
    DynamicValues.recordFromEntries(("name" := name) +: pythonType.map("type" := _).toVector)
