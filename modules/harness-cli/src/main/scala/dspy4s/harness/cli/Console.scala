package dspy4s.harness.cli

import dspy4s.harness.host.Activity
import org.jline.reader.impl.history.DefaultHistory
import org.jline.reader.impl.{DefaultParser, LineReaderImpl}
import org.jline.reader.{EndOfFileException, LineReader, UserInterruptException}
import org.jline.terminal.{Attributes, Terminal, TerminalBuilder}
import org.jline.utils.InfoCmp

import java.io.{BufferedReader, IOException, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{Executors, RejectedExecutionException, TimeUnit}
import scala.annotation.tailrec
import scala.util.Try

/** What the person typed: a line, a Ctrl-C on an empty line, or the end of the input. */
enum Typed derives CanEqual:
  case Text(line: String)
  case Interrupt
  case End

/** The terminal of the person for `Cli.loop` (CODE_HARNESS_DESIGN.md, 10.6): the lines that the person types, and the
  * text of the session. The loop reads on one thread and prints on another thread.
  */
trait ClientConsole extends AutoCloseable:

  /** The next line. It waits for the person. */
  def read(): Typed

  /** Prints `text`, above the line that the person types when the console has one. */
  def print(text: String): Unit

  /** Runs `action` at each Ctrl-C that comes while no read waits, for example during `/wait`. A console with no such
    * key does nothing.
    */
  def onInterrupt(action: () => Unit): Unit = ()

  /** Shows what the session does now (`Busy`), or that no turn is open. A console with no line editor shows nothing. */
  def busy(next: Option[Busy]): Unit = ()

  /** Sets where the console reads what the host does now (`SessionHandle.activity`). A console with no line editor
    * reads nothing.
    */
  def activity(of: () => Option[Activity]): Unit =
    val _ = of

  def close(): Unit = ()

/** A console on plain streams, with no line editor: for a script, a pipe and the laws. An error of `in` is the end of
  * the input.
  */
final class PlainConsole(in: BufferedReader, out: PrintStream) extends ClientConsole:

  def read(): Typed =
    try Option(in.readLine()).fold(Typed.End)(Typed.Text(_))
    catch case _: IOException => Typed.End

  def print(text: String): Unit = out.synchronized(out.println(text))

/** A console with the line editor of JLine, for a person at a terminal (10.6).
  *
  *   - The person can edit the line, and the up and down keys give the earlier lines.
  *   - The history file keeps the lines after `mask`, so a known secret does not go into the file (5.6).
  *   - Enter after an odd number of `\` at the end of the line changes the last `\` into a new line, and the line goes
  *     on. A paste goes into the line as it is, so a pasted `\` stays.
  *   - The editor turns off the signals of the terminal (ISIG), so Ctrl-C is a key and never a signal. Ctrl-C on an
  *     empty line gives `Interrupt`. Ctrl-C on a line with text clears the text, and the read goes on. A Ctrl-C while
  *     no read waits stays in the input, and the next read gets it. So the terminal never sends SIGINT to the process
  *     group, which holds the wrappers of the jobs (10.3). A SIGINT from outside, as `kill -INT`, runs the action of
  *     `onInterrupt`.
  *   - Ctrl-D on an empty line gives `End`.
  *   - `print` writes above the line that the person types, so the text of the session does not break that line.
  *   - During a turn (`busy`), the prompt shows a spinner and the time since the start of what runs, and the right side
  *     of the line shows its label (`Busy`). The cursor is a blinking block. A timer redraws the line every
  *     `Busy.FrameMillis` through the widget `BusyWidget`, because JLine changes the line of a read only with its lock.
  *     A `%` of a label stays as it is. With no open turn, the prompt is `> `, and the cursor has the shape that the
  *     terminal gives it.
  *   - `close` first ends a read that waits, at most `CloseWait`. So JLine turns off the modes that its read turned on,
  *     for example bracketed paste, before the terminal goes back to the shell. It gives the cursor its shape back. A
  *     read after `close` gives `End`.
  */
final class EditorConsole private (reader: LineReaderImpl) extends ClientConsole:
  import EditorConsole.*

  private val terminal          = reader.getTerminal
  private val lock              = Object()
  private var reading           = false // only with `lock`
  @volatile private var closing = false

  /** What the session does now, and whether the cursor is a blinking block. */
  @volatile private var current: Option[Busy] = None
  private val blinking                        = AtomicBoolean(false)

  /** Where the console reads what the host does now. */
  @volatile private var activityOf: () => Option[Activity] = () => None

  private val ticker = Executors.newSingleThreadScheduledExecutor { task =>
    Thread.ofPlatform().daemon(true).name("harness-cli-busy").unstarted(task)
  }
  reader.getWidgets.put(BusyWidget, () => redraw()): Unit
  ticker.scheduleAtFixedRate(() => tick(), FrameMillis, FrameMillis, TimeUnit.MILLISECONDS): Unit

  def read(): Typed =
    val go = lock.synchronized {
      if !closing then reading = true
      !closing
    }
    if !go then Typed.End
    else
      try typedLine()
      finally lock.synchronized {
          reading = false
          lock.notifyAll()
        }

  /** One line. A Ctrl-C on a line with text only clears the text, so the read goes on. */
  @tailrec private def typedLine(): Typed =
    val typed =
      cursor(current.isDefined)
      try Some(Typed.Text(reader.readLine(promptNow(), rightPromptNow(), null: Character, null: String)))
      catch
        case e: UserInterruptException =>
          if closing then Some(Typed.End) else Option.when(Option(e.getPartialLine).forall(_.isEmpty))(Typed.Interrupt)
        case _: EndOfFileException => Some(Typed.End)
    typed match
      case Some(result) => result
      case None         => typedLine()

  def print(text: String): Unit =
    if !closing then
      try reader.printAbove(text)
      catch case _: IllegalStateException => () // The terminal closed during the print.

  override def onInterrupt(action: () => Unit): Unit =
    terminal.handle(Terminal.Signal.INT, _ => if !closing then action()): Unit

  override def activity(of: () => Option[Activity]): Unit = activityOf = of

  override def busy(next: Option[Busy]): Unit =
    current = next
    // The line shows the change at once, not at the next tick.
    try ticker.execute(() => tick())
    catch case _: RejectedExecutionException => () // The console closed.

  /** Redraws the line of a read that waits, while a turn is open or the cursor is still a blinking block. With no read,
    * the next read shows it.
    */
  private def tick(): Unit =
    if !closing && (current.isDefined || blinking.get) then
      try reader.callWidget(BusyWidget)
      catch case _: IllegalStateException => () // No read waits.

  /** The widget of `tick`: the two prompts of `current`, the shape of the cursor, and a redraw of the line. It runs
    * with the lock of the reader.
    */
  private def redraw(): Boolean =
    val now = current
    reader.setPrompt(promptOf(now))
    reader.setRightPrompt(rightPromptOf(now))
    cursor(now.isDefined)
    reader.callWidget(LineReader.REDISPLAY)
    true

  private def promptNow(): String      = promptOf(current)
  private def rightPromptNow(): String = rightPromptOf(current)

  /** The prompt of `busy`. JLine reads a `%` of a prompt as a pattern, so each `%` is `%%`. */
  private def promptOf(busy: Option[Busy]): String =
    Busy.prompt(busy, System.currentTimeMillis()).replace("%", "%%")

  /** The right prompt of `busy` and of the activity of the host, at most half of the width of the terminal. A failed
    * read of the activity shows no activity.
    */
  private def rightPromptOf(busy: Option[Busy]): String =
    val width    = terminal.getSize.getColumns
    val activity = if busy.exists(_.command) then Try(activityOf()).toOption.flatten else None
    Busy.rightPrompt(busy, activity, if width > 0 then (width / 2).max(MinRight) else DefaultRight).replace("%", "%%")

  /** Sets the cursor to a blinking block, or gives it the shape of the terminal, when that is a change. */
  private def cursor(blink: Boolean): Unit =
    if blinking.compareAndSet(!blink, blink) then
      Try {
        terminal.writer().write(if blink then BlinkingBlock else CursorDefault)
        terminal.flush()
      }: Unit

  /** Ends a read that waits, saves the history, and gives the terminal back to the shell. A second call does nothing.
    */
  override def close(): Unit =
    val first = lock.synchronized {
      val was = closing
      closing = true
      !was
    }
    if first then
      ticker.shutdownNow(): Unit
      val deadline = System.nanoTime() + CloseWait
      lock.synchronized {
        // The read may not wait for a key yet, so the interrupt comes again until the read ends.
        while reading && System.nanoTime() < deadline do
          Try(terminal.raise(Terminal.Signal.INT)): Unit
          lock.wait(50)
      }
      Try(reader.getHistory.save()): Unit
      // A read that ends turns off the modes that it turned on. At a SIGTERM, the shutdown hook of JLine can close the
      // terminal before that, so the client turns the modes off too. When the terminal is closed, the text goes to the
      // standard output, which is the same terminal.
      val off = Try {
        terminal.writer().write(BracketedPasteOff + CursorDefault)
        terminal.puts(InfoCmp.Capability.keypad_local): Unit
        terminal.flush()
      }
      if off.isFailure then
        Try {
          System.out.print(BracketedPasteOff + CursorDefault + KeypadLocal)
          System.out.flush()
        }: Unit
      Try(terminal.close()): Unit

object EditorConsole:

  /** The prompt of each line after a line that ends with `\`. */
  private val GoesOn    = "  "
  private val MostLines = 1000

  /** The text that turns bracketed paste off, and the text of an xterm that turns the mode of the keypad off. */
  private val BracketedPasteOff = "\u001b[?2004l"
  private val KeypadLocal       = "\u001b[?1l\u001b>"

  /** The texts that make the cursor a blinking block and that give it the shape of the terminal (DECSCUSR). */
  private val BlinkingBlock = "\u001b[1 q"
  private val CursorDefault = "\u001b[0 q"

  /** The name of the widget that redraws the busy line, and the time between two redraws. */
  private val BusyWidget  = "dspy4s-busy"
  private val FrameMillis = 125L

  /** The least width of the right prompt, and its width on a terminal with no known width. */
  private val MinRight     = 20
  private val DefaultRight = 40

  /** The longest wait of `close` for a read to end, in nanoseconds. */
  private val CloseWait = 2_000_000_000L

  /** A console on the terminal of this process, or none when the input or the output is not a terminal, for example a
    * pipe. The caller then uses `PlainConsole`.
    *
    * JLine asks the terminal for its modes in `build`, and it reads the answers from the input. A line that the person
    * types before the end of `build` is lost. So the client makes its console before it starts the host, and it tells
    * the person to type only after that.
    */
  def system(history: Path, mask: String => String): Option[EditorConsole] =
    // With software signals, JLine 4 raises INT for a Ctrl-C key and also passes the key on, so one Ctrl-C comes two
    // times. The editor needs only the key. A value that the person set stays.
    if System.getProperty(TerminalBuilder.PROP_SOFTWARE_SIGNALS) == null then
      System.setProperty(TerminalBuilder.PROP_SOFTWARE_SIGNALS, "false"): Unit
    // On JDK 21 a pipe gives no `System.console()`, so the client does not start JLine for a pipe. On a later JDK the
    // console can exist for a pipe too, and JLine then gives a dumb terminal.
    Option(System.console())
      .flatMap(_ =>
        Try(TerminalBuilder.builder().name("dspy4s-harness").system(true).dumb(true).encoding(UTF_8).build()).toOption
      )
      .flatMap { terminal =>
        if terminal.getType == Terminal.TYPE_DUMB || terminal.getType == Terminal.TYPE_DUMB_COLOR then
          terminal.close()
          None
        else Some(on(terminal, history, mask))
      }

  /** A console on `terminal`, with its history in the file `history`. Each line goes through `mask` before it goes into
    * the history.
    */
  def on(terminal: Terminal, history: Path, mask: String => String): EditorConsole =
    // The client does not split a line into words, so a quote does not hold the line open, and a `\` before the end of
    // the line is a plain character.
    val parser = DefaultParser()
    parser.setEofOnUnclosedQuote(false)
    parser.setEofOnEscapedNewLine(false)
    val variables = java.util.HashMap[String, AnyRef]()
    variables.put(LineReader.HISTORY_FILE, history): Unit
    variables.put(LineReader.HISTORY_FILE_SIZE, Integer.valueOf(MostLines)): Unit
    variables.put(LineReader.HISTORY_SIZE, Integer.valueOf(MostLines)): Unit
    variables.put(LineReader.SECONDARY_PROMPT_PATTERN, GoesOn): Unit
    // The client makes the reader as `LineReaderBuilder.build` does. The builder gives only the interface, and the busy
    // line needs `setPrompt` of the implementation.
    val reader = LineReaderImpl(terminal, "dspy4s-harness", variables)
    reader.setHistory(MaskedHistory(mask))
    reader.setParser(parser)
    // With no event expansion, a `!` in a message stays as it is, and JLine keeps each `\`.
    reader.option(LineReader.Option.DISABLE_EVENT_EXPANSION, true): Unit
    val accept = reader.getWidgets.get(LineReader.ACCEPT_LINE)
    reader.getWidgets.put(LineReader.ACCEPT_LINE, () => goesOn(reader) || accept.apply()): Unit
    // With no signals, a Ctrl-C is a key. A read of JLine keeps these attributes, and it gives them back at its end.
    // The close of a system terminal gives the shell the attributes from before the client.
    val attributes = terminal.getAttributes
    attributes.setLocalFlag(Attributes.LocalFlag.ISIG, false)
    terminal.setAttributes(attributes)
    EditorConsole(reader)

  /** At Enter after an odd number of `\` at the end of the line, the last `\` becomes a new line, and the line goes on.
    * An even number is a text that ends with `\`, so Enter sends it. A paste does not come here.
    */
  private def goesOn(reader: LineReader): Boolean =
    val buffer  = reader.getBuffer
    val slashes = buffer.toString.reverseIterator.takeWhile(_ == '\\').size
    if buffer.cursor == buffer.length && slashes % 2 == 1 then
      buffer.backspace(): Unit
      buffer.write("\n")
      true
    else false

  /** A history that keeps each line after `mask`. */
  private final class MaskedHistory(mask: String => String) extends DefaultHistory:
    override def add(time: Instant, line: String): Unit = super.add(time, mask(line))
