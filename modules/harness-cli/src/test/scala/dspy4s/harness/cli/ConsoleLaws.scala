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

  /** The frames of the spinner of the busy prompt. */
  private val Frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏".toSet

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

  // ── The busy line (2026-11-11) ──

  /** An `ExternalTerminal` of 160 columns, so the right prompt has room. */
  private def wide(writer: PipedOutputStream, out: ByteArrayOutputStream): Terminal =
    val terminal = external(PipedInputStream(writer, 4096), out)
    terminal.setSize(Size.of(160, 24))
    terminal

  test("CLI editor: during a turn the line shows the spinner and the label, and the cursor is a blinking block") {
    withHistory { history =>
      val writer  = PipedOutputStream()
      val out     = ByteArrayOutputStream()
      val console = EditorConsole.on(wide(writer, out), history, identity)
      val reader  = Thread.ofPlatform().daemon(true).start(() => console.read(): Unit)
      try
        assert(soon(out.toString(UTF_8).contains("\u001b[?2004h")), out.toString(UTF_8))
        // A command with a % shows it as it is: the prompt of JLine reads %s as a pattern.
        console.busy(Some(Busy("step 1: exec \"date +%s\"", System.currentTimeMillis(), 0)))
        assert(soon(out.toString(UTF_8).contains("step 1: exec \"date +%s\"")), out.toString(UTF_8))
        val busyText = out.toString(UTF_8)
        assert(busyText.contains("\u001b[1 q"), busyText)
        assert(busyText.exists(Frames.contains), busyText)
        val mark = busyText.length
        console.busy(None)
        assert(soon(out.toString(UTF_8).drop(mark).contains("\u001b[0 q")), out.toString(UTF_8).drop(mark))
        console.close()
        reader.join(5_000)
        val text = out.toString(UTF_8)
        assert(text.lastIndexOf("\u001b[0 q") > text.lastIndexOf("\u001b[1 q"), text)
      finally
        console.close()
        writer.close()
    }
  }

  test("CLI editor: a read that starts during a turn shows the busy line at once, and close gives the cursor back") {
    withHistory { history =>
      val writer  = PipedOutputStream()
      val out     = ByteArrayOutputStream()
      val console = EditorConsole.on(wide(writer, out), history, identity)
      console.busy(Some(Busy("step 2: the model works", System.currentTimeMillis(), 1)))
      val reader = Thread.ofPlatform().daemon(true).start(() => console.read(): Unit)
      try
        assert(soon(out.toString(UTF_8).contains("step 2: the model works")), out.toString(UTF_8))
        console.close()
        reader.join(5_000)
        val text = out.toString(UTF_8)
        assert(text.lastIndexOf("\u001b[0 q") > text.lastIndexOf("\u001b[1 q"), text)
      finally
        console.close()
        writer.close()
    }
  }
