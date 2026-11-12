package dspy4s.harness.cli

import dspy4s.harness.host.HostSupport.withRoot
import org.jline.terminal.{Attributes, Size, Terminal}
import org.jline.terminal.impl.{DumbTerminal, ExternalTerminal}

import java.io.{BufferedReader, ByteArrayInputStream, ByteArrayOutputStream, PipedInputStream, PipedOutputStream}
import java.io.{InputStream, OutputStream, PrintStream, StringReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters.*

/** The laws of the consoles of the terminal client (CODE_HARNESS_DESIGN.md, 10.6): the plain console of a script, and
  * the line editor of a person.
  *
  * The editor runs on a `DumbTerminal` or an `ExternalTerminal` of streams, so the laws need no real terminal. A
  * terminal of `TerminalBuilder` with streams uses a pseudo-terminal of the system. That terminal drops its input at
  * Ctrl-C and at the end of the stream, so its results depend on the machine. Only the `ExternalTerminal` binds the key
  * of a paste and writes the modes of the terminal.
  */
class ConsoleLaws extends munit.FunSuite:

  private def terminal(in: InputStream, out: OutputStream = ByteArrayOutputStream()): Terminal =
    DumbTerminal("fake", Terminal.TYPE_DUMB, in, out, UTF_8)

  private def terminal(input: String): Terminal = terminal(ByteArrayInputStream(input.getBytes(UTF_8)))

  private def external(in: InputStream, out: OutputStream): Terminal =
    ExternalTerminal("fake", "xterm-256color", in, out, UTF_8)

  private def withHistory[A](body: Path => A): A = withRoot(root => body(root.resolve("history")))

  /** True when `condition` holds within 10 seconds. */
  private def soon(condition: => Boolean): Boolean =
    val end = System.nanoTime() + 10_000_000_000L
    while !condition && System.nanoTime() < end do Thread.sleep(20)
    condition

  test("CLI: the plain console gives each line, then End") {
    val console = PlainConsole(BufferedReader(StringReader("one\n/two\n")), PrintStream(ByteArrayOutputStream()))
    assertEquals(Vector.fill(3)(console.read()), Vector(Typed.Text("one"), Typed.Text("/two"), Typed.End))
  }

  test("CLI editor: a line that ends with one \\ goes on, and the message has a new line in its place") {
    withHistory { history =>
      val console = EditorConsole.on(terminal("first line\\\nsecond line\nkeep \\\\\nthird\n"), history, identity)
      try
        assertEquals(console.read(), Typed.Text("first line\nsecond line"))
        assertEquals(console.read(), Typed.Text("keep \\\\"))
        assertEquals(console.read(), Typed.Text("third"))
        assertEquals(console.read(), Typed.End)
      finally console.close()
    }
  }

  test("CLI editor: a paste keeps each \\ at the end of its lines") {
    withHistory { history =>
      val paste   = "\u001b[200~docker run \\\n  --rm alpine echo hi\u001b[201~\n"
      val console = EditorConsole.on(
        external(ByteArrayInputStream(paste.getBytes(UTF_8)), ByteArrayOutputStream()),
        history,
        identity
      )
      try assertEquals(console.read(), Typed.Text("docker run \\\n  --rm alpine echo hi"))
      finally console.close()
    }
  }

  test("CLI editor: a message keeps each ! and each \\ that is not at the end of a line") {
    withHistory { history =>
      val console = EditorConsole.on(terminal("fix it!! now, with \\d+ and !0\n"), history, identity)
      try assertEquals(console.read(), Typed.Text("fix it!! now, with \\d+ and !0"))
      finally console.close()
    }
  }

  test("CLI editor: the history file keeps each line, with the mask of the secrets") {
    withHistory { history =>
      val mask    = (text: String) => text.replace("FAKE-SECRET-VALUE", "***")
      val console = EditorConsole.on(terminal("hello FAKE-SECRET-VALUE\n/status\n"), history, mask)
      try assertEquals(Vector.fill(3)(console.read()).last, Typed.End)
      finally console.close()
      val text = Files.readString(history)
      assert(text.contains("hello ***") && text.contains("/status"), text)
      assert(!text.contains("FAKE-SECRET-VALUE"), text)
    }
  }

  test("CLI editor: Ctrl-C on an empty line gives Interrupt, and the editor reads the next line after it") {
    withHistory { history =>
      val writer  = PipedOutputStream()
      val term    = terminal(PipedInputStream(writer, 4096))
      val console = EditorConsole.on(term, history, identity)
      val got     = AtomicReference[Typed]()
      val reader  = Thread.ofPlatform().daemon(true).start(() => got.set(console.read()))
      // The signal reaches the editor only while it waits for a line, so the law raises it until the read ends.
      val deadline = System.nanoTime() + 10_000_000_000L
      while reader.isAlive && System.nanoTime() < deadline do
        term.raise(Terminal.Signal.INT)
        reader.join(50)
      try
        assertEquals(got.get, Typed.Interrupt)
        writer.write("next\n".getBytes(UTF_8))
        writer.flush()
        assertEquals(console.read(), Typed.Text("next"))
      finally
        writer.close()
        console.close()
    }
  }

  test("CLI editor: the editor turns off the signals of the terminal, so Ctrl-C is a key and never a signal to jobs") {
    withHistory { history =>
      val writer  = PipedOutputStream()
      val term    = external(PipedInputStream(writer, 4096), ByteArrayOutputStream())
      val console = EditorConsole.on(term, history, identity)
      try assert(!term.getAttributes.getLocalFlag(Attributes.LocalFlag.ISIG), term.getAttributes.toString)
      finally
        writer.close()
        console.close()
    }
  }

  test("CLI editor: the Ctrl-C key on a line with text clears the text, and on an empty line gives Interrupt") {
    withHistory { history =>
      val writer  = PipedOutputStream()
      val term    = external(PipedInputStream(writer, 4096), ByteArrayOutputStream())
      val console = EditorConsole.on(term, history, identity)
      try
        // The key comes before the reads, as a Ctrl-C while no read waits: the next read gets it.
        writer.write("FAKE-HALF\u0003next\n\u0003".getBytes(UTF_8))
        writer.flush()
        assertEquals(console.read(), Typed.Text("next"))
        assertEquals(console.read(), Typed.Interrupt)
      finally
        writer.close()
        console.close()
    }
  }

  test("CLI editor: Ctrl-C outside a read runs the action of the client, and after close it runs nothing") {
    withHistory { history =>
      val term    = terminal(PipedInputStream(PipedOutputStream(), 4096))
      val console = EditorConsole.on(term, history, identity)
      val calls   = CountDownLatch(2)
      console.onInterrupt(() => calls.countDown())
      term.raise(Terminal.Signal.INT)
      assertEquals(calls.getCount, 1L)
      console.close()
      term.raise(Terminal.Signal.INT)
      assertEquals(calls.getCount, 1L)
    }
  }

  test("CLI editor: close during a read ends the read, and the terminal gets bracketed paste off again") {
    withHistory { history =>
      val writer  = PipedOutputStream()
      val out     = ByteArrayOutputStream()
      val console = EditorConsole.on(external(PipedInputStream(writer, 4096), out), history, identity)
      val got     = AtomicReference[Typed]()
      val reader  = Thread.ofPlatform().daemon(true).start(() => got.set(console.read()))
      try
        // The read has started when the editor turns bracketed paste on.
        assert(soon(out.toString(UTF_8).contains("\u001b[?2004h")), out.toString(UTF_8))
        val start = System.nanoTime()
        console.close()
        assert(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5, "close waited too long")
        reader.join(5_000)
        assert(!reader.isAlive, "the read did not end at close")
        assertEquals(got.get, Typed.End)
        val text = out.toString(UTF_8)
        assert(text.lastIndexOf("\u001b[?2004l") > text.lastIndexOf("\u001b[?2004h"), text)
        assertEquals(console.read(), Typed.End)
        console.close()
      finally writer.close()
    }
  }

  // ── The screen of CLI_SCREEN.md ──

  /** An `ExternalTerminal` of 80 columns and 24 rows. */
  private def sized(writer: PipedOutputStream, out: ByteArrayOutputStream): Terminal =
    val terminal = external(PipedInputStream(writer, 4096), out)
    terminal.setSize(Size.of(80, 24))
    terminal

  /** A live block of a turn with `rows`. */
  private def busy(rows: String*): Live = Live(rows.toVector.map(Row.of(_)), busy = true, title = "● dspy4s · shop")

  /** What a person sees after the output `out`. */
  private def seen(out: ByteArrayOutputStream): Vector[String] = FakeScreen.of(out.toString(UTF_8), 80, 24)

  /** Runs `body` with an editor on a terminal of 80 columns, with the live block `live`, while a read waits. */
  private def editing(live: AtomicReference[Live], footer: () => Option[Row] = () => None)(
      body: (EditorConsole, PipedOutputStream, ByteArrayOutputStream, AtomicReference[Typed]) => Unit
  ): Unit =
    withHistory { history =>
      val writer  = PipedOutputStream()
      val out     = ByteArrayOutputStream()
      val console = EditorConsole.on(sized(writer, out), history, identity)
      val got     = AtomicReference[Typed]()
      console.watch(() => live.get, footer)
      val reader = Thread.ofPlatform().daemon(true).start(() => got.set(console.read()))
      try body(console, writer, out, got)
      finally
        console.close()
        writer.close()
        reader.join(5_000)
    }

  test("CLI editor: the live block shows above the rule and the prompt, and a smaller block leaves no old row") {
    val live = AtomicReference(busy("  FAKE-ROW-A", "  FAKE-ROW-B", "  FAKE-ROW-C"))
    editing(live) { (_, _, out, _) =>
      assert(soon(seen(out).exists(_.contains("FAKE-ROW-C"))), seen(out).mkString("\n"))
      val shown = seen(out)
      val first = shown.indexWhere(_.contains("FAKE-ROW-A"))
      assertEquals(
        shown.slice(first, first + 5).map(_.take(14)),
        Vector("  FAKE-ROW-A", "  FAKE-ROW-B", "  FAKE-ROW-C", "─" * 14, "›")
      )
      live.set(busy("  FAKE-ROW-Z"))
      def old(rows: Vector[String]) = rows.exists(r => r.contains("FAKE-ROW-A") || r.contains("FAKE-ROW-B"))
      assert(soon(seen(out).exists(_.contains("FAKE-ROW-Z")) && !old(seen(out))), seen(out).mkString("\n"))
    }
  }

  test("CLI editor: Enter keeps only the line of the person in the history, with no live block") {
    val live = AtomicReference(busy("  FAKE-ROW-A", "  FAKE-ROW-B"))
    editing(live) { (_, writer, out, got) =>
      assert(soon(seen(out).exists(_.contains("FAKE-ROW-B"))), seen(out).mkString("\n"))
      writer.write("hi\r".getBytes(UTF_8))
      writer.flush()
      assert(soon(got.get == Typed.Text("hi")), String.valueOf(got.get))
      val shown = seen(out)
      assert(shown.contains("› hi"), shown.mkString("\n"))
      assert(!shown.exists(_.contains("FAKE-ROW")), shown.mkString("\n"))
    }
  }

  test("CLI editor: Ctrl-O runs the action of the client during a read") {
    val live   = AtomicReference(busy("  FAKE-ROW-A"))
    val counts = java.util.concurrent.atomic.AtomicInteger(0)
    editing(live) { (console, writer, out, _) =>
      console.onDetail(() => counts.incrementAndGet(): Unit)
      assert(soon(seen(out).exists(_.contains("FAKE-ROW-A"))), seen(out).mkString("\n"))
      writer.write("\u000f".getBytes(UTF_8))
      writer.flush()
      assert(soon(counts.get == 1), counts.get.toString)
    }
  }

  test("CLI editor: during a turn the cursor is a blinking block with the title of the turn, and close gives it back") {
    val live = AtomicReference(busy("  FAKE-ROW-A"))
    editing(live) { (console, _, out, _) =>
      assert(soon(out.toString(UTF_8).contains("\u001b[1 q")), out.toString(UTF_8))
      assert(soon(out.toString(UTF_8).contains("\u001b]0;● dspy4s · shop\u0007")), out.toString(UTF_8))
      val mark = out.toString(UTF_8).length
      live.set(Live(Vector.empty, busy = false, title = "dspy4s · shop"))
      assert(soon(out.toString(UTF_8).drop(mark).contains("\u001b[0 q")), out.toString(UTF_8).drop(mark))
      assert(soon(!seen(out).exists(_.contains("FAKE-ROW-A"))), seen(out).mkString("\n"))
      live.set(busy("  FAKE-ROW-B"))
      assert(soon(seen(out).exists(_.contains("FAKE-ROW-B"))), seen(out).mkString("\n"))
      console.close()
      val text = out.toString(UTF_8)
      assert(text.lastIndexOf("\u001b[0 q") > text.lastIndexOf("\u001b[1 q"), text)
    }
  }

  test("CLI editor: a row with a % shows it as it is, and the footer shows its row") {
    val live = AtomicReference(busy("  $ date +%s"))
    editing(live, () => Some(Row.of("  FAKE-FOOTER"))) { (_, _, out, _) =>
      assert(soon(seen(out).exists(_.contains("$ date +%s"))), seen(out).mkString("\n"))
      assert(soon(out.toString(UTF_8).contains("FAKE-FOOTER")), out.toString(UTF_8))
    }
  }

  test("CLI editor: Tab completes the commands, the lifetimes after /allow and the views after /reasoning") {
    val reader                              = org.jline.reader.LineReaderBuilder.builder().terminal(terminal("")).build()
    def complete(line: String): Set[String] =
      val parsed =
        org.jline.reader.impl.DefaultParser().parse(line, line.length, org.jline.reader.Parser.ParseContext.COMPLETE)
      val found = java.util.ArrayList[org.jline.reader.Candidate]()
      EditorConsole.completer.complete(reader, parsed, found)
      found.asScala.map(_.value).toSet
    assert(complete("/re").contains("/reasoning"), complete("/re").toString)
    assert(complete("/sh").contains("/show"), complete("/sh").toString)
    assert(complete("/allow se").contains("session"), complete("/allow se").toString)
    assert(complete("/allow 12 tu").contains("turn"), complete("/allow 12 tu").toString)
    assert(complete("/reasoning l").contains("line"), complete("/reasoning l").toString)
  }
