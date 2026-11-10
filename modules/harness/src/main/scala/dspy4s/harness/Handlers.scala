package dspy4s.harness

import dspy4s.harness.guest.{Data, Files, JobId, JobStatus, Shell, Store, Turn}
import dspy4s.harness.kernel.{EffectId, EffectResult, Entry}
import dspy4s.harness.repl.Bindings
import dspy4s.harness.sandbox.{Layout, Profile}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files as JFiles, Path}

/** The gate of the guest API calls of a cell: `recorded(h)` of CODE_HARNESS_DESIGN.md, 5.3. It commits `EffectStarted`
  * before each call and `EffectDone` after it, with the value as `Json` for a replay and as text for a reader (4.1). A
  * call that the journal refuses does not run, for example after a stop (4.7, Stop), and the cell gets an exception.
  */
final class Recorder(journal: Journal):
  private var cell  = -1L
  private var calls = 0

  /** Starts the calls of the cell whose `Acted` has the `seq` `cell`. Its calls get the ids `EffectId(cell, 0)`,
    * `EffectId(cell, 1)` and so on.
    */
  def startCell(cell: Long): Unit =
    synchronized {
      this.cell = cell
      calls = 0
    }

  /** One call: the intent, the call, and the outcome. A call that throws gets `EffectResult.Error`, and the exception
    * goes on to the cell.
    */
  def call[A](op: String, args: Json)(run: => A)(using data: Data[A]): A =
    val id = synchronized {
      val next = EffectId(cell, calls)
      calls += 1
      next
    }
    journal.commit(Entry.EffectStarted(id, op, args)).left.foreach { rejection =>
      throw IllegalStateException(s"$op: the session refused the call, because it stops or the cell ended ($rejection)")
    }
    val value =
      try run
      catch
        case e: Exception =>
          journal.commit(Entry.EffectDone(id, EffectResult.Error(Recorder.message(e)))): Unit
          throw e
    val json = data.encode(value)
    journal.commit(Entry.EffectDone(id, EffectResult.Value(json, Recorder.rendered(json)))): Unit
    value

object Recorder:

  /** The longest text of a value for a reader. The `Json` holds the whole value. */
  private val RenderedLimit = 2_000

  private def message(e: Exception): String = s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"

  private def rendered(json: Json): String =
    val text = json match
      case Json.String(value) => value
      case other              => other.print
    if text.length <= RenderedLimit then text else text.take(RenderedLimit) + s"… [${text.length - RenderedLimit} more]"

/** `Files.local(root)` of 5.3: the real files under `root`, with the rules of `profile` (5.4). A write is only under a
  * writable root and not in a read-only part, such as `.git`. A hidden path cannot be read or written, and nor can a
  * file with a secret name of `names` that exists, as for a command. A new file with a secret name can be written, and
  * after that it is hidden. A path outside `root` is an error, also through a symbolic link.
  */
final class LocalFiles(root: Path, profile: Profile, names: Set[String]) extends Files:
  private val base = Layout.real(root)

  def read(path: String): String =
    val target = readable(path)
    JFiles.readString(target, UTF_8)

  def write(path: String, content: String): Unit =
    val target = writable(path)
    JFiles.createDirectories(target.getParent): Unit
    JFiles.writeString(target, content, UTF_8): Unit

  def edit(path: String, oldText: String, newText: String): Unit =
    val target = writable(path)
    val text   = JFiles.readString(readable(path), UTF_8)
    val at     = text.indexOf(oldText)
    if oldText.isEmpty || at < 0 then throw IllegalArgumentException(s"$path holds no place of the old text")
    if text.indexOf(oldText, at + 1) >= 0 then
      throw IllegalArgumentException(s"$path holds the old text more than one time, so edit does not know the place")
    JFiles.writeString(target, text.substring(0, at) + newText + text.substring(at + oldText.length), UTF_8): Unit

  /** The absolute normal path of `path` under the root, as the arguments of a recorded call hold it (5.5). */
  def absolute(path: String): String = base.resolve(path).normalize.toString

  private def readable(path: String): Path =
    val target = inside(path)
    if !profile.canRead(target) || secret(path) then throw IllegalArgumentException(s"$path is closed to this session")
    target

  private def writable(path: String): Path =
    val target = inside(path)
    if !profile.canWrite(target) || secret(path) then throw IllegalArgumentException(s"$path is read-only or closed")
    target

  /** The real path of `path` under the root. A part that does not exist yet keeps its name after the real path of the
    * part before it, so a new file in a linked folder is checked at its real place.
    */
  private def inside(path: String): Path =
    val lexical = base.resolve(path).normalize
    if !lexical.startsWith(base) then throw IllegalArgumentException(s"$path is outside the workspace")
    val target = LocalFiles.realPart(lexical)
    if !target.startsWith(base) then throw IllegalArgumentException(s"$path is outside the workspace")
    target

  /** True when a part of `path` under the root, as written and as a real path, has a secret name and exists (5.4). */
  private def secret(path: String): Boolean =
    val lexical = base.resolve(path).normalize
    Vector(lexical, LocalFiles.realPart(lexical)).exists { full =>
      full.startsWith(base) && (1 to base.relativize(full).getNameCount).exists { n =>
        val part = base.resolve(base.relativize(full).subpath(0, n))
        Layout.isSecretName(part.getFileName.toString, names) &&
        JFiles.exists(part, java.nio.file.LinkOption.NOFOLLOW_LINKS)
      }
    }

object LocalFiles:

  /** The real path of the deepest part that exists, with the rest of `path` after it. */
  private def realPart(path: Path): Path =
    if JFiles.exists(path) then path.toRealPath()
    else Option(path.getParent).fold(path)(parent => realPart(parent).resolve(path.getFileName))

/** `Store.snapshot(state)` of 5.3: the store of the session at the start of a cell, and a buffer of its writes.
  * `CellResult.writes` holds the buffer, so the store changes only when the cell completes (4.7, Store). The REPL binds
  * one handler for its life, so `begin` takes the snapshot of each cell.
  */
final class SnapshotStore extends Store:
  private var base   = Map.empty[String, Json]
  private var buffer = Map.empty[String, Json]

  def begin(snapshot: Map[String, Json]): Unit =
    synchronized {
      base = snapshot
      buffer = Map.empty
    }

  def writes: Map[String, Json] = synchronized(buffer)

  def keys: Vector[String] = synchronized((base ++ buffer).keys.toVector.sorted)

  def load[A: Data](key: String): Option[A] =
    synchronized((base ++ buffer).get(key)).map(json =>
      summon[Data[A]].decode(json, key).fold(error => throw error, a => a)
    )

  def store[A: Data](key: String, value: A): Unit =
    synchronized { buffer = buffer.updated(key, summon[Data[A]].encode(value)) }

/** The `Turn` of a session (5.1): the time of `clock`, and the values of the input fields from `SessionState.inputs`.
  * `recorded` records each `now`, so a replay gets the same time.
  */
final class JournalTurn(journal: Journal, clock: () => Long) extends Turn:
  def now(): Long                       = clock()
  def input(name: String): Option[Json] = journal.state.inputs.get(name)

/** `recorded(h)` of 5.3 for each handler of `bindings`: each call of the guest API goes through `recorder`. `input`
  * reads the journal, so it is not an effect. With `root`, the path of a file call is absolute and normal in the
  * arguments, as a path rule of a guard needs (5.5).
  *
  * The handlers of the mode of phase 4 have a record: files, shell, store and the turn. A binding of another capability
  * is an error until its phase.
  */
def recorded(bindings: Bindings, recorder: Recorder, root: Option[Path] = None): Bindings =
  val others =
    Vector(bindings.network.isDefined, bindings.llm.isDefined, bindings.sessions.isDefined, bindings.children.isDefined)
  if others.contains(true) then
    throw IllegalArgumentException("recorded has a record only for files, shell, store and turn")
  def path(text : String): String        = root.fold(text)(r => Layout.real(r).resolve(text).normalize.toString)
  def args(pairs: (String, Json)*): Json = Json.Object(Chunk.from(pairs))
  def text(value: String): Json          = Json.String(value)
  val turn                               = bindings.turn
  Bindings(
    turn = new Turn:
      def now(): Long                       = recorder.call("now", Json.Null)(turn.now())
      def input(name: String): Option[Json] = turn.input(name)
    ,
    files = bindings.files.map { inner =>
      new Files:
        def read(p: String): String                 = recorder.call("read", args("path" -> text(path(p))))(inner.read(p))
        def write(p: String, content: String): Unit =
          recorder.call("write", args("path" -> text(path(p)), "content" -> text(content)))(inner.write(p, content))
        def edit(p: String, oldText: String, newText: String): Unit =
          recorder.call("edit", args("path" -> text(path(p)), "old" -> text(oldText), "new" -> text(newText)))(
            inner.edit(p, oldText, newText)
          )
    },
    shell = bindings.shell.map { inner =>
      new Shell:
        def exec(command: String)(using files: Files): String =
          recorder.call("exec", args("command" -> text(command)))(inner.exec(command))
        def spawn(command: String, report: Boolean)(using files: Files): JobId =
          recorder.call("spawn", args("command" -> text(command), "report" -> Json.Boolean(report)))(
            inner.spawn(command, report)
          )
        def jobOutput(job: JobId): String =
          recorder.call("jobOutput", summon[Data[JobId]].encode(job))(inner.jobOutput(job))
        def waitJob(job: JobId, maxSeconds: Int): JobStatus =
          recorder.call(
            "waitJob",
            args("job" -> summon[Data[JobId]].encode(job), "maxSeconds" -> Json.Number(BigDecimal(maxSeconds)))
          )(
            inner.waitJob(job, maxSeconds)
          )
        def stopJob(job: JobId): Unit = recorder.call("stopJob", summon[Data[JobId]].encode(job))(inner.stopJob(job))
    },
    store = bindings.store.map { inner =>
      new Store:
        def keys: Vector[String]                        = recorder.call("keys", Json.Null)(inner.keys)
        def load[A: Data](key: String): Option[A]       = recorder.call("load", args("key" -> text(key)))(inner.load[A](key))
        def store[A: Data](key: String, value: A): Unit =
          recorder.call(
            "store",
            args("key" -> text(key), "value" -> summon[Data[A]].encode(value))
          )(inner.store(key, value))
    }
  )
