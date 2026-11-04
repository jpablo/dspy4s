package experiment

import harness.guest.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files as JFiles, Path}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable

/** One guest call and its result. The result is text, so a journal can store it. */
final case class EffectRecord(op: String, args: List[String], result: String)

/** One recorded cell (section 8.3: the cell code and its effects in order). */
final case class CellJournal(code: String, effects: Vector[EffectRecord], output: String, ok: Boolean)

/** The real effects. Each counter counts the real calls, so a test can show that a replay did none. */
final class RealWorld(root: Path):
  val reads     = AtomicInteger()
  val writes    = AtomicInteger()
  val processes = AtomicInteger()
  val lmCalls   = AtomicInteger()

  def read(path: String): String =
    reads.incrementAndGet()
    JFiles.readString(root.resolve(path), UTF_8)

  def write(path: String, content: String): Unit =
    writes.incrementAndGet()
    val p = root.resolve(path)
    JFiles.createDirectories(p.getParent)
    JFiles.writeString(p, content, UTF_8): Unit

  def exec(command: String): ExecResult =
    processes.incrementAndGet()
    val p      = ProcessBuilder("/bin/sh", "-c", command).directory(root.toFile).redirectErrorStream(false).start()
    val stdout = String(p.getInputStream.readAllBytes(), UTF_8)
    p.waitFor(30, TimeUnit.SECONDS)
    ExecResult(p.exitValue(), stdout)

  /** A stand-in for an LM: each call costs one unit. */
  def llm(prompt: String): String =
    lmCalls.incrementAndGet()
    s"LM answer #${lmCalls.get()} to: ${prompt.take(60)}"

/** The replay found a call that the journal does not have. */
final class Divergence(message: String) extends RuntimeException(message)

/** The host side of the handlers of one REPL. In live mode, each guest call does the real effect and writes a record.
  * In replay mode, each guest call takes the next record of the cell, checks the operation and the arguments, and gives
  * the recorded result with no real effect (the `recorded` and `replayed` handlers of F2).
  */
final class Gate(real: RealWorld):
  private enum Mode:
    case Live(records: mutable.Buffer[EffectRecord])
    case Replay(records: mutable.Queue[EffectRecord])

  @volatile private var mode: Mode = Mode.Live(mutable.ArrayBuffer())

  def startLiveCell(): Unit                                = mode = Mode.Live(mutable.ArrayBuffer())
  def startReplayCell(records: Vector[EffectRecord]): Unit = mode = Mode.Replay(mutable.Queue.from(records))

  /** The records of the current live cell. */
  def liveRecords: Vector[EffectRecord] =
    mode match
      case Mode.Live(records) => records.synchronized(records.toVector)
      case Mode.Replay(_)     => Vector.empty

  /** The records of the current replay cell that no call used. */
  def unusedReplayRecords: Vector[EffectRecord] =
    mode match
      case Mode.Replay(records) => records.synchronized(records.toVector)
      case Mode.Live(_)         => Vector.empty

  private def call(op: String, args: List[String])(effect: => String): String =
    mode match
      case Mode.Live(records) =>
        val result = effect
        records.synchronized(records += EffectRecord(op, args, result))
        result
      case Mode.Replay(records) => records.synchronized:
          records.dequeueFirst(r => r.op == op && r.args == args) match
            case Some(r) => r.result
            case None    => throw Divergence(s"No recorded effect for $op(${args.mkString(", ")})")

  val handlers: Handlers = Handlers(
    files = new Files:
      def read(path: String): String                 = call("read", List(path))(real.read(path))
      def write(path: String, content: String): Unit =
        call("write", List(path, content)) {
          real.write(path, content)
          ""
        }: Unit
    ,
    shell = new Shell:
      def exec(command: String): ExecResult =
        val text = call("exec", List(command)) {
          val r = real.exec(command)
          s"${r.exitCode}\n${r.stdout}"
        }
        val newline = text.indexOf('\n')
        ExecResult(text.take(newline).toInt, text.drop(newline + 1))
    ,
    llm = new Llm:
      def query(prompt: String): String = call("llmQuery", List(prompt))(real.llm(prompt))
  )
