package dspy4s.harness.repl

import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.repl.{HarnessDriver, Newline, ParseResult, Parsed, State, SyntaxErrors}
import dspy4s.harness.guest.*
import dspy4s.harness.kernel.CellOutcome
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

import java.io.{ByteArrayOutputStream, File, OutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.nowarn
import scala.concurrent.duration.*

/** The values behind the givens of a REPL (5.2): a handler for each capability, and the `Turn`. The mode says which of
  * them the preamble binds, and how: the bare type reads, and `T^` also writes.
  */
final case class Bindings(
    turn    : Turn,
    files   : Option[Files]    = None,
    shell   : Option[Shell]    = None,
    llm     : Option[Llm]      = None,
    store   : Option[Store]    = None,
    sessions: Option[Sessions] = None,
    children: Option[Children] = None
)

/** The result of one cell: the text that it printed, and its outcome. */
final case class CellRun(output: String, outcome: CellOutcome)

/** One REPL for each activation (6), in the same JVM, with capture checking and safe mode. The protections of Phase 0
  * hold: one cell at a time on its own thread with `Console.withOut`, the stop flag at the timeout, a catch around the
  * run, and an invalid wrapper for a cell that does not complete, so a later cell sees only the definitions from before
  * it.
  *
  * Not in this version: the second try in two parts, the refusal of the calls of a cell after its `submit`, and a clock
  * that stops during a guest API call.
  *
  * @param preamble
  *   the preamble of the session (5.1): its types, `submit` and its inputs
  * @param cellTimeout
  *   the time of one cell, with its compile
  * @param cellLimit
  *   the number of cells after which the REPL is full, and the host starts a new one (protection 5 of Phase 0)
  */
final class ScalaRepl(
    mode       : Mode,
    bindings   : Bindings,
    preamble   : String         = "",
    cellTimeout: FiniteDuration = 30.seconds,
    cellLimit  : Int            = 300
) extends AutoCloseable:
  import ScalaRepl.*

  private val key                              = s"repl-${UUID.randomUUID()}"
  private val capture                          = BoundedOutput(4 * 1024 * 1024)
  private val out                              = PrintStream(capture, true, UTF_8)
  private val driver                           = HarnessDriver(compilerArgs, out, SandboxLoader())
  private var state: State                     = driver.initialState
  private var cells                            = 0
  @volatile private var worker: Thread | Null  = null
  @volatile private var uncleanThread: Boolean = false

  HostBindings.register(key, bindings)
  // The givens read the bindings before safe mode starts. The preamble of the session comes after, in safe mode, so a
  // cell can use its types, `submit` and its inputs.
  for chunk <- givensFor(mode, key) :+ "import language.experimental.safe" :+ preamble if chunk.trim.nonEmpty do
    val result = execute(chunk)
    if result.outcome != CellOutcome.Ok then
      close()
      throw IllegalStateException(s"the preamble failed: ${result.outcome}\n${result.output}")
  cells = 0

  /** Runs one cell. */
  def run(code: String): CellRun =
    cells += 1
    execute(code)

  /** True when a cell did not stop after its timeout, so its thread still runs (protection 3 of Phase 0). The host then
    * starts a new process at the next safe point.
    */
  def unclean: Boolean = uncleanThread

  /** True after `cellLimit` cells. The memory of a REPL grows with the square of its cells, so the host starts a new
    * REPL with the preamble, the store and the note of lost names.
    */
  def full: Boolean = cells >= cellLimit

  def close(): Unit =
    driver.setStopFlag(true)
    val t = worker
    if t != null then t.interrupt()
    HostBindings.remove(key)

  private def execute(code: String): CellRun =
    capture.reset()
    HostBindings.clearSubmit(key)
    ParseResult(code.stripTrailing() + "\n")(using state) match
      case parsed: Parsed             => dispatch(parsed)
      case SyntaxErrors(_, errors, _) => compileError(errors.map(describe).toVector)
      case Newline                    => CellRun("", CellOutcome.Ok)
      case other                      => CellRun("", CellOutcome.Failed(s"the REPL gave $other"))

  private def dispatch(parsed: Parsed): CellRun =
    val before         = state
    val result         = AtomicReference[Either[Throwable, State] | Null](null)
    val body: Runnable = () =>
      result.set(
        try
          Console.withOut(out):
            Console.withErr(out):
              driver.setStopFlag(false)
              driver.resetEvaluationThrew()
              Right(driver.runParsed(parsed)(using before))
        // The thread of a cell gives each failure to the host: a compiler crash, and the stop of the stop flag.
        catch case t: Throwable @nowarn("id=E235") => Left(t)
      )
    val thread = Thread(null, body, key, 64L << 20)
    thread.setDaemon(true)
    worker = thread
    thread.start()
    try
      thread.join(cellTimeout.toMillis)
      if thread.isAlive then timedOut(thread, before)
      else
        result.get() match
          case Left(t) =>
            invalidate(before.objectIndex + 1)
            CellRun(capture.text, CellOutcome.Failed(t.toString))
          case Right(newState) =>
            state = newState
            HostBindings.submitted(key) match
              case Some(value) =>
                // A cell that ended by `submit` defines no name, because the signal leaves the cell (5.1).
                invalidate(newState.objectIndex)
                CellRun(withoutSignal(capture.text), CellOutcome.Submitted(value))
              case None if parsed.reporter.hasErrors => compileError(capture.text.linesIterator.toVector)
              case None if driver.evaluationThrew    =>
                invalidate(newState.objectIndex)
                CellRun(capture.text, CellOutcome.Failed(firstLine(capture.text)))
              case None => CellRun(capture.text, CellOutcome.Ok)
          case null => CellRun(capture.text, CellOutcome.Failed("the cell gave no result"))
    finally worker = null

  private def timedOut(thread: Thread, before: State): CellRun =
    driver.setStopFlag(true)
    thread.interrupt()
    thread.join(5000)
    if thread.isAlive then uncleanThread = true
    invalidate(state.objectIndex.max(before.objectIndex + 1))
    CellRun(withoutStopTrace(capture.text), CellOutcome.TimedOut(cellTimeout.toMillis))

  /** Hides the wrapper of a cell that did not complete, as atc does. The earlier definitions stay. */
  private def invalidate(index: Int): Unit =
    state = state.copy(
      objectIndex = state.objectIndex.max(index),
      invalidObjectIndexes = state.invalidObjectIndexes + index
    )

  private def compileError(diagnostics: Vector[String]): CellRun =
    val text = diagnostics.mkString("\n")
    CellRun(text, CellOutcome.CompileError(diagnostics ++ hint(text).map(h => s"Hint: $h")))

object ScalaRepl:

  /** The options of Phase 0 and of the checks of section 12. */
  private lazy val compilerArgs: Array[String] = Array(
    "-classpath",
    guestClasspath,
    "-color:never",
    "-language:experimental.captureChecking",
    "-language:experimental.modularity",
    "-Yexplicit-nulls",
    "-Xrepl-interrupt-instrumentation:true"
  )

  /** The compile classpath of a cell: the Scala library, the guest API, its JSON, and the host bindings. */
  private lazy val guestClasspath: String =
    StdRouting.install()
    List(
      Class.forName("scala.Predef$"),
      classOf[scala.deriving.Mirror],
      classOf[Files],
      classOf[Json],
      classOf[Chunk[?]],
      classOf[Bindings]
    ).map(c => Paths.get(c.getProtectionDomain.getCodeSource.getLocation.toURI).toString).distinct
      .mkString(File.pathSeparator)

  /** The preamble of a mode (5.2): one given for each capability that the mode gives, and the `Turn`. The bindings come
    * from the registry with the key of this REPL, before safe mode starts, so a cell cannot name them.
    */
  def givensFor(mode: Mode, key: String): Vector[String] =
    def bind(name: String, tpe: String, field: String, access: Access, read: Boolean): Option[String] =
      access match
        case Access.Full               => Some(s"@assumeSafe given $name: ($tpe^) = host.$field.get")
        case Access.Read if read       => Some(s"@assumeSafe given $name: $tpe = host.$field.get")
        case Access.Read | Access.None => None
    Vector(
      """import language.experimental.captureChecking
        |import caps.*
        |import dspy4s.harness.guest.*
        |import dspy4s.harness.guest.api.*""".stripMargin,
      s"""val host = dspy4s.harness.repl.HostBindings.take("$key")""",
      "@assumeSafe given turn: Turn = host.turn",
      s"""@assumeSafe def submitValue[A](value: A)(using d: Data[A]): Nothing =
         |  dspy4s.harness.repl.HostBindings.submit("$key", d.encode(value))""".stripMargin
    ) ++ Vector(
      bind("files", "Files", "files", mode.files, read = true),
      bind("shell", "Shell", "shell", mode.shell, read = false),
      bind("llm", "Llm", "llm", mode.llm, read = false),
      bind("state", "Store", "store", mode.store, read = true),
      bind("peers", "Sessions", "sessions", mode.sessions, read = true),
      bind("children", "Children", "children", mode.children, read = false)
    ).flatten

  private def describe(d: Diagnostic): String =
    val pos = d.pos
    if pos.exists then s"Line ${pos.line + 1}: ${d.message}" else d.message

  private def firstLine(text: String): String = text.linesIterator.find(_.trim.nonEmpty).getOrElse("an exception")

  /** Removes the trace that the REPL prints when the stop flag ends a cell. */
  private def withoutStopTrace(output: String): String =
    val lines = output.linesIterator.toVector
    val start = lines.lastIndexWhere(l => l.startsWith("java.lang.ThreadDeath") || l.contains("StopRepl"))
    if start < 0 then output else lines.take(start).mkString("\n").stripTrailing()

  /** Removes the line of the signal of `submit`, and its trace (5.1). */
  private def withoutSignal(output: String): String =
    output.linesIterator.filterNot(l => l.contains("SubmitSignal") || l.trim.startsWith("at ")).mkString("\n")

/** The signal of `submit`. A `ControlThrowable` has no stack trace, and `NonFatal` and `Try` do not catch it. */
final class SubmitSignal extends scala.util.control.ControlThrowable("submit")

/** The bindings of each REPL, with the key of that REPL. The preamble reads them before safe mode starts. */
object HostBindings:
  private val bindings = ConcurrentHashMap[String, Bindings]()
  private val submits  = ConcurrentHashMap[String, Json]()

  def register(key: String, value: Bindings): Unit = bindings.put(key, value): Unit

  def take(key: String): Bindings =
    Option(bindings.get(key)).getOrElse(throw IllegalStateException(s"no bindings for $key"))

  def remove(key: String): Unit =
    bindings.remove(key): Unit
    submits.remove(key): Unit

  /** Records the first value of a cell, and ends the cell. */
  def submit(key: String, value: Json): Nothing =
    submits.putIfAbsent(key, value): Unit
    throw SubmitSignal()

  def submitted(key: String): Option[Json] = Option(submits.get(key))

  def clearSubmit(key: String): Unit = submits.remove(key): Unit

/** The parent class loader of each REPL, as in Phase 0 and atc. It gives the shared packages from the class loader of
  * the host, and the JDK for the other names. It hides the class resources: with the interrupt instrumentation, the
  * REPL loader makes an instrumented copy of each class whose bytecode it can read, and the shared classes must stay
  * the classes of the host, for example the registry of the bindings.
  */
private final class SandboxLoader(app: ClassLoader = classOf[Bindings].getClassLoader)
    extends ClassLoader("repl-sandbox", ClassLoader.getPlatformClassLoader):
  private val shared = List("scala.", "dspy4s.harness.", "zio.blocks.")
  private val hidden = List("scala.quoted.runtime.impl.", "scala.tools.")

  override protected def loadClass(name: String, resolve: Boolean): Class[?] =
    if shared.exists(name.startsWith) && !hidden.exists(name.startsWith) then
      val c = app.loadClass(name)
      if resolve then resolveClass(c)
      c
    else super.loadClass(name, resolve)

  override def getResource(name: String): java.net.URL | Null =
    if name.endsWith(".class") then null else super.getResource(name)

/** Sets `System.out` and `System.err` one time to streams that write to `Console.out` and `Console.err` of the current
  * thread. So a guest `System.out.println` goes to the buffer of its REPL, with no lock (protection 2 of Phase 0).
  */
private object StdRouting:
  @volatile private var installed = false

  def install(): Unit =
    synchronized:
      if !installed then
        val _ = (Console.out, Console.err)
        System.setOut(PrintStream(Forward(() => Console.out), true))
        System.setErr(PrintStream(Forward(() => Console.err), true))
        installed = true

  private final class Forward(target: () => PrintStream) extends OutputStream:
    override def write(b: Int): Unit                             = target().write(b)
    override def write(b: Array[Byte], off: Int, len: Int): Unit = target().write(b, off, len)
    override def flush(): Unit                                   = target().flush()

/** An output buffer that keeps at most `limit` bytes. */
private final class BoundedOutput(limit: Int) extends OutputStream:
  private val buffer = ByteArrayOutputStream(8192)

  override def write(b: Int): Unit = synchronized(if buffer.size() < limit then buffer.write(b))

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    synchronized:
      val room = limit - buffer.size()
      if room > 0 then buffer.write(b, off, math.min(len, room))

  def reset(): Unit = synchronized(buffer.reset())
  def text: String  = synchronized(buffer.toString(UTF_8)).trim
