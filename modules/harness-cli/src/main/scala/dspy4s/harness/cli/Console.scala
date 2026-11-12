package dspy4s.harness.cli

import org.jline.reader.impl.completer.{AggregateCompleter, ArgumentCompleter, NullCompleter, StringsCompleter}
import org.jline.reader.impl.history.DefaultHistory
import org.jline.reader.impl.{DefaultParser, LineReaderImpl}
import org.jline.reader.{Completer, EndOfFileException, LineReader, Reference, UserInterruptException}
import org.jline.keymap.KeyMap
import org.jline.terminal.{Attributes, Size, Terminal, TerminalBuilder}
import org.jline.utils.{AttributedString, AttributedStringBuilder, AttributedStyle, InfoCmp, Status as JlineStatus}

import java.io.{BufferedReader, IOException, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{Executors, RejectedExecutionException, TimeUnit}
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
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

  /** True for a console with the screen of CLI_SCREEN.md: the live block, the footer, and the rows with tones. */
  def rich: Boolean = false

  /** The glyphs of the rows of this console. */
  def glyphs: Glyphs = Glyphs.unicode

  /** Prints `rows` above the line that the person types. A console with no tones prints the plain text of the rows. */
  def printRows(rows: Vector[Row]): Unit = if rows.nonEmpty then print(rows.map(_.plain).mkString("\n"))

  /** Sets where the console reads the live block and the footer. A console with no line editor reads nothing. */
  def watch(live: () => Live, footer: () => Option[Row]): Unit =
    val _ = (live, footer)

  /** Runs `action` at each Ctrl-O. A console with no such key does nothing. */
  def onDetail(action: () => Unit): Unit =
    val _ = action

  def close(): Unit = ()

/** A console on plain streams, with no line editor: for a script, a pipe and the laws. An error of `in` is the end of
  * the input.
  */
final class PlainConsole(in: BufferedReader, out: PrintStream) extends ClientConsole:

  def read(): Typed =
    try Option(in.readLine()).fold(Typed.End)(Typed.Text(_))
    catch case _: IOException => Typed.End

  def print(text: String): Unit = out.synchronized(out.println(text))

/** A console with the line editor of JLine, for a person at a terminal (10.6), with the screen of CLI_SCREEN.md.
  *
  *   - The person can edit the line, and the up and down keys give the earlier lines. Tab completes the commands, the
  *     lifetimes after `/allow` and the views after `/reasoning` (`completer`).
  *   - The history file keeps the lines after `mask`, so a known secret does not go into the file (5.6).
  *   - Enter after an odd number of `\` at the end of the line changes the last `\` into a new line, and the line goes
  *     on. A paste goes into the line as it is, so a pasted `\` stays.
  *   - The editor turns off the signals of the terminal (ISIG), so Ctrl-C is a key and never a signal. Ctrl-C on an
  *     empty line gives `Interrupt`. Ctrl-C on a line with text clears the text, and the read goes on. A Ctrl-C while
  *     no read waits stays in the input, and the next read gets it. So the terminal never sends SIGINT to the process
  *     group, which holds the wrappers of the jobs (10.3). A SIGINT from outside, as `kill -INT`, runs the action of
  *     `onInterrupt`.
  *   - Ctrl-D on an empty line gives `End`. Ctrl-O runs the action of `onDetail`.
  *   - `print` and `printRows` write above the live block, so the text of the session does not break the line of the
  *     person.
  *   - The live block (`watch`) and a rule are the prompt of the read, above `› `. JLine changes the line of a read
  *     only with its lock, so a timer redraws it every `FrameMillis` through the widget `BusyWidget`, when it changed.
  *     A `%` of a row stays as it is. Before Enter accepts a line, the prompt is only `› `, so the history keeps no
  *     live block.
  *   - The footer is one line of a JLine `Status` at the bottom of the terminal. The status gets this row when the
  *     console starts, and it keeps it with a blank row when there is no footer. So its height does not change during a
  *     read.
  *   - At a resize of the window, the reader draws the full prompt again from its first row, with the rows of the new
  *     width (`Reader`).
  *   - During a turn the cursor is a blinking block, and the window title is the title of the live block.
  *   - With `color` off, each tone has no color.
  *   - `close` first ends a read that waits, at most `CloseWait`. So JLine turns off the modes that its read turned on,
  *     for example bracketed paste, before the terminal goes back to the shell. It gives the cursor its shape back, and
  *     it removes the footer. A read after `close` gives `End`.
  */
final class EditorConsole private (reader: LineReaderImpl, color: Boolean, override val glyphs: Glyphs)
    extends ClientConsole:
  import EditorConsole.*

  private val terminal          = reader.getTerminal
  private val lock              = Object()
  private var reading           = false // only with `lock`
  @volatile private var closing = false

  // Where the console reads the screen, and what the terminal shows now.
  @volatile private var liveOf: () => Live          = () => Idle
  @volatile private var footerOf: () => Option[Row] = () => None
  @volatile private var detailAction: () => Unit    = () => ()
  @volatile private var shownPrompt                 = ""
  @volatile private var shownTitle                  = ""
  @volatile private var shownFooter                 = AttributedString.EMPTY
  private val blinking                              = AtomicBoolean(false)
  private val status                                = Try(Option(JlineStatus.getStatus(terminal))).toOption.flatten
  // The footer gets its row before the first read draws its prompt (`showFooter`).
  showFooter(AttributedString.EMPTY)

  private val ticker = Executors.newSingleThreadScheduledExecutor { task =>
    Thread.ofPlatform().daemon(true).name("harness-cli-screen").unstarted(task)
  }

  reader.getWidgets.put(BusyWidget, () => redraw(force = false)): Unit
  reader.getWidgets.put(ResizeWidget, () => resized()): Unit
  reader.getWidgets.put(
    DetailWidget,
    () =>
      Try(detailAction()): Unit
      redraw(force = true)
  ): Unit
  Vector(LineReader.EMACS, LineReader.MAIN).flatMap(name => Option(reader.getKeyMaps.get(name))).foreach(
    _.bind(Reference(DetailWidget), KeyMap.ctrl('O'))
  )
  private val accept = reader.getWidgets.get(LineReader.ACCEPT_LINE)
  reader.getWidgets.put(
    LineReader.ACCEPT_LINE,
    () =>
      goesOn(reader) || {
        // The history keeps only the line of the person, with no live block.
        reader.setPrompt(markText)
        shownPrompt = markText
        reader.callWidget(LineReader.REDISPLAY)
        accept.apply()
      }
  ): Unit
  ticker.scheduleAtFixedRate(() => tick(), FrameMillis, FrameMillis, TimeUnit.MILLISECONDS): Unit

  override def rich: Boolean = true

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
      val live   = current()
      val prompt = promptOf(live)
      shownPrompt = prompt
      cursor(live.busy)
      title(live.title)
      try Some(Typed.Text(reader.readLine(prompt, "", null: Character, null: String)))
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

  /** Prints `rows` above the live block. The loop changes the screen before it prints, and JLine draws the prompt again
    * after a print. So the console first sets the prompt of the new screen, and the print shows it. Else a row that
    * ends the turn, for example `Done`, would come with the old status line under it.
    */
  override def printRows(rows: Vector[Row]): Unit =
    if rows.nonEmpty then
      tick()
      print(rows.map(ansi).mkString("\n"))

  override def onInterrupt(action: () => Unit): Unit =
    terminal.handle(Terminal.Signal.INT, _ => if !closing then action()): Unit

  override def watch(live: () => Live, footer: () => Option[Row]): Unit =
    liveOf = live
    footerOf = footer
    kick()

  override def onDetail(action: () => Unit): Unit = detailAction = action

  /** Redraws the screen at once, not at the next tick. */
  private def kick(): Unit =
    try ticker.execute(() => tick())
    catch case _: RejectedExecutionException => () // The console closed.

  /** Redraws the line of a read that waits. With no read, the next read shows the screen. */
  private def tick(): Unit =
    if !closing then
      try reader.callWidget(BusyWidget)
      catch case _: IllegalStateException => () // No read waits.

  /** The widget of `tick`: the live block and the rule as the prompt, the footer, the cursor and the title. It runs
    * with the lock of the reader. It redraws the line only when the prompt changed, or with `force`.
    */
  private def redraw(force: Boolean): Boolean =
    val live   = current()
    val prompt = promptOf(live)
    cursor(live.busy)
    title(live.title)
    footer()
    if force || prompt != shownPrompt then
      shownPrompt = prompt
      reader.setPrompt(prompt)
      reader.setRightPrompt("")
      reader.callWidget(LineReader.REDISPLAY)
    true

  /** The widget of a resize: it sets the prompt of the new width, and JLine then draws it (`Reader`). So no row of the
    * old width wraps on a smaller window.
    */
  private def resized(): Boolean =
    val prompt = promptOf(current())
    shownPrompt = prompt
    reader.setPrompt(prompt)
    true

  /** The live block now. A failed read of the screen shows no live block. */
  private def current(): Live = Try(liveOf()).getOrElse(Idle)

  /** The prompt of `live`: each row of the live block, cut to the width of the terminal, the rule, and `› `. JLine
    * reads a `%` of a prompt as a pattern, so each `%` is `%%`.
    */
  private def promptOf(live: Live): String =
    val width = columns() - 1
    val rule  = Row.of(glyphs.rule * width, Tone.Dim)
    ((live.rows :+ rule).map(row => styled(row).columnSubSequence(0, width).toAnsi(terminal)) :+ markText)
      .mkString("\n")
      .replace("%", "%%")

  /** The prompt mark `› ` in the accent tone, with no `%`. */
  private def markText: String = styled(Row.of(s"${glyphs.prompt} ", Tone.Accent)).toAnsi(terminal)

  private def columns(): Int =
    val width = terminal.getSize.getColumns
    if width > 0 then width else DefaultColumns

  /** Shows the footer of `footerOf`, when its line changed. The line is cut to the width, so a resize can change it. */
  private def footer(): Unit =
    val line = footerLine(Try(footerOf()).toOption.flatten)
    if !line.equals(shownFooter) then showFooter(line)

  /** The line of the footer `row`, cut to the width of the terminal, or a blank line for no footer. */
  private def footerLine(row: Option[Row]): AttributedString =
    row.fold(AttributedString.EMPTY)(r => styled(r).columnSubSequence(0, columns() - 1))

  /** Shows `line` in the status. For no footer, the line is blank, so the status always has one row. When the height of
    * the status changes during a read, JLine forgets the lines that it drew (`LineReaderImpl.redisplay`). It then draws
    * the full prompt again from the row of the cursor. That row is the last row of the prompt, so the old live block
    * and the old rule stay above it.
    */
  private def showFooter(line: AttributedString): Unit =
    shownFooter = line
    status.foreach(s => Try(s.update(Vector(line).asJava)): Unit)

  /** Sets the title of the window, when it changed. The title has no control character (`Screen.live`). */
  private def title(text: String): Unit =
    if text.nonEmpty && text != shownTitle then
      shownTitle = text
      Try {
        terminal.writer().write(s"\u001b]0;$text\u0007")
        terminal.flush()
      }: Unit

  /** Sets the cursor to a blinking block, or gives it the shape of the terminal, when that is a change. */
  private def cursor(blink: Boolean): Unit =
    if blinking.compareAndSet(!blink, blink) then
      Try {
        terminal.writer().write(if blink then BlinkingBlock else CursorDefault)
        terminal.flush()
      }: Unit

  /** The text of `row` with the codes of its tones, for `printAbove`. */
  private def ansi(row: Row): String = styled(row).toAnsi(terminal)

  /** `row` with a style for each tone, or with no style when `color` is off. */
  private def styled(row: Row): AttributedString =
    val builder = AttributedStringBuilder()
    row.spans.foreach(span => builder.styled(if color then styleOf(span.tone) else AttributedStyle.DEFAULT, span.text))
    builder.toAttributedString

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
      // `Status.close` gives back only the scroll region, and the text of the footer stays on the last row. So the
      // console first removes the row of the status with an update with no lines, as `Status.hide` does.
      val footerShown = status.exists(_.size > 0)
      status.foreach { s =>
        Try(s.update(java.util.Collections.emptyList())): Unit
        Try(s.close()): Unit
      }
      // A read that ends turns off the modes that it turned on. At a SIGTERM, the shutdown hook of JLine can close the
      // terminal before that, so the client turns the modes off too, and it clears the last row, which has the footer.
      // When the terminal is closed, the text goes to the standard output, which is the same terminal.
      val off = Try {
        terminal.writer().write(BracketedPasteOff + CursorDefault)
        terminal.puts(InfoCmp.Capability.keypad_local): Unit
        terminal.flush()
      }
      if off.isFailure then
        Try {
          System.out.print(BracketedPasteOff + CursorDefault + KeypadLocal + (if footerShown then ClearLastRow else ""))
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

  /** The text that clears the last row of the screen and keeps the cursor where it is. The terminal moves a cursor to
    * row 999 only as far as its last row.
    */
  private val ClearLastRow = "\u001b7\u001b[999;1H\u001b[2K\u001b8"

  /** The texts that make the cursor a blinking block and that give it the shape of the terminal (DECSCUSR). */
  private val BlinkingBlock = "\u001b[1 q"
  private val CursorDefault = "\u001b[0 q"

  /** The names of the widgets that redraw the screen, that switch the detail and that set the prompt after a resize,
    * and the time between two redraws.
    */
  private val BusyWidget   = "dspy4s-screen"
  private val DetailWidget = "dspy4s-detail"
  private val ResizeWidget = "dspy4s-resize"
  private val FrameMillis  = 125L

  /** The width of a terminal that gives no width. */
  private val DefaultColumns = 80

  /** The live block of a session with no open turn. */
  private val Idle = Live(Vector.empty, busy = false, title = "")

  /** The longest wait of `close` for a read to end, in nanoseconds. */
  private val CloseWait = 2_000_000_000L

  /** The style of each tone: only the 16 ANSI colors, so each theme of a terminal works (CLI_SCREEN.md, 9.1). */
  private def styleOf(tone: Tone): AttributedStyle =
    tone match
      case Tone.Plain  => AttributedStyle.DEFAULT
      case Tone.Dim    => AttributedStyle.DEFAULT.faint()
      case Tone.Accent => AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)
      case Tone.Good   => AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN)
      case Tone.Bad    => AttributedStyle.DEFAULT.foreground(AttributedStyle.RED)
      case Tone.Warn   => AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW)

  /** The completion of a line: each command, the lifetimes after `/allow`, and the views after `/reasoning`. */
  private[cli] val completer: Completer =
    val words     = commandHelp.map(_._1.takeWhile(_ != ' ')).distinct.filterNot(Set("/allow", "/reasoning"))
    val lifetimes = StringsCompleter("once", "turn", "session")
    // `/allow N once`: the number is free text, so this completer does not check the words before the lifetime.
    val numbered =
      ArgumentCompleter(StringsCompleter("/allow"), NullCompleter.INSTANCE, lifetimes, NullCompleter.INSTANCE)
    numbered.setStrict(false)
    AggregateCompleter(
      ArgumentCompleter(StringsCompleter("/allow"), lifetimes, NullCompleter.INSTANCE),
      numbered,
      ArgumentCompleter(
        StringsCompleter("/reasoning"),
        StringsCompleter(ReasoningView.values.map(_.toString.toLowerCase)*),
        NullCompleter.INSTANCE
      ),
      ArgumentCompleter(StringsCompleter(words*), NullCompleter.INSTANCE)
    )

  /** A console on the terminal of this process, or none when the input or the output is not a terminal, for example a
    * pipe. The caller then uses `PlainConsole`. A terminal with no UTF-8 gets the ASCII glyphs.
    *
    * JLine asks the terminal for its modes in `build`, and it reads the answers from the input. A line that the person
    * types before the end of `build` is lost. So the client makes its console before it starts the host, and it tells
    * the person to type only after that.
    */
  def system(history: Path, mask: String => String, color: Boolean = true): Option[EditorConsole] =
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
        else
          val glyphs = if UTF_8.equals(terminal.encoding()) then Glyphs.unicode else Glyphs.ascii
          Some(on(terminal, history, mask, color, glyphs))
      }

  /** A console on `terminal`, with its history in the file `history`. Each line goes through `mask` before it goes into
    * the history.
    */
  def on(
      terminal: Terminal,
      history : Path,
      mask    : String => String,
      color   : Boolean = true,
      glyphs  : Glyphs  = Glyphs.unicode
  ): EditorConsole =
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
    // The client makes the reader as `LineReaderBuilder.build` does. The builder gives only the interface, the live
    // block needs `setPrompt` of the implementation, and a resize needs the subclass `Reader`.
    val reader = Reader(terminal, variables)
    reader.setHistory(MaskedHistory(mask))
    reader.setParser(parser)
    reader.setCompleter(completer)
    // With no event expansion, a `!` in a message stays as it is, and JLine keeps each `\`.
    reader.option(LineReader.Option.DISABLE_EVENT_EXPANSION, true): Unit
    // With no signals, a Ctrl-C is a key. A read of JLine keeps these attributes, and it gives them back at its end.
    // The close of a system terminal gives the shell the attributes from before the client.
    val attributes = terminal.getAttributes
    attributes.setLocalFlag(Attributes.LocalFlag.ISIG, false)
    terminal.setAttributes(attributes)
    EditorConsole(reader, color, glyphs)

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

  /** A reader that draws the full prompt again from its first row at a resize of the window.
    *
    * At a resize with a status, `LineReaderImpl.handleSignal` makes a new display, and it draws the full prompt again
    * from the cursor, as if the cursor were at the start of the prompt. The cursor of the editor is on the last row of
    * its prompt, after the text of the person. So this reader first gives the new size to the old display: a terminal
    * wraps the long rows again at the new width, and the display does the same. It then erases the rows of the prompt
    * and puts the cursor at the start of the prompt, as `printAbove` does. Last, the widget `ResizeWidget` sets the
    * prompt of the new width, and JLine draws it.
    */
  private final class Reader(term: Terminal, values: java.util.Map[String, AnyRef])
      extends LineReaderImpl(term, "dspy4s-harness", values):

    // The monitor comes before the lock, as in `handleSignal` and `redisplay` of JLine. The lock keeps the timer out
    // until JLine draws the prompt again.
    override protected def handleSignal(signal: Terminal.Signal): Unit =
      synchronized {
        lock.lock()
        try
          if Terminal.Signal.WINCH.equals(signal) && isReading then
            val grid = terminal.getBufferSize
            if redraws(grid) then
              Option(display).foreach { old =>
                old.resize(grid)
                old.update(java.util.Collections.emptyList(), 0)
              }
              callWidget(ResizeWidget)
          super.handleSignal(signal)
        finally lock.unlock()
      }

    /** True when JLine draws the full prompt again at a resize to `grid`: the size changes, and the status has rows. */
    private def redraws(grid: Size): Boolean =
      val changed = grid.getRows != size.getRows || grid.getColumns != size.getColumns
      changed && Option(JlineStatus.getStatus(terminal, false)).exists(_.size > 0)
