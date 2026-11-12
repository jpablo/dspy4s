package dspy4s.harness.host

import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.Secrets
import dspy4s.harness.kernel.SessionId
import dspy4s.harness.sandbox.Environment
import dspy4s.lm.contracts.{ExchangeEnd, ExchangeRecord, ExchangeRequest, ExchangeSink}
import zio.blocks.schema.json.Json

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files as JFiles, Path}
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.{Try, Using}

/** The exchange file of one session (docs/code-harness/OBSERVABILITY.md, 4): `<root>/sessions/<id>.http.jsonl`. It is
  * the exchange sink of each activation of the session (4.2).
  *
  *   - `start` writes a `request` line: the session, `after` (the last `seq` of the journal), the predictor that runs
  *     on the thread of the call, the link of a probe, the time, the URL, the headers, the stream flag and the exact
  *     body. The record keeps the reply, and its end writes one `reply` line (4.3).
  *   - Each text goes through the mask of `secrets`, and the value of a header with a secret name (5.6) is the mark of
  *     its name (4.4).
  *   - Each line is one write under one lock of the process, through a `FileOutputStream` that opens, appends and
  *     closes. A channel of NIO stops on the interrupt flag, and a stop sets it on the thread of a policy call, so a
  *     channel would lose the reply line of the call. A thread that ends after its activation still writes its line
  *     too. A failed write prints one line on the standard error, once, and the call goes on.
  */
final class ExchangeFile private (
    writer  : ExchangeFile.LineWriter,
    session : SessionId,
    position: () => Long,
    secrets : Secrets,
    probe   : Option[String]
) extends ExchangeSink:

  /** The path of the file. */
  def path: Path = writer.path

  /** The same file, with the link of the probe `link` in the lines of its exchanges (4.2). */
  def forProbe(link: ProbeLink): ExchangeFile =
    new ExchangeFile(writer, session, position, secrets, Some(HarnessProbe.nameOf(link)))

  def start(request: ExchangeRequest)(using RuntimeContext): ExchangeRecord =
    val id     = UUID.randomUUID().toString
    val labels =
      Vector("kind" -> Json.String("request"), "id" -> Json.String(id), "session" -> Json.String(session.value))
        ++ Vector("after" -> Json.Number(position()))
        ++ RuntimeEnvironment.activePredictor.map(key => "predictor" -> Json.String(key.moduleName))
        ++ probe.map(link => "probe" -> Json.String(link))
    val sent = Vector(
      "at"      -> Json.Number(System.currentTimeMillis()),
      "url"     -> text(request.url),
      "headers" -> headers(request.headers),
      "stream"  -> Json.Boolean(request.stream),
      "body"    -> text(request.body)
    )
    writer.write(Json.Object((labels ++ sent)*))
    ExchangeFile.Reply(id, request.stream, this)

  /** Writes the reply line of the exchange `id`. */
  private def replied(id: String, reply: ExchangeFile.Reply, how: ExchangeEnd): Unit =
    val end = how match
      case ExchangeEnd.Done            => Vector("end" -> Json.String("done"))
      case ExchangeEnd.Closed          => Vector("end" -> Json.String("closed"))
      case ExchangeEnd.Failed(message) => Vector("end" -> Json.String("failed"), "error" -> text(message))
    val kept =
      if reply.stream then Vector("lines" -> Json.Array(reply.lines.map(text)*))
      else reply.text.map(body => "body" -> text(body)).toVector
    val got = Vector("kind" -> Json.String("reply"), "id" -> Json.String(id))
      ++ Vector("at" -> Json.Number(System.currentTimeMillis()))
      ++ reply.first.map(at => "first" -> Json.Number(at))
      ++ reply.status.map(code => "status" -> Json.Number(code))
      ++ reply.headers.map(values => "headers" -> headers(values))
      ++ kept
    writer.write(Json.Object((got ++ end)*))

  private def text(value: String): Json = Json.String(secrets.maskText(value))

  private def headers(values: Map[String, String]): Json =
    Json.Object(values.toVector.sortBy(_._1).map { (name, value) =>
      secrets.maskText(name) -> (if Environment.isSecret(name) then Json.String(Secrets.mark(name)) else text(value))
    }*)

object ExchangeFile:

  /** The variable that turns the exchange files off: `DSPY_EXCHANGES=off`. */
  val Variable = "DSPY_EXCHANGES"

  /** The exchange file of `session` under the root `root` of a host: next to its journal, with a name that does not end
    * with `.db`, so `Catalog.journalFiles` does not take it.
    */
  def pathOf(root: Path, session: SessionId): Path =
    SessionFile.folderOf(root).resolve(s"${session.value}.http.jsonl")

  /** The exchange file of `session` under `root`. `position` gives the last `seq` of the journal of the session, and
    * `secrets` the mask of each text.
    */
  def apply(root: Path, session: SessionId, position: () => Long, secrets: Secrets): ExchangeFile =
    new ExchangeFile(LineWriter(pathOf(root, session)), session, position, secrets, None)

  /** Whether the variables `env` keep the exchange files on: on, apart from `DSPY_EXCHANGES=off`. */
  def enabled(env: Map[String, String]): Boolean = !env.get(Variable).exists(_.trim.equalsIgnoreCase("off"))

  /** The reply of one exchange. The record keeps it, and its end writes the reply line. `GuardedRecord` of the lm
    * module makes the calls of a record one at a time, under its lock, so the fields need no lock of their own.
    */
  private final class Reply(id: String, val stream: Boolean, file: ExchangeFile) extends ExchangeRecord:
    var status: Option[Int]                  = None
    var headers: Option[Map[String, String]] = None
    var text: Option[String]                 = None
    var first: Option[Long]                  = None
    private val kept                         = Vector.newBuilder[String]

    def lines: Vector[String] = kept.result()

    def replied(status: Int, headers: Map[String, String]): Unit =
      this.status = Some(status)
      this.headers = Some(headers)

    def body(text: String): Unit = this.text = Some(text)

    def line(text: String): Unit =
      if first.isEmpty then first = Some(System.currentTimeMillis())
      kept += text

    def end(how: ExchangeEnd): Unit = file.replied(id, this, how)

  /** The lock of each write of an exchange line in this process. Two activations of one session write to one file: a
    * late thread of the first one, and the second one. So one lock for all the files keeps their lines apart. A model
    * call takes seconds, so the lock costs nothing.
    */
  private val writes = Object()

  /** The writes of one file: one line at a time, under the lock `writes`. */
  private final class LineWriter(val path: Path):
    private val warned  = AtomicBoolean(false)
    private var folders = false

    def write(line: Json): Unit =
      val bytes = (line.print + "\n").getBytes(UTF_8)
      writes.synchronized {
        Try {
          if !folders then
            JFiles.createDirectories(path.toAbsolutePath.getParent): Unit
            folders = true
          Using.resource(FileOutputStream(path.toFile, true))(_.write(bytes))
        }.failed.foreach { e =>
          if !warned.getAndSet(true) then System.err.println(s"The exchange file $path did not take a line: $e")
        }
      }
