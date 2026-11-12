package dspy4s.harness.host

import dspy4s.harness.{Harness, Journal, Secrets, TurnEffects, drive, textSuffix}
import dspy4s.harness.guest.{JobStatus, Mode, Scope}
import dspy4s.harness.kernel.{Command, EffectId, Entry, Logged, Next, Position, RequestId, SessionConfig, SessionId}
import dspy4s.harness.kernel.{SessionState, Source, TurnOutcome, closing, next, repair, state as foldOf}

import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files as JFiles, Path, StandardOpenOption}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.util.Try
import scala.util.control.NonFatal

/** A host of sessions (CODE_HARNESS_DESIGN.md, 10.1). It is free of the types of a runtime, so a Kyo, ZIO or Pekko host
  * stays possible.
  */
trait SessionHost:

  /** Opens the session `id`. A new session needs `Some(config)`. For a session with a journal, `None` keeps the
    * configuration of its last `Activated`, and `Some(config)` activates it with `config`.
    */
  def open(id: SessionId, config: Option[SessionConfig]): Either[HostError, SessionHandle]

/** An open session for a client (10.1).
  *
  * A proposal for the review: section 10.1 names this trait `SessionRef`. The guest API already has the data type
  * `SessionRef`, which the store can hold, so the host trait is `SessionHandle`.
  */
trait SessionHandle:
  def id: SessionId

  /** Puts `command` into the mailbox, with the source `User`. It does not wait for the commit. */
  def send(command: Command): Either[HostError, Unit]

  /** Runs `command` with the source `User`, and waits for its commit. A client uses it when the person must see a
    * refusal, for example `Close` during a turn or a `Grant` after the end.
    */
  def run(command: Command): Either[HostError, SessionState]

  /** The committed state. */
  def state: SessionState

  /** The status of each job of `state` (10.3): `Running` or `Exited` from the job table, and `Stopped` or `Lost` for a
    * job with no process in this host. A client shows it, for example after a stop that left a job of its turn.
    */
  def jobStatus: Map[EffectId, JobStatus]

  /** A feed of the entries of the commits after this call (10.2). */
  def subscribe(capacity: Int): Feed

  /** The committed entries with a `seq` above `seq`. A client reads them after `Behind`. */
  def read(after: Long): Vector[Logged]

  /** What the host of the session does now, apart from the journal (`SessionRuntime.activity`), or none. A client polls
    * it. The default is none.
    */
  def activity: Option[Activity] = None

  /** The size of the last view of the memory and its budget (`SessionRuntime.memoryUse`), or none before the first call
    * to the policy. A client polls it. The default is none.
    */
  def memoryUse: Option[MemoryUse] = None

/** What the host needs to run the turns of a session (10.1). It makes the live part of each activation. */
trait SessionRuntime:

  /** The live part of a new activation of `session` (11: Live): a new REPL, the handlers of the mode of `config` and
    * the effects of a turn. `journal` is the journal of this activation, with its ticket: each commit of the effects
    * and of the recorder goes through it, so a fence after a hard stop reaches them all. `jobs` is the job table of the
    * session, which lives as long as the session is open in this host.
    */
  def activate(session: SessionId, config: SessionConfig, journal: Journal, jobs: JobTable): Activation

  /** The part of the runtime that lives as long as the open session `session`, with the configuration `config` of its
    * open. The host calls it once at each open, before the `Activate` of the open, with the configuration of that
    * `Activate`. So each activation of the open session can use it, as it uses the job table, also the activation of a
    * turn that the `Activate` starts at once for a waiting message. The host closes the result at the end of the open
    * session, after the activation, and also when the `Activate` fails. The default keeps nothing.
    */
  def opened(session: SessionId, config: SessionConfig): AutoCloseable =
    val _ = (session, config)
    () => ()

  /** What the runtime does now for the open session `session`, apart from the journal, or none. It must not wait for a
    * lock that a command holds, because a client polls it while the command runs. The default is none.
    */
  def activity(session: SessionId): Option[Activity] =
    val _ = session
    None

  /** The size of the last view of the memory of the open session `session`, and its budget, or none. The default is
    * none.
    */
  def memoryUse(session: SessionId): Option[MemoryUse] =
    val _ = session
    None

/** The live part of one activation (11). Nothing outside it depends on it, and a new activation makes a new one. */
trait Activation extends AutoCloseable:

  /** The effects of a turn (4.6). */
  def effects: TurnEffects

  /** Steps 2 and 3 of the path of a hard stop (10.3): the stop of the policy call, `ScalaRepl.stopCell` and
    * `SandboxedShell.stopAll`. It returns at once. The worker thread gets no interrupt, so its commits complete. A long
    * policy call so ends before the grace period, and the activation and its REPL stay.
    */
  def stop(): Unit

  /** True when a cell thread did not end after its stop (protection 3 of Phase 0). Then the activation is not safe, and
    * the next turn needs a new activation.
    */
  def unclean: Boolean

  /** Closes the REPL. */
  def close(): Unit

/** The first session host (10.2): one writer and one SQLite file for each session under `root`, the catalog, the timers
  * and the path of a hard stop.
  *
  *   - `open` of a new id makes `<root>/sessions/<id>.db` and commits `Activate`. `open` of an id with a file reads its
  *     journal, and `Activate` repairs an open turn with `TurnEnded(Crashed)` (4.4). An open after `Close` starts a new
  *     writer, and `Activate` opens the session again. A second `open` of an open session gives the same handle.
  *   - A mode above the ceiling of the last `Activated` gives `OpenFailed`, and so does a file that is not a journal.
  *   - A configuration whose `Home`, `Directory` or `Worktree` scope is not an existing folder gives `OpenFailed` with
  *     the path. The check comes before the lock and the file, so the refused open makes no journal.
  *   - The host holds an exclusive lock on `<root>/sessions/<id>.lock` while the session is open. Another host gets
  *     `OpenFailed` for that session, so two hosts never write one file. The lock goes with the process, so a crash
  *     frees it.
  *   - Each activation gets a new ticket and a `WriterJournal` with it. When `next` gives work and no worker runs, the
  *     host starts a worker thread that runs `drive` with that journal and the effects of the activation. The worker
  *     makes the live part of the activation (`SessionRuntime.activate`) at the first turn that needs it, so an open
  *     that only reads starts no REPL, and the thread of the writer never waits for a REPL.
  *   - Before the `Activate` of an open, the host calls `SessionRuntime.opened` once with the configuration of the
  *     open, and keeps the result while the session is open. So each activation of the open can use it, also one that
  *     the `Activate` starts at once for a waiting message. A second `open` of the open session does not call it again.
  *     When the call throws, the open gives `OpenFailed`, commits no `Activate`, and frees the lock. The end of the
  *     session closes the result after the activation, also after a failed `Activate`.
  *   - After `StopRequested`, the host calls `Activation.stop`, and a timer calls `Writer.expire` with the turn of the
  *     stop and the ticket after `limits.grace` (10.3, step 5). When that turn still runs, the writer fences the ticket
  *     and closes the turn, and the next turn gets a new activation.
  *   - After each commit, the host copies the marks of the jobs of the state to the job table (10.3). The table of the
  *     session posts the message of a job with `report` to the writer, so the message also reaches a later activation.
  *   - After a turn ends or the status changes, the host puts the row of the session into the catalog.
  */
final class LocalHost private (
    root   : Path,
    runtime: SessionRuntime,
    secrets: Secrets,
    limits : HostLimits,
    clock  : () => Long,
    catalog: Catalog,
    timers : Timers,
    rebuilt: Rebuilt
) extends SessionHost,
      AutoCloseable:

  private val sessions = scala.collection.mutable.Map.empty[SessionId, OpenSession]

  def open(id: SessionId, config: Option[SessionConfig]): Either[HostError, SessionHandle] =
    // A session that ends frees its lock in its shutdown. The wait for it holds no lock of the host, so the opens of
    // other sessions go on.
    synchronized(sessions.get(id)).filterNot(_.live).foreach(_.awaitEnd())
    synchronized {
      sessions.get(id) match
        case Some(session) if session.live => Right(session)
        case _                             =>
          OpenSession.start(id, config, OpenSession.Place(root, runtime, secrets, limits, clock, catalog, timers)).map {
            session =>
              sessions.update(id, session)
              session
          }
    }

  /** The rows of the catalog. */
  def list: Vector[CatalogRow] = catalog.rows

  /** The open session `id`. Only the laws use it. */
  private[host] def sessionOf(id: SessionId): Option[OpenSession] = synchronized(sessions.get(id))

  /** The files of `sessions` that are not journals, from the rebuild at the start (10.2). */
  def failed: Vector[(Path, String)] = rebuilt.failed

  /** Ends the writer of each open session, closes its activation, stops its jobs and frees its lock. The journals stay.
    */
  def close(): Unit =
    synchronized {
      sessions.values.foreach(_.close())
      sessions.clear()
    }
    timers.close()
    Try(catalog.close()): Unit

object LocalHost:

  /** Starts a host at `root`: it makes the folders, and it rebuilds the catalog from the files (10.2). */
  def start(
      root   : Path,
      runtime: SessionRuntime,
      secrets: Secrets,
      limits : HostLimits = HostLimits(),
      clock  : () => Long = () => System.currentTimeMillis()
  ): Either[HostError, LocalHost] =
    for
      sessions <- Try(JFiles.createDirectories(SessionFile.folderOf(root))).toEither.left
                    .map(e => HostError.OpenFailed(s"the folder of the host did not open: ${e.getMessage}"))
      catalog <- Catalog.open(Catalog.pathOf(root)).left.map(HostError.OpenFailed(_))
      rebuilt <- Catalog.rebuild(catalog, sessions).left.map { e =>
                   catalog.close()
                   HostError.OpenFailed(e)
                 }
    yield LocalHost(root, runtime, secrets, limits, clock, catalog, Timers(), rebuilt)

  /** The sessions of the root of a host, with no runtime and no open session: the rebuild of the catalog that `start`
    * runs (10.2). A root with no folder of journals has no session, and this function then makes no file.
    */
  def sessions(root: Path): Either[HostError, Rebuilt] =
    if !JFiles.isDirectory(SessionFile.folderOf(root)) then Right(Rebuilt(Vector.empty, Vector.empty))
    else
      Catalog.open(Catalog.pathOf(root)).left.map(HostError.OpenFailed(_)).flatMap { catalog =>
        try Catalog.rebuild(catalog, SessionFile.folderOf(root)).left.map(HostError.OpenFailed(_))
        finally catalog.close()
      }

  /** The session of each journal file of the root, in the order of the ids (`Catalog.journalFiles`). It reads only the
    * names of the files, so it needs no catalog: a session whose row is missing or old still has its journal.
    */
  def journals(root: Path): Either[HostError, Vector[SessionId]] =
    Catalog.journalFiles(SessionFile.folderOf(root)).left.map(HostError.OpenFailed(_))
      .map(_.map(file => SessionId(file.getFileName.toString.stripSuffix(".db"))))

/** The name of a job for a person and for the model: the cell and the number of its `spawn`, for example `12.0`. The
  * guest API gives the model the same two numbers as `JobId(12, 0)` (10.3).
  */
def jobName(job: EffectId): String = s"${job.cell}.${job.n}"

/** One open session of a `LocalHost`: its writer, its feeds, its jobs, its lock, and the live part of its activation
  * and worker. `onCommit` runs on the thread of the writer after each commit, and the worker thread runs the turns. One
  * lock (`guard`) holds the state of the activation and the worker.
  *
  * The writer and the job table call the session through hooks that `start` sets after the session exists, so no thread
  * sees a session that is not complete.
  */
private final class OpenSession private (
    val id : SessionId,
    place  : OpenSession.Place,
    writer : Writer,
    feeds  : Feeds,
    jobs   : JobTable,
    start  : Vector[Logged],
    channel: FileChannel,
    lock   : FileLock
) extends SessionHandle:
  import OpenSession.*

  private val guard    = Object()
  private val finished = CountDownLatch(1)

  // The live part. Only code that holds `guard` changes it.
  private var activation: Option[(WorkerTicket, Activation)] = None
  private var openPart: Option[AutoCloseable]                = None // the result of `SessionRuntime.opened`
  private var worker: Option[Long]                           = None
  private var workers                                        = 0L
  private var tickets                                        = 0L
  private var pendingTicket: Option[WorkerTicket]            = None
  private var stopTurn                                       = -1L
  private var row                                            = catalogRow(id)(start)
  private var putOnce                                        = false
  @volatile private var endedFlag                            = false

  /** True while the session takes commands. */
  def live: Boolean = !endedFlag && !writer.ended && !writer.state.closed

  /** A session whose state is closed gives `Ended` at once: its writer ends right after the commit of `Ended`. */
  def send(command: Command): Either[HostError, Unit] =
    if writer.state.closed then Left(HostError.Ended) else writer.post(command, Source.User)

  def run(command: Command): Either[HostError, SessionState] =
    if writer.state.closed then Left(HostError.Ended) else writer.run(command, Source.User)

  def state: SessionState = writer.state

  def jobStatus: Map[EffectId, JobStatus] =
    val now = writer.state
    now.jobs.keys.map(job => job -> jobs.status(job, now)).toMap

  def subscribe(capacity: Int): Feed = feeds.subscribe(capacity)

  def read(after: Long): Vector[Logged] = writer.log.filter(_.seq > after)

  override def activity: Option[Activity] = place.runtime.activity(id)

  override def memoryUse: Option[MemoryUse] = place.runtime.memoryUse(id)

  /** Makes the next write of the session fail. Only the laws of a failed write use it. */
  private[host] def breakWritesForLaws(): Unit = writer.breakFileForLaws()

  /** Waits at most 30 seconds for the end of a session that is ending. */
  def awaitEnd(): Unit = finished.await(30, TimeUnit.SECONDS): Unit

  /** The end of the session by the host (`LocalHost.close`, the end of a client). */
  def close(): Unit = shutdown()

  /** The end of this open session, from each path: `Ended`, a failed write, or the close of the host. It runs once, and
    * it does all the work of the end: the activation and its REPL close, the result of `SessionRuntime.opened` closes,
    * the jobs stop, the writer ends, the lock goes, and a reopen in this host can go on. After it, a commit starts no
    * worker.
    */
  private def shutdown(): Unit =
    val last = guard.synchronized {
      if endedFlag then None
      else
        endedFlag = true
        val live = activation.map(_._2)
        val part = openPart
        activation = None
        openPart = None
        Some((live, part))
    }
    last.foreach { (live, part) =>
      live.foreach(a => Try(a.close()): Unit)
      part.foreach(p => Try(p.close()): Unit)
      Try(jobs.close()): Unit
      writer.close()
      Try(lock.release()): Unit
      Try(channel.close()): Unit
      finished.countDown()
    }

  /** After a failed write, on the thread of the writer (10.2: "A failed write ends this session only"). */
  private def onFailure(error: String): Unit =
    val _ = error
    shutdown()

  /** Commits the `Activate` of this open. */
  private def activate(config: SessionConfig): Either[HostError, Unit] =
    writer.run(Command.Activate(Harness.versions, config), Source.Harness).map(_ => ())

  /** Calls `SessionRuntime.opened` once for this open, with the configuration `config` of the open, and keeps the
    * result until the end of the session. `start` calls it before the `Activate`, when no commit and no other thread
    * can end the session. When the `Activate` then fails, the close of the session closes the result.
    */
  private def keepOpened(config: SessionConfig): Either[HostError, Unit] =
    Try(place.runtime.opened(id, config)).toEither.left
      .map(e => HostError.OpenFailed(s"the runtime did not open the session: $e"))
      .map(part => guard.synchronized { openPart = Some(part) })

  /** After each commit, on the thread of the writer. It must return at once. After the end, it does nothing. */
  private def onCommit(s: SessionState): Unit =
    val ended = guard.synchronized {
      if !endedFlag then
        Try(jobs.sync(s)): Unit
        if s.stopping && s.position != Position.NoTurn && s.lastTurn != stopTurn then
          stopTurn = s.lastTurn
          activation.foreach((_, live) => Try(live.stop()): Unit)
          // The ticket exists before its activation, so a stop while a REPL starts also gets its timer.
          activation.map(_._1).orElse(pendingTicket).foreach { ticket =>
            place.timers.after(place.limits.grace)(() => writer.expire(s.lastTurn, ticket)): Unit
          }
        putRow()
        if !s.closed && next(s) != Next.Wait then ensureWorker()
      s.closed
    }
    if ended then shutdown()

  /** Starts a worker when the work needs one. Holds `guard`. A worker that runs takes the new work in its next round,
    * unless its activation is not safe: then the next work gets a new worker and a new activation, and the old worker
    * keeps its fenced ticket.
    */
  private def ensureWorker(): Unit =
    // A running worker with no activation yet makes one now, so the work waits for it: two activations never run
    // turns of one session at the same time.
    val unsafe = activation.exists((ticket, live) => writer.isFenced(ticket) || live.unclean)
    if worker.isEmpty || unsafe then
      if unsafe then activation.foreach((ticket, _) => writer.fence(ticket))
      workers += 1
      val n     = workers
      val fresh = activation.isEmpty || unsafe
      worker = Some(n)
      Thread.ofPlatform().name(s"harness-worker-${id.value}-$n").daemon(true).start(() => runWorker(n, fresh)): Unit

  /** One worker: a new activation when the last one is not safe, then `drive`. At the end, it starts the next worker
    * when work came during its last round.
    */
  /** The rule of a failure of the turn machinery: the turn ends, its waiting messages count as seen, and the session
    * waits for the next command. So a failure that occurs again never starts the same turn again by itself. The `Abort`
    * of the harness commits `StopRequested`, which sets `waiting` to 0, so the end of the turn starts no new turn
    * (4.2). `decide` takes a `StopRequested` only through `Abort`, so the end is two commits.
    */
  private def runWorker(n: Long, fresh: Boolean): Unit =
    var clean = false
    try
      val (ticket, live) = if fresh then newActivation() else guard.synchronized(activation.get)
      try
        drive(WriterJournal(writer, ticket), live.effects): Unit
        clean = true
      catch
        case NonFatal(e) if !writer.isFenced(ticket) && !writer.ended =>
          // The turn stays open after an exception of the effects. The repair closes it as after a crash (4.4).
          guard.synchronized { activation = activation.filterNot(_._1 == ticket) }
          Try(live.close()): Unit
          writer.run(Command.Abort(s"the turn failed: $e"), Source.Harness): Unit
          writer.run(Command.Commit(repair(writer.state)), Source.Harness): Unit
          clean = true
        case NonFatal(_) => clean = true
    catch
      case NonFatal(e) =>
        // No activation: the open turn ends with the failure, so the session does not wait for ever.
        val reason = s"the activation failed: $e"
        writer.run(Command.Abort(reason), Source.Harness): Unit
        writer.run(
          Command.Commit(closing(writer.state) :+ Entry.TurnEnded(TurnOutcome.Failed(reason))),
          Source.Harness
        ): Unit
        clean = true
    finally
      guard.synchronized {
        if worker.contains(n) then
          worker = None
          // After a fatal error, no worker starts again: the next command of a client tries again.
          if clean && live && next(writer.state) != Next.Wait then ensureWorker()
      }

  /** A new activation with a new ticket. The last activation closes. */
  private def newActivation(): (WorkerTicket, Activation) =
    val (ticket, old) = guard.synchronized {
      tickets += 1
      val made = WorkerTicket(tickets)
      pendingTicket = Some(made)
      val old = activation.map(_._2)
      activation = None
      (made, old)
    }
    old.foreach(o => Try(o.close()): Unit)
    var started = false
    try
      val made  = place.runtime.activate(id, writer.state.config, WriterJournal(writer, ticket), jobs)
      val taken = guard.synchronized {
        pendingTicket = None
        // A shutdown while the REPL started: this activation closes at once.
        if endedFlag then false
        else
          activation = Some((ticket, made))
          true
      }
      started = true
      if !taken then Try(made.close()): Unit
      (ticket, made)
    finally if !started then guard.synchronized { pendingTicket = None }

  /** Puts the row of the session into the catalog when its status changes, and at the first commit of this open. */
  private def putRow(): Unit =
    val fresh = writer.log.reverseIterator.takeWhile(_.seq > row.lastSeq).toVector.reverse
    val next  = fresh.foldLeft(row)(catalogRow(id).step)
    if !putOnce || next.status != row.status then
      putOnce = true
      // A failed write of the catalog never ends a session: the next rebuild corrects the row.
      place.catalog.put(next): Unit
    row = next

  /** The message of a job with `report` at its end (10.3). The writer of the session gets it, so it also reaches a
    * later activation.
    */
  private def reportJob(job: EffectId, status: JobStatus): Unit =
    // The cut keeps whole characters, because the journal refuses a surrogate with no pair, and a failed write ends the
    // session.
    val output = jobs.output(job).fold("")(text => textSuffix(text, ReportOutput))
    val text   = s"The job ${jobName(job)} ended with the status $status. The end of its output:\n$output"
    writer.post(Command.Send(RequestId(s"job:${job.cell}:${job.n}"), text, Map.empty), Source.Harness): Unit

private object OpenSession:

  /** What each open session of a host shares. */
  final case class Place(
      root   : Path,
      runtime: SessionRuntime,
      secrets: Secrets,
      limits : HostLimits,
      clock  : () => Long,
      catalog: Catalog,
      timers : Timers
  )

  /** The most characters of the output of a job in its message. */
  private val ReportOutput = 2_000

  /** Opens the session `id`: the folder of the scope, the lock, the file, the journal, the configuration, the writer,
    * the call of `SessionRuntime.opened` and the `Activate`. A folder that is not there refuses the open before the
    * lock and the file, so it makes no journal. A failed step after the writer closes the session, and the close also
    * closes the result of `opened`.
    */
  def start(id: SessionId, config: Option[SessionConfig], place: Place): Either[HostError, OpenSession] =
    val path = SessionFile.pathOf(place.root, id)
    if config.isEmpty && !JFiles.exists(path) then Left(HostError.OpenFailed("a new session needs a configuration"))
    else
      config.flatMap(c => folderFault(c.scope)) match
        case Some(fault) => Left(HostError.OpenFailed(fault))
        case None        => lockOf(path.resolveSibling(s"${id.value}.lock")).flatMap { (channel, lock) =>
            val opened =
              for
                file    <- SessionFile.open(path, id).left.map(HostError.OpenFailed(_))
                start   <- file.readFrom(0).left.map(e => failed(file, e))
                setting <- chosen(config, start).left.map(e => failed(file, e))
                session  = made(id, place, file, start, channel, lock)
                // `opened` comes before the `Activate`, because the `Activate` can start a turn for a waiting message
                // at once, and the activation of that turn needs the result of `opened`.
                _ <- session.keepOpened(setting).left.map { e =>
                       session.close()
                       e
                     }
                _ <- session.activate(setting).left.map { e =>
                       session.close()
                       e
                     }
              yield session
            if opened.isLeft then
              Try(lock.release()): Unit
              Try(channel.close()): Unit
            opened
          }

  /** The fault of the folder of `scope`, or none (10.2). A `Home`, a `Directory` or a `Worktree` scope names a folder
    * of the user, and that folder must exist. The harness makes the folder of a `Scratch` or a `Fork` scope, so these
    * have no check. An open with no configuration also has no check, so a client can still read the journal of a
    * session whose folder is gone. The shell then names the folder at each command (5.4).
    */
  private def folderFault(scope: Scope): Option[String] =
    val folder = scope match
      case Scope.Home(path)                    => Some(path)
      case Scope.Directory(path)               => Some(path)
      case Scope.Worktree(path, _)             => Some(path)
      case Scope.Scratch(_) | Scope.Fork(_, _) => None
    folder.flatMap { text =>
      val path = Try(Path.of(text)).toOption
      if path.exists(JFiles.isDirectory(_)) then None
      else if path.exists(JFiles.exists(_)) then Some(s"the path $text of the session is not a folder")
      else Some(s"the folder $text of the session does not exist")
    }

  /** The session with its writer, feeds and jobs. The hooks of the writer and of the jobs reach the session only after
    * it exists.
    */
  private def made(
      id     : SessionId,
      place  : Place,
      file   : SessionFile,
      start  : Vector[Logged],
      channel: FileChannel,
      lock   : FileLock
  ): OpenSession =
    val committed = AtomicReference[SessionState => Unit](_ => ())
    val failing   = AtomicReference[String => Unit](_ => ())
    val reported  = AtomicReference[(EffectId, JobStatus) => Unit]((_, _) => ())
    val feeds     = Feeds()
    val writer    = Writer(
      file,
      start,
      place.clock,
      place.secrets,
      feeds,
      place.limits.mailbox,
      s => committed.get()(s),
      e => failing.get()(e)
    )
    val jobs    = JobTable(place.limits.grace, (job, status) => reported.get()(job, status))
    val session = OpenSession(id, place, writer, feeds, jobs, start, channel, lock)
    committed.set(s => session.onCommit(s))
    failing.set(e => session.onFailure(e))
    reported.set((job, status) => session.reportJob(job, status))
    session

  private def failed(file: SessionFile, reason: String): HostError =
    file.close()
    HostError.OpenFailed(reason)

  /** The configuration of this open: `config`, or the last one of the journal. A new session needs `config`, and a mode
    * or a ceiling above the ceiling of the session fails.
    */
  private def chosen(config: Option[SessionConfig], start: Vector[Logged]): Either[String, SessionConfig] =
    if start.isEmpty then config.toRight("a new session needs a configuration")
    else
      val last = foldOf(start).config
      config match
        case None                                                                       => Right(last)
        case Some(c) if within(c.mode, last.ceiling) && within(c.ceiling, last.ceiling) => Right(c)
        case Some(_)                                                                    => Left("the mode of the configuration is above the ceiling of the session")

  private def within(mode: Mode, ceiling: Mode): Boolean = mode.meet(ceiling) == mode

  /** The exclusive lock of an open session. Another host, in this process or in another, gets `OpenFailed`. */
  private def lockOf(path: Path): Either[HostError, (FileChannel, FileLock)] =
    val busy = HostError.OpenFailed("the session is open in another host")
    Try(FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)).toEither.left
      .map(e => HostError.OpenFailed(s"the lock of the session did not open: ${e.getMessage}"))
      .flatMap { channel =>
        val taken =
          try Option(channel.tryLock())
          catch case _: OverlappingFileLockException => None
        taken.map(lock => (channel, lock)).toRight {
          channel.close()
          busy
        }
      }
