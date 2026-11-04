package experiment

import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.repl.*

import scala.annotation.nowarn
import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.duration.*

enum CellOutcome:
  case Ok
  case CompileError(diagnostics: String)
  case Failed(trace: String)

  /** `stopped` is false when the evaluation thread still runs after the stop. */
  case TimedOut(after: FiniteDuration, stopped: Boolean, stopMillis: Long)

final case class CellResult(output: String, outcome: CellOutcome, millis: Long):
  def isOk: Boolean = outcome == CellOutcome.Ok

/** A capture buffer that keeps at most `limit` bytes. */
final class BoundedOutputStream(limit: Int) extends OutputStream:
  private val buffer = ByteArrayOutputStream(8192)

  override def write(b: Int): Unit =
    synchronized:
      if buffer.size() < limit then buffer.write(b)

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    synchronized:
      val room = limit - buffer.size()
      if room > 0 then buffer.write(b, off, math.min(len, room))

  def reset(): Unit = synchronized(buffer.reset())
  def text: String  = synchronized(buffer.toString(UTF_8))

object ReplSession:
  val compilerArgs: Array[String] = Array(
    "-classpath",
    Sandbox.guestClasspath,
    "-color:never",
    "-Xrepl-interrupt-instrumentation:true"
  )

  /** Binds the handlers of one REPL through the registry key of that REPL. The `val` takes the entry out of the
    * registry when the wrapper object starts, so the binding is in this REPL only.
    */
  def preamble(key: String): List[String] =
    List(
      s"""|import harness.guest.*
        |val hostHandlers: Handlers = Registry.take("$key")""".stripMargin,
      """|given Files = hostHandlers.files
       |given Shell = hostHandlers.shell
       |given Llm = hostHandlers.llm
       |given scala.concurrent.ExecutionContext = hostHandlers.ec""".stripMargin
    )

  /** One pool for the guest tasks of all REPLs. Each task sets the output of its own REPL. */
  private val pool = java.util.concurrent.Executors.newCachedThreadPool: (r: Runnable) =>
    val t = Thread(r, "guest-task")
    t.setDaemon(true)
    t

  /** Threads that did not stop after a timeout, in all sessions. */
  val leakedThreads: mutable.Buffer[Thread] = mutable.ArrayBuffer[Thread]()

/** One persistent REPL with its own class loader, output buffer and handlers. */
final class ReplSession(val name: String, handlers: Option[harness.guest.Handlers]):
  import ReplSession.*

  private val capture  = BoundedOutputStream(4 * 1024 * 1024)
  val out: PrintStream = PrintStream(capture, true, UTF_8)

  /** The time of each start phase, in milliseconds. */
  val startup: mutable.LinkedHashMap[String, Long] = mutable.LinkedHashMap()

  private def timed[A](phase: String)(body: => A): A =
    val t0 = System.nanoTime()
    val a  = body
    startup(phase) = (System.nanoTime() - t0) / 1_000_000
    a

  /** Runs each guest task with the output of this REPL, also on a thread of the shared pool. */
  val executionContext: scala.concurrent.ExecutionContext = new scala.concurrent.ExecutionContext:
    def execute(task: Runnable): Unit =
      pool.execute: () =>
        Console.withOut(out)(Console.withErr(out)(task.run()))
    def reportFailure(cause: Throwable): Unit = cause.printStackTrace(out)

  private val driver                              = timed("driver")(ExpDriver(compilerArgs, out, Sandbox.newLoader()))
  private var state: State                        = timed("initialState")(driver.initialState)
  @volatile private var evalThread: Thread | Null = null

  timed("preamble"):
    handlers.foreach: h =>
      val key = s"$name-${UUID.randomUUID()}"
      harness.guest.Registry.register(key, h.copy(ec = executionContext))
      preamble(key).foreach: code =>
        val r = run(code, 120.seconds)
        if !r.isOk then throw IllegalStateException(s"The preamble failed: ${r.outcome}\n${r.output}")

  /** The `StopRepl` class of this REPL (a separate class for each REPL loader in the `true` mode). */
  def stopReplClass: Class[?] = driver.replClassLoader.nn.loadClass("dotty.tools.repl.StopRepl")

  def replClassLoader: ClassLoader = driver.replClassLoader.nn

  /** The buffer now, also after the cell ended (a thread of the guest can still write to it). */
  def currentOutput: String = capture.text

  def run(code: String, timeout: FiniteDuration = 30.seconds): CellResult =
    val start  = System.nanoTime()
    def millis = (System.nanoTime() - start) / 1_000_000
    capture.reset()
    ParseResult(code.stripTrailing() + "\n")(using state) match
      case parsed: Parsed             => dispatch(parsed, timeout, start)
      case SyntaxErrors(_, errors, _) => CellResult("", CellOutcome.CompileError(format(errors)), millis)
      case Newline                    => CellResult("", CellOutcome.Ok, millis)
      case other                      => CellResult("", CellOutcome.Failed(s"Unexpected parse result: $other"), millis)

  private def dispatch(parsed: Parsed, timeout: FiniteDuration, start: Long): CellResult =
    def millis         = (System.nanoTime() - start) / 1_000_000
    val previousIndex  = state.objectIndex
    val before         = state
    val result         = AtomicReference[Either[Throwable, State]]()
    val body: Runnable = () =>
      val r =
        try
          Console.withOut(out):
            Console.withErr(out):
              driver.setStopFlag(false)
              driver.resetEvaluationThrew()
              Right(driver.runParsed(parsed)(using before))
        // The worker must give every failure to the host, also the ThreadDeath of the stop flag.
        catch case t: Throwable @nowarn("id=E235") => Left(t)
      result.set(r)
    val worker = Thread(null, body, s"repl-$name", 64L << 20)
    worker.setDaemon(true)
    evalThread = worker
    worker.start()
    try
      worker.join(timeout.toMillis)
      if worker.isAlive then
        val stopStart = System.nanoTime()
        driver.setStopFlag(true)
        worker.interrupt()
        worker.join(5000)
        val stopMillis = (System.nanoTime() - stopStart) / 1_000_000
        val stopped    = !worker.isAlive
        if !stopped then leakedThreads.synchronized(leakedThreads += worker)
        result.get() match
          case Right(s) => state = s
          case _        => ()
        skipInvalidWrapper(previousIndex)
        CellResult(withoutStopTrace(capture.text), CellOutcome.TimedOut(timeout, stopped, stopMillis), millis)
      else
        result.get() match
          case Left(t) =>
            skipInvalidWrapper(previousIndex)
            CellResult(capture.text, CellOutcome.Failed(t.toString), millis)
          case Right(newState) =>
            state = newState
            val output  = capture.text
            val outcome =
              if parsed.reporter.hasErrors then CellOutcome.CompileError(output)
              else if driver.evaluationThrew then CellOutcome.Failed(output)
              else CellOutcome.Ok
            CellResult(output, outcome, millis)
    finally evalThread = null

  /** Excludes the stopped wrapper, as atc does, and keeps all earlier definitions. */
  private def skipInvalidWrapper(previousIndex: Int): Unit =
    val stoppedIndex = state.objectIndex.max(previousIndex + 1)
    state = state.copy(
      objectIndex = stoppedIndex,
      invalidObjectIndexes = state.invalidObjectIndexes + stoppedIndex
    )

  /** Removes the trace that the REPL prints when the stop flag ends the cell. */
  private def withoutStopTrace(output: String): String =
    val lines = output.linesIterator.toVector
    val start = lines.lastIndexWhere: line =>
      line.startsWith("java.lang.ThreadDeath") || line.startsWith("java.lang.ExceptionInInitializerError")
    if start < 0 then output else lines.take(start).mkString("\n").stripTrailing()

  private def format(diags: List[Diagnostic]): String =
    diags
      .map: d =>
        val pos = d.pos
        if pos.exists then s"Line ${pos.line + 1}: ${d.message}" else d.message
      .mkString("\n")

  def close(): Unit =
    driver.setStopFlag(true)
    val t = evalThread
    if t != null then t.interrupt()
