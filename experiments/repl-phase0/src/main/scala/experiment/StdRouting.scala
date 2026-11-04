package experiment

import java.io.{OutputStream, PrintStream}

/** Sets `System.out` and `System.err` one time, at the start of the JVM, to streams that write to `Console.out` and
  * `Console.err` of the current thread. Then a guest `System.out.println` goes to the buffer of its REPL too. This is
  * not a swap around each cell, so it needs no lock.
  */
object StdRouting:
  @volatile private var installed = false

  def install(): Unit =
    synchronized:
      if !installed then
        // Start `Console` first: it keeps the original streams as its default values.
        val _ = (Console.out, Console.err)
        System.setOut(PrintStream(Forward(() => Console.out), true))
        System.setErr(PrintStream(Forward(() => Console.err), true))
        installed = true

  private final class Forward(target: () => PrintStream) extends OutputStream:
    override def write(b: Int): Unit                             = target().write(b)
    override def write(b: Array[Byte], off: Int, len: Int): Unit = target().write(b, off, len)
    override def flush(): Unit                                   = target().flush()
