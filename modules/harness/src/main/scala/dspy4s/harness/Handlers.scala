package dspy4s.harness

import dspy4s.harness.guest.{Children, Data, Files, JobId, JobStatus, Llm, Scope, SessionCard, SessionRef, Sessions}
import dspy4s.harness.guest.{Shell, StrategyName, Store, Turn}
import dspy4s.harness.kernel.{EffectId, EffectResult, Entry}
import dspy4s.harness.repl.Bindings
import dspy4s.harness.sandbox.{Layout, Profile}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files as JFiles, LinkOption, Path, StandardOpenOption}

/** The gate of the guest API calls of a cell: `recorded(h)` of CODE_HARNESS_DESIGN.md, 5.3. It commits `EffectStarted`
  * before each call and `EffectDone` after it, with the value as `Json` for a replay and as text for a reader (4.1). A
  * call that the journal refuses does not run, for example after a stop (4.7, Stop), and the cell gets an exception.
  * After `close`, no call runs (9). When `ended` is true, no call runs either: the program call of the session ended,
  * so a child that outlives its parent cannot write or exec (9).
  */
final class Recorder(journal: Journal, ended: () => Boolean = () => false, mask: Json => Json = identity):
  private var cell   = -1L
  private var calls  = 0
  private var closed = false

  /** Starts the calls of the cell whose `Acted` has the `seq` `cell`. Its calls get the ids `EffectId(cell, 0)`,
    * `EffectId(cell, 1)` and so on.
    */
  def startCell(cell: Long): Unit =
    synchronized {
      this.cell = cell
      calls = 0
    }

  /** Refuses each later call, also of a later cell. The harness closes the recorder when the REPL cannot run a next
    * cell safely, for example when the thread of a cell still runs. Then that thread cannot reach a handler (9).
    */
  def close(): Unit = synchronized { closed = true }

  /** One call: the intent, the check of `guarded` (5.5), the call, and the outcome. A call that throws gets
    * `EffectResult.Error`, and the exception goes on to the cell. A call that `guarded` stops does not run: its
    * `EffectDone` holds the result of the guard, and the cell gets the error of the guard. A call whose intent the
    * journal refuses does not run, and the cell gets an exception with the refusal in words (`Rejection.words`).
    *
    * The lock of the recorder holds the choice of the id and the commit of the intent, so the journal gets the intents
    * of a cell in the order of their ids (4.5). The call itself and its outcome are outside the lock. The lock order is
    * the recorder, then the journal. The journal never calls the recorder.
    */
  def call[A](op: String, args: Json)(run: => A)(using data: Data[A]): A =
    val (id, intent) = synchronized {
      if closed then
        throw IllegalStateException(s"$op: the session refused the call, because the harness closed the calls")
      if ended() then
        throw IllegalStateException(s"$op: the session refused the call, because its program call ended")
      val fresh = EffectId(cell, calls)
      calls += 1
      (fresh, journal.commit(Entry.EffectStarted(fresh, op, args)))
    }
    val after = intent.fold(
      rejection =>
        throw IllegalStateException(
          s"$op: the session refused the call, because it stops or the cell ended (${rejection.words})"
        ),
      identity
    )
    // `guarded` reads the state after the intent, so a `Once` grant stays until the outcome of its call (5.5). It
    // compares the arguments with the mask, because the journal holds the pending rule and the grant with the mask.
    guarded(after, op, mask(args)).left.foreach { stop =>
      journal.commit(Entry.EffectDone(id, stop.result)): Unit
      throw stop.error
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

  /** The text of a value for a reader, at most `RenderedLimit` units and a note of the rest. It goes into the journal,
    * so the cut keeps each character whole.
    */
  private[harness] def rendered(json: Json): String =
    val text = json match
      case Json.String(value) => value
      case other              => other.print
    if text.length <= RenderedLimit then text
    else
      val kept = textPrefix(text, RenderedLimit)
      kept + s"… [${text.length - kept.length} more]"

/** `Files.local(root)` of 5.3: the real files under `root`, with the rules of `profile` (5.4). A write is only under a
  * writable root and not in a read-only part, such as `.git`. A hidden path cannot be read or written, and nor can a
  * file with a secret name of `names` that exists, as for a command. A new file with a secret name can be written, and
  * after that it is hidden. A path outside `root` is an error, also through a symbolic link.
  *
  * A command in the sandbox can make links, and the JVM is not in the sandbox (9). So a path with a part that is a
  * symbolic link to a place that does not exist is an error, and so is a file with more than one hard link. A write and
  * a read open the file with no link at the last part.
  */
final class LocalFiles(root: Path, profile: Profile, names: Set[String]) extends Files:
  private val base = Layout.real(root)

  def read(path: String): String =
    val stream = JFiles.newInputStream(readable(path), LinkOption.NOFOLLOW_LINKS)
    try String(stream.readAllBytes(), UTF_8)
    finally stream.close()

  def write(path: String, content: String): Unit =
    val target = writable(path)
    JFiles.createDirectories(target.getParent): Unit
    LocalFiles.put(target, content)

  def edit(path: String, oldText: String, newText: String): Unit =
    val target = writable(path)
    val text   = read(path)
    val at     = text.indexOf(oldText)
    if oldText.isEmpty || at < 0 then throw IllegalArgumentException(s"$path holds no place of the old text")
    if text.indexOf(oldText, at + 1) >= 0 then
      throw IllegalArgumentException(s"$path holds the old text more than one time, so edit does not know the place")
    LocalFiles.put(target, text.substring(0, at) + newText + text.substring(at + oldText.length))

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
    val target = LocalFiles.realPart(lexical).getOrElse(
      throw IllegalArgumentException(s"$path has a link to a place that does not exist")
    )
    if !target.startsWith(base) then throw IllegalArgumentException(s"$path is outside the workspace")
    if LocalFiles.links(target) > 1 then throw IllegalArgumentException(s"$path has more than one hard link")
    target

  /** True when a part of `path` under the root, as written and as a real path, has a secret name and exists (5.4). */
  private def secret(path: String): Boolean =
    val lexical = base.resolve(path).normalize
    (lexical +: LocalFiles.realPart(lexical).toVector).exists { full =>
      full.startsWith(base) && (1 to base.relativize(full).getNameCount).exists { n =>
        val part = base.resolve(base.relativize(full).subpath(0, n))
        Layout.isSecretName(part.getFileName.toString, names) && JFiles.exists(part, LinkOption.NOFOLLOW_LINKS)
      }
    }

object LocalFiles:

  /** The real path of the deepest part that exists, with the rest of `path` after it. `None` when a part that does not
    * exist is a symbolic link, because a write follows the link to its place.
    */
  private def realPart(path: Path): Option[Path] =
    if JFiles.exists(path) then Some(path.toRealPath())
    else if JFiles.exists(path, LinkOption.NOFOLLOW_LINKS) then None
    else Option(path.getParent).fold(Some(path))(parent => realPart(parent).map(_.resolve(path.getFileName)))

  /** The number of hard links of a regular file, or 1 for another file and for a file that does not exist. */
  private def links(path: Path): Int =
    if !JFiles.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then 1
    else
      JFiles.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) match
        case n: Integer => n.intValue
        case _          => 1

  /** Writes `text` to `path`. The open does not follow a link at the last part, so a link that a job makes after the
    * checks gives an error and not a write outside the root.
    */
  private def put(path: Path, text: String): Unit =
    JFiles.writeString(
      path,
      text,
      UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE,
      LinkOption.NOFOLLOW_LINKS
    ): Unit

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

/** The `Turn` of a session (5.1): the time of `clock`, the values of the input fields from `SessionState.inputs`, and
  * `zoom`, which reads `zooms`. The effects of the session set the reader of `zooms` after each update (7.1).
  * `recorded` records each `now`, so a replay gets the same time.
  */
final class JournalTurn(journal: Journal, clock: () => Long, zooms: ZoomSource = ZoomSource()) extends Turn:
  def now(): Long                       = clock()
  def input(name: String): Option[Json] = journal.state.inputs.get(name)

  /** The part `ref` of the memory. An error is an exception with a plain message, so the cell fails with it. */
  def zoom(ref: String): String =
    zooms.zoom(ref).fold(error => throw IllegalArgumentException(s"zoom: $error"), identity)

/** `recorded(h)` of 5.3 for each handler of `bindings`: each call of the guest API goes through `recorder`. `input`
  * reads the journal, so it is not an effect. With `root`, the path of a file call is absolute and normal in the
  * arguments, as a path rule of a guard needs (5.5).
  *
  * The handlers of the task mode have a record: files, shell, store, the turn, and since phase 5 the LM (`llmQuery`,
  * `llmQueryBatched`) and the children (`rlm`, `rlmBatched`). The value of each call goes into the journal through its
  * `Data`. A child has its own journal, so the journal of the parent has one effect for each `rlm`. Since phase 9 the
  * calls of `Sessions` have a record too (`recordedSessions`). A binding of the network is an error until its phase.
  */
def recorded(bindings: Bindings, recorder: Recorder, root: Option[Path] = None): Bindings =
  if bindings.network.isDefined then
    throw IllegalArgumentException(
      "recorded has a record only for files, shell, store, llm, children, sessions and turn"
    )
  val base                               = root.map(Layout.real)
  def path(text : String): String        = base.fold(text)(_.resolve(text).normalize.toString)
  def args(pairs: (String, Json)*): Json = Json.Object(Chunk.from(pairs))
  def text(value: String): Json          = Json.String(value)
  val turn                               = bindings.turn
  Bindings(
    turn = new Turn:
      def now(): Long                       = recorder.call("now", Json.Null)(turn.now())
      def input(name: String): Option[Json] = turn.input(name)
      def zoom(ref  : String): String       = recorder.call("zoom", args("ref" -> text(ref)))(turn.zoom(ref))
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
    },
    llm = bindings.llm.map { inner =>
      new Llm:
        def query(prompt: String): String =
          recorder.call("llmQuery", args("prompt" -> text(prompt)))(inner.query(prompt))
        def queryBatched(prompts: Vector[String]): Vector[String] =
          recorder.call("llmQueryBatched", args("prompts" -> summon[Data[Vector[String]]].encode(prompts)))(
            inner.queryBatched(prompts)
          )
    },
    children = bindings.children.map { inner =>
      new Children:
        def rlm[A: Data](task: String, inputs: Map[String, String]): A =
          recorder.call(
            "rlm",
            args("task" -> text(task), "inputs" -> summon[Data[Map[String, String]]].encode(inputs))
          )(inner.rlm[A](task, inputs))
        def rlmBatched[A: Data](tasks: Vector[(String, Map[String, String])]): Vector[A] =
          recorder.call(
            "rlmBatched",
            args("tasks" -> summon[Data[Vector[(String, Map[String, String])]]].encode(tasks))
          )(
            inner.rlmBatched[A](tasks)
          )
    },
    sessions = bindings.sessions.map(recordedSessions(_, recorder))
  )

/** `recorded(h)` of 5.3 for the handler of `Sessions` (8.7): the operations `sessions`, `tell`, `start`, `observe` and
  * `zoom`, each with its arguments, and the value of each call through its `Data`. The arguments of `start` hold
  * `kind`, the name of the case of its scope, so a guard rule can ask for a `Directory` scope. `zoom` of another
  * session has the arguments `session` and `ref`, so a guard rule can tell it from `zoom(ref)` of the turn. `tell` and
  * `start` are not read-only, so their intents reach the disk before they run (4.1).
  */
private def recordedSessions(inner: Sessions, recorder: Recorder): Sessions =
  def args(pairs : (String, Json)*): Json      = Json.Object(Chunk.from(pairs))
  def text(value : String): Json               = Json.String(value)
  def session(ref: SessionRef): (String, Json) = "session" -> text(ref.id)
  new Sessions:
    def list(): Vector[SessionCard]                  = recorder.call("sessions", Json.Null)(inner.list())
    def tell(ref: SessionRef, message: String): Unit =
      recorder.call("tell", args(session(ref), "message" -> text(message)))(inner.tell(ref, message))
    def start(goal: String, scope: Scope, strategy: StrategyName): SessionRef =
      val called = args(
        "goal"     -> text(goal),
        "kind"     -> text(scope.productPrefix),
        "scope"    -> summon[Data[Scope]].encode(scope),
        "strategy" -> text(strategy.toString)
      )
      recorder.call("start", called)(inner.start(goal, scope, strategy))
    def observe(ref: SessionRef, question: String)(using Llm): String =
      recorder.call("observe", args(session(ref), "question" -> text(question)))(inner.observe(ref, question))
    def zoom(ref: SessionRef, part: String): String =
      recorder.call("zoom", args(session(ref), "ref" -> text(part)))(inner.zoom(ref, part))
