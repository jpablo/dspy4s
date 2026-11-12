package dspy4s.harness.host

import dspy4s.core.contracts.RuntimeContext
import dspy4s.harness.{Budget, Harness, Journal, MemoryTree, Secrets, TurnEffects, drive, textSuffix}
import dspy4s.harness.guest.{JobStatus, Mode, Scope}
import dspy4s.harness.kernel.{Command, EffectId, Entry, Logged, Next, Position, ReleaseItem, ReleaseStep, RequestId}
import dspy4s.harness.kernel.{JobSelector, Owner, Rejection, ReleasePlans, ReleaseResult, ScopeState}
import dspy4s.harness.kernel.{irreversible, releasePlan}
import dspy4s.harness.sandbox.Layout
import dspy4s.harness.kernel.{
  SessionConfig, SessionId, SessionOutcome, SessionState, Source, TurnOutcome, closing, next
}
import dspy4s.harness.kernel.{repair, state as foldOf}

import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files as JFiles, Path, StandardOpenOption}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, Executors, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.*
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

/** What the handler of `Sessions` needs from its host (CODE_HARNESS_DESIGN.md, 8.7). `LocalHost` gives it. Each session
  * that a handler reaches is open in the same host, because a host holds a lock on each open session.
  */
trait Peers:

  /** The rows of the catalog. */
  def rows: Vector[CatalogRow]

  /** Opens the session `id`, as `SessionHost.open` does. A session that is open gives its handle. */
  def open(id: SessionId, config: Option[SessionConfig]): Either[HostError, SessionHandle]

  /** Opens the new session `id` with `config`, and commits `Begin` with its task `task` with the source `source`: its
    * `Activated` and its task in one commit (8.6). It gives the state after that commit. An id with a journal gives
    * `Rejected(NotNew)`, so a second `start` never activates a session again.
    */
  def openWith(
      id    : SessionId,
      config: SessionConfig,
      task  : Command.Send,
      source: Source
  ): Either[HostError, SessionState]

  /** Runs `command` in the session `id` with the source `source`, and waits for its commit. A session of the catalog
    * that is not open in this host opens first, with the configuration of its journal. A session whose journal has
    * ended gives `Ended`, and it does not open again: the check reads the journal under the lock of the host, not the
    * row of the catalog, which can lag. An id that is not valid or that the catalog does not have gives `Refused`.
    */
  def runAs(id: SessionId, command: Command, source: Source): Either[HostError, SessionState]

  /** The committed journal of the session `id`: from its writer when it is open, else from its file, which opens read
    * only (`SessionFile.readOnly`). An id that is not valid or that the catalog does not have gives an error, and no
    * file appears.
    */
  def journalOf(id: SessionId): Either[String, Vector[Logged]]

  /** The part `ref` of the memory of the session `id` (8.7): from its journal and the table `memory` of its file, with
    * no LM call and no write. A node with no built line gives the line of its source, as a spent budget does (7.1). An
    * id as for `journalOf` gives an error.
    */
  def zoomInto(id: SessionId, ref: String): Either[String, String]

  /** The scope of the new session `session` of `start` (8.7), with its folder made. A `Scratch` and a `Worktree` must
    * have an empty path: the host makes `<work>/<session>`, a new folder for a scratch and a new worktree of a new
    * branch for a worktree. A `Directory` must be an absolute path of a folder that exists. A `Home`, a `Fork`, a path
    * that is not absolute or not empty as these rules say, a worktree whose branch is its base, and a repository inside
    * the scope of a session that has not ended give an error.
    */
  def make(scope: Scope, session: SessionId): Either[String, Scope]

object Peers:

  /** A host with no other session: each call gives an error. A runtime with no host, as in phase 7, uses it. */
  val none: Peers = new Peers:
    private val error                                                                      = "this runtime has no host of sessions"
    def rows: Vector[CatalogRow]                                                           = Vector.empty
    def open(id: SessionId, config: Option[SessionConfig])                                 = Left(HostError.OpenFailed(error))
    def openWith(id: SessionId, config: SessionConfig, task: Command.Send, source: Source) =
      Left(HostError.OpenFailed(error))
    def runAs(id    : SessionId, command: Command, source: Source)          = Left(HostError.OpenFailed(error))
    def journalOf(id: SessionId): Either[String, Vector[Logged]]            = Left(error)
    def zoomInto(id : SessionId, ref    : String): Either[String, String]   = Left(error)
    def make(scope  : Scope, session    : SessionId): Either[String, Scope] = Left(error)

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
  *
  * Since phase 9 (8.7), the host is the `Peers` of the handler of `Sessions`, so its runtime comes from `runtimeOf`
  * with the host. It makes the folders of `start` under `work`, outside `root`, because the sandbox hides `root`.
  *
  *   - The live report: after a `TurnEnded` or an `Ended` of a session whose owner is a session, the host gives the
  *     reports to one reporter thread of its own. That thread holds no `guard` and no lock of the host, and it puts
  *     each report into the mailbox of the owner with `Writer.tryPost`, which never waits. A report that does not fit,
  *     or whose owner is not open, waits for the pass. No writer thread ever calls `run`.
  *   - The pass (`reportTo`) runs on the thread that opens an owner, after the owner is in `sessions`, so a live report
  *     during the open reaches it, and with no lock of the host. At its start the host opens each session of
  *     `ReportPass.repairable`.
  *   - After each `TurnEnded` in a git folder, the host commits `ScopeRecorded` with the snapshot of `scopes`, on one
  *     ordered thread for each session. A snapshot that a newer `TurnEnded` makes old does not commit. The controller,
  *     whose scope is `Home`, gets no snapshot.
  *   - The row of a session goes to the catalog when its status or its ledger changes.
  *   - `release` ends a session with its release plan.
  */
final class LocalHost private (
    root     : Path,
    runtimeOf: Peers => SessionRuntime,
    secrets  : Secrets,
    limits   : HostLimits,
    clock    : () => Long,
    catalog  : Catalog,
    timers   : Timers,
    rebuilt  : Rebuilt,
    scopes   : Scopes,
    hooks    : HostHooks,
    work     : Path
) extends SessionHost,
      Peers,
      AutoCloseable:

  private val sessions = scala.collection.mutable.Map.empty[SessionId, OpenSession]

  // The runtime gets the host, and the host gets the runtime, so the runtime comes at its first use.
  private lazy val runtime: SessionRuntime = runtimeOf(this)

  // The live reports (8.7): each session whose turn or session ended, with its owner. The writers put them here, and
  // the reporter thread takes them. A put never waits, so the thread of a writer never waits for a report. The reporter
  // takes the lock of the host for a short time, so it is a platform thread: on Java 21, a virtual thread that waits
  // for a lock holds its carrier thread.
  private val due                   = LinkedBlockingQueue[(SessionId, SessionId)]()
  @volatile private var stopReports = false
  private val reporter              = Thread.ofPlatform().name("harness-reporter").daemon(true).start(() => reportLoop())

  def open(id: SessionId, config: Option[SessionConfig]): Either[HostError, SessionHandle] =
    openLive(id, config).map { (session, fresh) =>
      if fresh then afterOpen(id)
      session
    }

  /** Opens the session `id`, or gives it when it is open, with true when this call opened it. */
  private def openLive(id: SessionId, config: Option[SessionConfig]): Either[HostError, (OpenSession, Boolean)] =
    // A session that ends frees its lock in its shutdown. The wait for it holds no lock of the host, so the opens of
    // other sessions go on.
    synchronized(sessions.get(id)).filterNot(_.live).foreach(_.awaitEnd())
    synchronized {
      sessions.get(id) match
        case Some(session) if session.live => Right((session, false))
        case _                             => OpenSession.start(id, config, place).map { session =>
            sessions.update(id, session)
            (session, true)
          }
    }

  /** The report pass of a session that this host opened (8.7), on the thread of the open and with no lock of the host.
    * A pass that fails loses no report, because the next open runs it again.
    */
  private def afterOpen(id: SessionId): Unit = reportTo(id): Unit

  /** The rows of the catalog. */
  def list: Vector[CatalogRow] = catalog.rows

  def rows: Vector[CatalogRow] = list

  def openWith(id: SessionId, config: SessionConfig, task: Command.Send, source: Source)
      : Either[HostError, SessionState] =
    if !SessionId.valid(id.value) then Left(HostError.Refused(s"${id.value} is not the id of a session"))
    else
      val opened = synchronized {
        if sessions.contains(id) || JFiles.exists(SessionFile.pathOf(root, id)) then
          Left(HostError.Rejected(Rejection.NotNew))
        else
          OpenSession.start(id, Some(config), place, Some((task, source))).map { session =>
            sessions.update(id, session)
            session.state
          }
      }
      opened.foreach(_ => afterOpen(id))
      opened

  def runAs(id: SessionId, command: Command, source: Source): Either[HostError, SessionState] =
    liveOrOpen(id).flatMap(_.runAs(command, source))

  /** The live session `id`, or the session that this call opens, with the report pass of a new open (8.7). A session
    * that has ended gives `Ended`: only `open` opens it again. An id that is not valid gives `Refused`.
    */
  private def liveOrOpen(id: SessionId): Either[HostError, OpenSession] =
    if !SessionId.valid(id.value) then Left(HostError.Refused(s"${id.value} is not the id of a session"))
    else
      // The check and the open hold the lock of the host, and the pass and the commits after it do not.
      val target = synchronized {
        sessions.get(id).filter(_.live) match
          case Some(session) => Right((session, false))
          case None          =>
            // The row of the catalog can lag, so the journal says whether the session has ended.
            journalOf(id).left.map(HostError.Refused(_)).flatMap { journal =>
              if foldOf(journal).closed then Left(HostError.Ended) else openLive(id, None)
            }
      }
      target.map { (session, fresh) =>
        if fresh then afterOpen(id)
        session
      }

  def journalOf(id: SessionId): Either[String, Vector[Logged]] =
    if !SessionId.valid(id.value) then Left(s"${id.value} is not the id of a session")
    else
      synchronized(sessions.get(id)).filter(_.live) match
        case Some(session) => Right(session.read(-1))
        case None          =>
          if catalog.row(id).isEmpty && !synchronized(sessions.contains(id)) then Left(s"no session ${id.value}")
          else
            SessionFile.readOnly(SessionFile.pathOf(root, id), id).flatMap { file =>
              try file.readFrom(0)
              finally file.close()
            }

  def zoomInto(id: SessionId, ref: String): Either[String, String] =
    journalOf(id).flatMap { journal =>
      FileLines.readOnly(SessionFile.pathOf(root, id), id).flatMap { lines =>
        try
          // A spent budget gives the line of the source, so the memory comes with no LM call (7.1).
          val tree             = MemoryTree.withLeaves(lines, summaries = () => Budget(0))
          given RuntimeContext = RuntimeContext()
          tree.update(journal, tree.empty).left.map(_.message).flatMap(tree.zoom(_, ref))
        finally lines.close()
      }
    }

  def make(scope: Scope, session: SessionId): Either[String, Scope] =
    val folder = work.resolve(session.value).toString
    scope match
      case Scope.Scratch("") =>
        val made = Scope.Scratch(folder)
        scopes.make(made).map(_ => made)
      case Scope.Worktree("", branch, repo, base) =>
        val repository = Try(Path.of(repo)).toOption.filter(_.isAbsolute)
        if branch == base then Left(s"the branch $branch is its own base")
        else if repository.isEmpty then Left(s"the repository $repo is not an absolute path")
        else if repository.exists(inWritableScope) then
          Left(s"the repository $repo is inside the folder of a session that has not ended")
        else
          val made = Scope.Worktree(folder, branch, repo, base)
          scopes.make(made).map(_ => made)
      case Scope.Directory(path) =>
        if Try(Path.of(path)).toOption.exists(p => p.isAbsolute && JFiles.isDirectory(p)) then Right(scope)
        else Left(s"the folder $path is not an absolute path of a folder that exists")
      case Scope.Scratch(_) | Scope.Worktree(_, _, _, _) =>
        Left("the path of a Scratch or a Worktree of start must be empty: the host makes the folder")
      case Scope.Home(_) | Scope.Fork(_, _) => Left(s"start takes no ${scope.productPrefix} scope")

  /** The place that each open session of the host shares. */
  private def place: OpenSession.Place =
    OpenSession.Place(
      root,
      runtime,
      secrets,
      limits,
      clock,
      catalog,
      timers,
      scopes,
      (child, owner) => due.offer((child, owner)): Unit
    )

  /** True when `path` is inside the folder of a session that has not ended and can write there. The controller has the
    * scope `Home` and no write access, so its scope does not count.
    */
  private def inWritableScope(path: Path): Boolean =
    val real   = Layout.real(path)
    val opened = synchronized(sessions.values.filter(_.live).map(_.state.config.scope).toVector)
    val listed = catalog.rows.filter(notEnded).flatMap(_.scope)
    (opened ++ listed).exists {
      case Scope.Home(_) => false
      case other         => folderOf(other).exists(p => real.startsWith(Layout.real(p)))
    }

  /** True when the row is of a session that has not ended. */
  private def notEnded(row: CatalogRow): Boolean =
    row.status match
      case SessionStatus.Ended(_) => false
      case _                      => true

  /** The folder of `scope`, or none for an empty or a relative path. */
  private def folderOf(scope: Scope): Option[Path] =
    val text = scope match
      case Scope.Home(p)              => p
      case Scope.Directory(p)         => p
      case Scope.Worktree(p, _, _, _) => p
      case Scope.Scratch(p)           => p
      case Scope.Fork(p, _)           => p
    Try(Path.of(text)).toOption.filter(p => text.nonEmpty && p.isAbsolute)

  /** The report pass for the owner `owner` (8.6, 8.7): it opens the owner when it is not open, and sends each report of
    * `ReportPass.owed` with the source `Source.Child(session)`, and waits for each commit. It gives the number of
    * reports that the owner committed. An owner that has ended gets none, and the pass runs again when it opens.
    */
  def reportTo(owner: SessionId): Either[HostError, Int] =
    if !SessionId.valid(owner.value) then Left(HostError.Refused(s"${owner.value} is not the id of a session"))
    else
      val target = synchronized(sessions.get(owner)).filter(_.live) match
        case Some(session) => Right(Some(session))
        case None          => journalOf(owner).left.map(HostError.Refused(_)).flatMap { journal =>
            if foldOf(journal).closed then Right(None) else openLive(owner, None).map((session, _) => Some(session))
          }
      target.flatMap(_.fold(Right(0))(session => pass(owner, session)))

  /** Sends each report of `ReportPass.owed` that `session`, the owner `owner`, does not have, and waits for each
    * commit. The first refusal stops the pass, and the reports after it wait for the next pass.
    */
  private def pass(owner: SessionId, session: OpenSession): Either[HostError, Int] =
    ReportPass.owed(owner, session.state, catalog.rows, journalOf).foldLeft[Either[HostError, Int]](Right(0)) {
      case (Right(n), (from, report)) =>
        if session.state.requests(report.request) then Right(n)
        else
          hooks.beforeReport(from, report)
          session.runAs(report, Source.Child(from)).map(_ => n + 1)
      case (refused, _) => refused
    }

  /** The reporter thread: it takes each live report of `due` until the close of the host. A report that fails waits for
    * the pass. The close interrupts the thread and does not wait for it.
    */
  private def reportLoop(): Unit =
    try
      while !stopReports do
        val (child, owner) = due.take()
        try liveReport(child, owner)
        catch case NonFatal(_) => ()
    catch case _: InterruptedException => ()

  /** The live report of `child` to `owner` (8.7): the reports of `ReportPass.owed` for the row of `child`, when `owner`
    * is open in this host. `Writer.tryPost` never waits, so the first report that does not fit stops the reports after
    * it, and the next pass sends them. An owner that is not open gets its reports at its open.
    */
  private def liveReport(child: SessionId, owner: SessionId): Unit =
    synchronized(sessions.get(owner)).filter(_.live).foreach { target =>
      ReportPass.owed(owner, target.state, catalog.row(child).toVector, journalOf).forall { (from, report) =>
        hooks.beforeReport(from, report)
        target.tryPost(report, Source.Child(from))
      }: Unit
    }

  /** The release plan of `plans` for the session `id` and `outcome`, with the state of its scope, and with no effect:
    * the dry run of CODE_HARNESS.md, 7.9. The state of the scope comes from `scopes`. It is `owned` when its folder is
    * under `work`, and `shared` when another session that has not ended has the same folder. The ref of a kept branch
    * is `refs/archive/<id>`, and the archive folder is `<root>/archive/<id>`. A session that runs its release gives the
    * plan of its `Releasing`.
    */
  def planOf(id: SessionId, outcome: SessionOutcome, plans: ReleasePlans = releasePlan)
      : Either[HostError, (ScopeState, Vector[ReleaseItem])] =
    journalOf(id).left.map(HostError.Refused(_)).flatMap { journal =>
      val s = foldOf(journal)
      if s.closed then Left(HostError.Ended)
      else
        val scope = scopeStateOf(id, s.config.scope)
        Right((scope, if s.releasing then releaseOf(journal)._2 else plans(scope, outcome, refOf(id), archiveOf(id))))
    }

  /** Ends the session `id` with `outcome` and its release plan of `plans` (7.9, 8.7).
    *
    *   - The controller gives `Refused`, because it never ends (8.1). A session with an open turn gives
    *     `Rejected(TurnOpen)`, and no step runs.
    *   - The host commits `Releasing(plan)`, so no message and no turn come in, then `StopJobs(All)`, and it waits for
    *     the end of the jobs.
    *   - Before each item, the host asks `irreversible` with the items whose steps are `Done` and then the item. An
    *     irreversible item runs only when `confirm` gives true for it, with `confirmed`, else it gets `Skipped`. Each
    *     step commits `Released(step)` before the next one, and then the host commits `Close(outcome, reason)`.
    *   - A session that runs its release goes on with the plan of its `Releasing`, from the first item with no
    *     `Released`, so a crash in a release loses no step.
    *
    * It gives the steps of this call.
    */
  def release(
      id     : SessionId,
      outcome: SessionOutcome,
      reason : String,
      confirm: ReleaseItem => Boolean,
      plans  : ReleasePlans = releasePlan
  ): Either[HostError, Vector[ReleaseStep]] =
    if id == Controller.id then Left(HostError.Refused("the controller never ends, so it has no release"))
    else
      for
        session <- liveOrOpen(id)
        begun   <- releaseStart(id, session, outcome, plans)
        _       <- session.runAs(Command.StopJobs(JobSelector.All, "the release of the session"), Source.Harness)
        _       <- Either.cond(
               session.awaitJobs(limits.grace * 3 + 2.seconds),
               (),
               HostError.Refused("a job of the session still runs, so the release waits: close the session again")
             )
        steps <- releaseSteps(session, begun._1, begun._2, scopeStateOf(id, session.state.config.scope), confirm)
        _     <- session.runAs(Command.Close(outcome, reason), Source.User)
      yield steps

  /** The steps before this call and the items to run of the release of `session` (8.7): of its `Releasing` when it runs
    * one, else of a new `Releasing` with the plan of `plans`. A session with an open turn gives `Rejected(TurnOpen)`.
    */
  private def releaseStart(id: SessionId, session: OpenSession, outcome: SessionOutcome, plans: ReleasePlans)
      : Either[HostError, (Vector[ReleaseStep], Vector[ReleaseItem])] =
    val s = session.state
    if s.releasing then Right(releaseOf(session.read(-1)))
    else if s.position != Position.NoTurn then Left(HostError.Rejected(Rejection.TurnOpen))
    else
      val plan = plans(scopeStateOf(id, s.config.scope), outcome, refOf(id), archiveOf(id))
      session.runAs(Command.Commit(Vector(Entry.Releasing(plan))), Source.Harness).map(_ => (Vector.empty, plan))

  /** Runs each item of `items` in order, and commits `Released` with its step before the next item (7.9). `before`
    * holds the steps of the release before this call. An item that `irreversible` gives, with the items whose steps are
    * `Done` and then the item, runs only when `confirm` gives true, else it gets `Skipped`. A failed commit stops the
    * release, and the next call goes on from the first item with no `Released`.
    */
  private def releaseSteps(
      session: OpenSession,
      before : Vector[ReleaseStep],
      items  : Vector[ReleaseItem],
      state  : ScopeState,
      confirm: ReleaseItem => Boolean
  ): Either[HostError, Vector[ReleaseStep]] =
    items.foldLeft[Either[HostError, Vector[ReleaseStep]]](Right(Vector.empty)) {
      case (Right(steps), item) =>
        val done   = (before ++ steps).collect { case ReleaseStep(ran, ReleaseResult.Done) => ran }
        val risky  = irreversible(done :+ item, state).contains(item)
        val result =
          if risky && !confirm(item) then ReleaseResult.Skipped("the person gave no confirmation")
          else scopes.release(item, confirmed = risky)
        val step = ReleaseStep(item, result)
        session.runAs(Command.Commit(Vector(Entry.Released(step))), Source.Harness).map(_ => steps :+ step)
      case (stopped, _) => stopped
    }

  /** The last `Releasing` of `journal`: the steps after it, and its items with no step, in order. A journal with no
    * `Releasing` gives two empty vectors.
    */
  private def releaseOf(journal: Vector[Logged]): (Vector[ReleaseStep], Vector[ReleaseItem]) =
    val at = journal.lastIndexWhere(_.entry match
      case Entry.Releasing(_) => true
      case _                  => false)
    journal.lift(at).map(_.entry) match
      case Some(Entry.Releasing(plan)) =>
        val done = journal.drop(at + 1).collect { case Logged(_, _, Entry.Released(step)) => step }
        (done, plan.drop(done.size))
      case _ => (Vector.empty, Vector.empty)

  /** The state of `scope` for a release plan of the session `id` (7.9): from `scopes`, with `owned` when its folder is
    * under `work`, and `shared` when another session that has not ended has the same folder.
    */
  private def scopeStateOf(id: SessionId, scope: Scope): ScopeState =
    val folder = folderOf(scope).map(Layout.real)
    val base   = Layout.real(work)
    val opened = synchronized(sessions.values.filter(s => s.live && s.id != id).map(_.state.config.scope).toVector)
    val listed = catalog.rows.filter(row => row.session != id && notEnded(row)).flatMap(_.scope)
    val others = (opened ++ listed).flatMap(folderOf).map(Layout.real)
    scopes.stateOf(scope).copy(
      owned = folder.exists(f => f.startsWith(base) && f.getNameCount > base.getNameCount),
      shared = folder.exists(others.contains)
    )

  /** The ref that keeps a branch of the session `id` (7.9). */
  private def refOf(id: SessionId): String = s"refs/archive/${id.value}"

  /** The folder that gets the scratch directory of the session `id` (7.9). */
  private def archiveOf(id: SessionId): String = root.resolve("archive").resolve(id.value).toString

  /** The open session `id`. Only the laws use it. */
  private[host] def sessionOf(id: SessionId): Option[OpenSession] = synchronized(sessions.get(id))

  /** The files of `sessions` that are not journals, from the rebuild at the start (10.2). */
  def failed: Vector[(Path, String)] = rebuilt.failed

  /** Ends the writer of each open session, closes its activation, stops its jobs and frees its lock. The journals stay.
    */
  def close(): Unit =
    stopReports = true
    reporter.interrupt()
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
    startWith(root, _ => runtime, secrets, limits, clock)

  /** Starts a host at `root` whose runtime comes from `runtimeOf` with the host, so a handler of `Sessions` reaches the
    * other sessions (8.7). The folders of `start` go under `work`, by default the folder `work` beside `root`. It makes
    * the folders, rebuilds the catalog from the files (10.2), and opens each session of `ReportPass.repairable`, so the
    * repair commits `TurnEnded(Crashed)`.
    */
  def startWith(
      root     : Path,
      runtimeOf: Peers => SessionRuntime,
      secrets  : Secrets,
      limits   : HostLimits   = HostLimits(),
      clock    : () => Long   = () => System.currentTimeMillis(),
      scopes   : Scopes       = GitScopes(),
      hooks    : HostHooks    = HostHooks.none,
      work     : Option[Path] = None
  ): Either[HostError, LocalHost] =
    for
      sessions <- Try(JFiles.createDirectories(SessionFile.folderOf(root))).toEither.left
                    .map(e => HostError.OpenFailed(s"the folder of the host did not open: ${e.getMessage}"))
      catalog <- Catalog.open(Catalog.pathOf(root)).left.map(HostError.OpenFailed(_))
      rebuilt <- Catalog.rebuild(catalog, sessions).left.map { e =>
                   catalog.close()
                   HostError.OpenFailed(e)
                 }
    yield
      val host = LocalHost(
        root,
        runtimeOf,
        secrets,
        limits,
        clock,
        catalog,
        Timers(),
        rebuilt,
        scopes,
        hooks,
        work.getOrElse(root.resolveSibling("work"))
      )
      // Each owned session with an open turn opens, so `Activate` commits `TurnEnded(Crashed)` (4.4), and the pass at
      // the open of its owner sends the report. A session that does not open here is repaired at its next open.
      ReportPass.repairable(rebuilt.rows).foreach(id => host.open(id, None): Unit)
      host

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

/** The status of a job in words for a person and for the model, after the name of the job (10.3): `runs`, `exited with
  * the code N`, `stopped`, or `lost with its host`. The message at the end of a job with `report` and the text of
  * `/jobs` give these words. The guest API gives the model the `JobStatus` value, and its source is a reference for the
  * model, so this rule is in the host and not in the guest type.
  */
def jobStatusWords(status: JobStatus): String =
  status match
    case JobStatus.Running      => "runs"
    case JobStatus.Exited(code) => s"exited with the code $code"
    case JobStatus.Stopped      => "stopped"
    case JobStatus.Lost         => "lost with its host"

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

  // The snapshots of the scope after each `TurnEnded` (8.7): one thread of this session runs them in the order of the
  // turns. `turnsEnded` counts the `TurnEnded` of this open, so a snapshot knows when a newer one comes after it.
  private val recorder =
    Executors.newSingleThreadExecutor(Thread.ofVirtual().name(s"harness-scope-${id.value}").factory())
  @volatile private var turnsEnded = 0L

  // The live part. Only code that holds `guard` changes it.
  private var activation: Option[(WorkerTicket, Activation)] = None
  private var openPart: Option[AutoCloseable]                = None // the result of `SessionRuntime.opened`
  private var worker: Option[Long]                           = None
  private var workers                                        = 0L
  private var tickets                                        = 0L
  private var pendingTicket: Option[WorkerTicket]            = None
  private var stopTurn                                       = -1L
  private var row                                            = start.foldLeft(catalogRow(id).initial)(catalogRow(id).step)
  private var putOnce                                        = false
  @volatile private var endedFlag                            = false

  /** True while the session takes commands. */
  def live: Boolean = !endedFlag && !writer.ended && !writer.state.closed

  /** A session whose state is closed gives `Ended` at once: its writer ends right after the commit of `Ended`. */
  def send(command: Command): Either[HostError, Unit] =
    if writer.state.closed then Left(HostError.Ended) else writer.post(command, Source.User)

  def run(command: Command): Either[HostError, SessionState] =
    if writer.state.closed then Left(HostError.Ended) else writer.run(command, Source.User)

  /** `run` with the source `source`, for the peers of the host (8.7). */
  def runAs(command: Command, source: Source): Either[HostError, SessionState] =
    if writer.state.closed then Left(HostError.Ended) else writer.run(command, source)

  /** Waits at most `max` for the end of each job of the session (8.7: the release). */
  def awaitJobs(max: FiniteDuration): Boolean = jobs.awaitAll(max)

  /** `Writer.tryPost` with the source `source`, for the live reports of the host (8.7). */
  def tryPost(command: Command, source: Source): Boolean = !writer.state.closed && writer.tryPost(command, source)

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
      Try(recorder.shutdownNow()): Unit
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
  /** Commits the `Begin` of a new session with its task `task` from `source` (8.7): one commit. */
  private def begin(config: SessionConfig, task: Command.Send, source: Source): Either[HostError, Unit] =
    writer.run(Command.Begin(Harness.versions, config, task.request, task.text), source).map(_ => ())

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
        val added    = putRow()
        val turnEnds = added.exists(_.entry match
          case Entry.TurnEnded(_) => true
          case _                  => false)
        val ends = added.exists(_.entry match
          case Entry.Ended(_, _) => true
          case _                 => false)
        // A report is due after a `TurnEnded` or an `Ended` of a session whose owner is a session (8.6).
        if turnEnds || ends then
          s.config.owner match
            case Owner.Session(owner) => place.report(id, owner)
            case _                    => ()
        if turnEnds && !s.closed then recordScope(s.config.scope)
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

  /** Puts the row of the session into the catalog when its status or its ledger changes, and at the first commit of
    * this open (8.7). It gives the entries that the row did not have.
    */
  private def putRow(): Vector[Logged] =
    val fresh = writer.log.reverseIterator.takeWhile(_.seq > row._1.lastSeq).toVector.reverse
    val next  = fresh.foldLeft(row)(catalogRow(id).step)
    if !putOnce || next._1.status != row._1.status || next._1.ledger != row._1.ledger then
      putOnce = true
      // A failed write of the catalog never ends a session: the next rebuild corrects the row.
      place.catalog.put(next._1): Unit
    row = next
    fresh

  /** Commits `ScopeRecorded` with the snapshot of `scope` after a `TurnEnded` (8.7), on the thread of the snapshots. A
    * snapshot that a newer `TurnEnded` makes old does not commit, and a folder with no git gives none. The `Home` of
    * the controller gets no snapshot: the controller writes nothing, and a walk of the home of the person can be long.
    * Holds `guard`.
    */
  private def recordScope(scope: Scope): Unit =
    scope match
      case Scope.Home(_) => ()
      case _             =>
        turnsEnded += 1
        val turn = turnsEnded
        // After the end of the session, the thread takes no task, and the session needs no snapshot.
        Try(recorder.execute { () =>
          if turn == turnsEnded then
            place.scopes.snapshot(scope).filter(_ => turn == turnsEnded).foreach { now =>
              writer.run(Command.Commit(Vector(Entry.ScopeRecorded(now.head, now.clean))), Source.Harness): Unit
            }
        }): Unit

  /** The message of a job with `report` at its end (10.3), for example `The job 4.0 exited with the code 0.` and the
    * end of its output. The model reads it, so it gives the status in words (`jobStatusWords`) and not as a Scala
    * value. The writer of the session gets it, so it also reaches a later activation.
    */
  private def reportJob(job: EffectId, status: JobStatus): Unit =
    // The cut keeps whole characters, because the journal refuses a surrogate with no pair, and a failed write ends the
    // session.
    val output = jobs.output(job).fold("")(text => textSuffix(text, ReportOutput))
    val text   = s"The job ${jobName(job)} ${jobStatusWords(status)}. The end of its output:\n$output"
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
      timers : Timers,
      scopes : Scopes,
      report : (SessionId, SessionId) => Unit // the put of a live report: the session and its owner (8.7)
  )

  /** The most characters of the output of a job in its message. */
  private val ReportOutput = 2_000

  /** Opens the session `id`: the folder of the scope, the lock, the file, the journal, the configuration, the writer,
    * the call of `SessionRuntime.opened` and the `Activate`. A folder that is not there refuses the open before the
    * lock and the file, so it makes no journal. A failed step after the writer closes the session, and the close also
    * closes the result of `opened`.
    */
  def start(
      id    : SessionId,
      config: Option[SessionConfig],
      place : Place,
      first : Option[(Command.Send, Source)] = None
  ): Either[HostError, OpenSession] =
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
                // `opened` comes before the `Activate` or the `Begin`, because each can start a turn at once, and the
                // activation of that turn needs the result of `opened`.
                _ <- session.keepOpened(setting).left.map { e =>
                       session.close()
                       e
                     }
                _ <- first.fold(session.activate(setting))(session.begin(setting, _, _)).left.map { e =>
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
      case Scope.Worktree(path, _, _, _)       => Some(path)
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
