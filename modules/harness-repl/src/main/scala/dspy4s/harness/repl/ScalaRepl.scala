package dspy4s.harness.repl

import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.dotc.util.SourceFile
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
    network : Option[Network]  = None,
    llm     : Option[Llm]      = None,
    store   : Option[Store]    = None,
    sessions: Option[Sessions] = None,
    children: Option[Children] = None
)

/** The result of one cell: the text that it printed, and its outcome. */
final case class CellRun(output: String, outcome: CellOutcome):

  /** The text of the cell for an agent (6). The MCP server and the harness both show this text, so the two agents see
    * the same cell in the same words:
    *   - for a cell that completed, its output
    *   - for a compile error, the lines of the error with their hints
    *   - for a failure, a timeout or a submit, the output and a note of the outcome
    */
  def text: String =
    def withOutput(note: String) = Vector(output, note).filter(_.nonEmpty).mkString("\n")
    outcome match
      case CellOutcome.Ok                  => output
      case CellOutcome.CompileError(lines) => lines.mkString("\n")
      case CellOutcome.Failed(error)       => withOutput(s"The cell failed: $error")
      case CellOutcome.TimedOut(millis)    => withOutput(s"The cell stopped after $millis ms.")
      case CellOutcome.Submitted(value)    => withOutput(s"The cell submitted ${value.print}.")

/** One REPL for each activation (6), in the same JVM, with capture checking and safe mode. The protections of Phase 0
  * hold:
  *   - one cell at a time on its own thread with `Console.withOut`, and `System.out` routed to the cell
  *   - the stop flag at the timeout, and a catch around the run
  *   - an invalid wrapper for a cell that does not complete or does not compile, so a later cell sees only the
  *     definitions from before it
  *   - the unclean flag for a thread that does not stop, the heap guard, and the cell limit
  *
  * After a compile error that confuses the compiler (`confusesCompiler`), `needsRestart` is true, and the host starts a
  * new REPL. A compile error that shows the effect of the defect (`showsArrayDefect`) also sets it, but only after an
  * earlier cell of `run`.
  *
  * Each guest API call goes through a gate: the clock of the cell stops during a call, and after `submit` the gate
  * refuses each later call of the cell. An interrupt of the thread of `run` stops the cell with the stop flag and an
  * interrupt of the cell thread, waits for the cell as at a timeout, and then goes on to the caller. A cell that the
  * capture checker rejects for the capture set of the enclosing object gets a second try in two parts: the type
  * definitions first, then the rest.
  *
  * @param preamble
  *   the preamble of the session (5.1): its types, `submit` and its inputs
  * @param cellTimeout
  *   the time of the guest code of one cell, with its compile and with no guest API call
  * @param cellLimit
  *   the number of cells after which the REPL is full, and the host starts a new one (protection 5 of Phase 0)
  * @param secondTry
  *   false only for a check of the second try
  */
final class ScalaRepl(
    mode       : Mode,
    bindings   : Bindings,
    preamble   : String         = "",
    cellTimeout: FiniteDuration = 30.seconds,
    cellLimit  : Int            = 300,
    secondTry  : Boolean        = true
) extends AutoCloseable:
  import ScalaRepl.*

  private val absent = absentHandlers(mode, bindings)
  require(absent.isEmpty, s"the mode gives ${absent.mkString(", ")}, and the bindings have no handler for it")

  private val key                                = s"repl-${UUID.randomUUID()}"
  private val capture                            = BoundedOutput(4 * 1024 * 1024)
  private val out                                = PrintStream(capture, true, UTF_8)
  private val driver                             = HarnessDriver(compilerArgs, out, SandboxLoader())
  private val gate                               = CallGate(() => HostBindings.submitted(key).isDefined)
  private var state: State                       = driver.initialState
  private var cells                              = 0
  @volatile private var worker: Thread | Null    = null
  @volatile private var cellStart: Long          = 0L
  @volatile private var uncleanThread: Boolean   = false
  @volatile private var memoryStop: Boolean      = false
  @volatile private var closedForMemory: Boolean = false
  private var confused: Boolean                  = false

  HostBindings.register(key, gated(bindings, gate))
  HeapGuard.watch(this)
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

  /** True when the heap guard stopped a cell of this REPL (protection 4 of Phase 0). The host starts a new REPL. */
  def outOfMemory: Boolean = closedForMemory

  /** True after `cellLimit` cells. The memory of a REPL grows with the square of its cells, so the host starts a new
    * REPL with the preamble, the store and the note of lost names.
    */
  def full: Boolean = cells >= cellLimit

  /** True after a cell with a compile error that confuses the compiler (`confusesCompiler`), and it stays true. Each
    * later Array chain in this REPL fails to compile, so the host starts a new REPL with the preamble, the store and
    * the note of lost names (6).
    *
    * A compile error that shows the effect of the defect (`showsArrayDefect`) also sets it, when an earlier cell of
    * `run` ran in this REPL. The preamble and the givens are not cells of `run`. A cell can cause the defect with no
    * error of its own, so the error of the next Array chain is the first sign. In the first cell, the REPL has no state
    * of an earlier cell, so the same error is a real error, and a new REPL would give it again.
    */
  def needsRestart: Boolean = confused

  /** Steps 2 and 4 of the hard stop (CODE_HARNESS_DESIGN.md, 10.3, phase 7): it interrupts the thread of the running
    * cell, and returns at once. The cell ends with a failure, and the REPL and the definitions of the earlier cells
    * stay. A cell thread that does not end in the wait of `run` makes the REPL `unclean`. With no running cell, it does
    * nothing. The thread that waits in `run` gets no interrupt, so the commits of the worker complete.
    *
    * A proposal for the review: `close` also interrupts the cell, but it removes the bindings, so a stop must not use
    * it.
    */
  def stopCell(): Unit = ???

  def close(): Unit =
    driver.setStopFlag(true)
    val t = worker
    if t != null then t.interrupt()
    HostBindings.remove(key)
    HeapGuard.unwatch(this)

  /** The bytes that the running cell allocated, for the heap guard. */
  private[repl] def allocatedInCell: Option[Long] =
    val t = worker
    if t == null then None else HeapGuard.allocated(t).map(_ - cellStart)

  /** Stops the running cell for the heap guard, and closes the REPL. */
  private[repl] def stopForMemory(): Unit =
    val t = worker
    if t != null then
      memoryStop = true
      closedForMemory = true
      driver.setStopFlag(true)
      t.interrupt()

  private def execute(code: String): CellRun =
    val whole = executeOnce(code)
    whole.outcome match
      case CellOutcome.CompileError(lines) if secondTry && isCaptureOfObject(lines.mkString(" ")) =>
        split(code).fold(whole)(inTwoParts)
      case _ => whole

  /** The second try (6): the type definitions first, then the rest. A failed second part also hides the first. */
  private def inTwoParts(types: String, rest: String): CellRun =
    val before = state
    val first  = executeOnce(types)
    if first.outcome != CellOutcome.Ok then
      state = before.copy(objectIndex = state.objectIndex)
      first
    else
      val typesIndex = state.objectIndex
      val second     = executeOnce(rest)
      if second.outcome != CellOutcome.Ok then invalidate(typesIndex)
      CellRun(Vector(first.output, second.output).filter(_.nonEmpty).mkString("\n"), second.outcome)

  private def executeOnce(code: String): CellRun =
    capture.reset()
    HostBindings.clearSubmit(key)
    gate.reset()
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
    memoryStop = false
    cellStart = HeapGuard.allocated(thread).getOrElse(0L)
    worker = thread
    thread.start()
    val started = System.nanoTime()
    // The clock of the cell counts only the guest code: the gate takes out the time of the guest API calls.
    def guestNanos = System.nanoTime() - started - gate.callNanos
    try
      try while thread.isAlive && guestNanos < cellTimeout.toNanos && !memoryStop do thread.join(10)
      catch
        case interrupt: InterruptedException =>
          // The caller of `run` stops, for example at the end of a program call (9). The stop flag does not reach host
          // code, such as a child of `rlm`, so the cell also gets an interrupt, and it ends before the caller goes on.
          stop(thread, before): Unit
          throw interrupt
      if memoryStop then stoppedForMemory(thread, before)
      else if thread.isAlive then timedOut(thread, before)
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
              // The REPL hides the wrapper of a cell that does not compile (`afterFailedCompilation`).
              case None if parsed.reporter.hasErrors => compileError(capture.text.linesIterator.toVector)
              case None if driver.evaluationThrew    =>
                invalidate(newState.objectIndex)
                CellRun(capture.text, CellOutcome.Failed(firstLine(capture.text)))
              case None => CellRun(capture.text, CellOutcome.Ok)
          case null => CellRun(capture.text, CellOutcome.Failed("the cell gave no result"))
    finally worker = null

  private def stop(thread: Thread, before: State): String =
    driver.setStopFlag(true)
    thread.interrupt()
    thread.join(5000)
    if thread.isAlive then uncleanThread = true
    invalidate(state.objectIndex.max(before.objectIndex + 1))
    withoutStopTrace(capture.text)

  private def timedOut(thread: Thread, before: State): CellRun =
    CellRun(stop(thread, before), CellOutcome.TimedOut(cellTimeout.toMillis))

  private def stoppedForMemory(thread: Thread, before: State): CellRun =
    CellRun(
      stop(thread, before),
      CellOutcome.Failed("the heap was full, so the host stopped the cell and closes the REPL")
    )

  /** Hides the wrapper of a cell that did not complete, as atc does. The earlier definitions stay. */
  private def invalidate(index: Int): Unit =
    state = state.copy(
      objectIndex = state.objectIndex.max(index),
      invalidObjectIndexes = state.invalidObjectIndexes + index
    )

  private def compileError(diagnostics: Vector[String]): CellRun =
    val text = diagnostics.mkString("\n")
    // `run` counts the current cell too, so `cells > 1` means an earlier cell of `run`.
    if confusesCompiler(text) || (cells > 1 && showsArrayDefect(text)) then confused = true
    CellRun(text, CellOutcome.CompileError(diagnostics ++ hint(text).map(h => s"Hint: $h")))

  /** The type definitions and the rest of a cell, with the imports in both parts, or `None` when a part is empty. */
  private def split(code: String): Option[(String, String)] =
    given Context = state.context
    ParseResult(SourceFile.virtual("cell", code))(using state) match
      case parsed: Parsed =>
        val trees   = parsed.trees
        val imports = trees.collect { case i: untpd.Import => i }
        val types   = trees.filter {
          case _: untpd.TypeDef | _: untpd.ModuleDef => true
          case _                                     => false
        }
        val rest = trees.filter {
          case _: untpd.TypeDef | _: untpd.ModuleDef | _: untpd.Import => false
          case _                                                       => true
        }
        def text(part: List[untpd.Tree]): String = (imports ++ part).map(t =>
          code.substring(t.sourcePos.start, t.sourcePos.end)
        ).mkString("\n")
        if types.isEmpty || rest.isEmpty then None else Some((text(types), text(rest)))
      case _ => None

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
    * from the registry with the key of this REPL. Each given reads its handler inline from the registry, before safe
    * mode starts, so a cell cannot name the bindings.
    *
    * The preamble has no top-level val of the `Bindings`. With such a val, the capture checker of the 3.10 nightly gave
    * each Array result of a later cell in safe mode a capture set, so `xs.map(f).sum` did not compile.
    */
  def givensFor(mode: Mode, key: String): Vector[String] =
    val bindings = s"""dspy4s.harness.repl.HostBindings.take("$key")"""

    def bind(name: String, tpe: String, field: String, access: Access, read: Boolean): Option[String] =
      access match
        case Access.Full               => Some(s"@assumeSafe given $name: ($tpe^) = $bindings.$field.get")
        case Access.Read if read       => Some(s"@assumeSafe given $name: $tpe = $bindings.$field.get")
        case Access.Read | Access.None => None
    Vector(
      """import language.experimental.captureChecking
        |import caps.*
        |import dspy4s.harness.guest.*
        |import dspy4s.harness.guest.api.*""".stripMargin,
      s"@assumeSafe given turn: Turn = $bindings.turn",
      s"""@assumeSafe def submitValue[A](value: A)(using d: Data[A]): Nothing =
         |  dspy4s.harness.repl.HostBindings.submit("$key", d.encode(value))""".stripMargin
    ) ++ Vector(
      bind("files", "Files", "files", mode.files, read = true),
      bind("shell", "Shell", "shell", mode.shell, read = false),
      bind("net", "Network", "network", mode.network, read = false),
      bind("llm", "Llm", "llm", mode.llm, read = false),
      bind("state", "Store", "store", mode.store, read = true),
      bind("peers", "Sessions", "sessions", mode.sessions, read = true),
      bind("children", "Children", "children", mode.children, read = false)
    ).flatten

  /** The capabilities that the mode gives and that the bindings do not have. */
  private def absentHandlers(mode: Mode, b: Bindings): Vector[String] =
    def needs(access: Access, read: Boolean): Boolean = access == Access.Full || (read && access == Access.Read)
    Vector(
      ("files", needs(mode.files, read = true), b.files.isDefined),
      ("shell", needs(mode.shell, read = false), b.shell.isDefined),
      ("network", needs(mode.network, read = false), b.network.isDefined),
      ("llm", needs(mode.llm, read = false), b.llm.isDefined),
      ("store", needs(mode.store, read = true), b.store.isDefined),
      ("sessions", needs(mode.sessions, read = true), b.sessions.isDefined),
      ("children", needs(mode.children, read = false), b.children.isDefined)
    ).collect { case (name, needed, present) if needed && !present => name }

  /** The error of the capture checker that the second try answers (6). Its text spans two lines of the diagnostic. */
  private def isCaptureOfObject(text: String): Boolean =
    text.contains("is not included in the allowed capture set") && text.contains("of the enclosing object")

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

  /** Records the first value of a cell, and ends the cell. A second `submit` of the cell is an error (5.1). */
  def submit(key: String, value: Json): Nothing =
    if submits.putIfAbsent(key, value) != null then
      throw IllegalStateException("submit: this cell has called submit, so it can make no more calls")
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
