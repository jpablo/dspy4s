package dspy4s.harness.host

import dspy4s.harness.{Journal, Secrets, TurnEffects}
import dspy4s.harness.kernel.{Command, Logged, SessionConfig, SessionId, SessionState}

import java.nio.file.Path
import scala.annotation.nowarn

/** A host of sessions (CODE_HARNESS_DESIGN.md, 10.1). It is free of the types of a runtime, so a Kyo, ZIO or Pekko host
  * stays possible.
  */
trait SessionHost:

  /** Opens the session `id` with `config`: a new session, or a session with a journal. */
  def open(id: SessionId, config: SessionConfig): Either[HostError, SessionHandle]

/** An open session for a client (10.1).
  *
  * A proposal for the review: section 10.1 names this trait `SessionRef`. The guest API already has the data type
  * `SessionRef`, which the store can hold, so the host trait is `SessionHandle`.
  */
trait SessionHandle:
  def id: SessionId

  /** Puts `command` into the mailbox, with the source `User`. It does not wait for the commit. */
  def send(command: Command): Either[HostError, Unit]

  /** The committed state. */
  def state: SessionState

  /** A feed of the entries of the commits after this call (10.2). */
  def subscribe(capacity: Int): Feed

  /** The committed entries with a `seq` above `seq`. A client reads them after `Behind`. */
  def read(after: Long): Vector[Logged]

/** What the host needs to run the turns of a session (10.1). It makes the live part of each activation. */
trait SessionRuntime:

  /** The live part of a new activation of `session` (11: Live): a new REPL, the handlers of the mode of `config` and
    * the effects of a turn over `journal`. `jobs` is the job table of the session, which lives as long as the session
    * is open in this host.
    */
  def activate(session: SessionId, config: SessionConfig, journal: Journal, jobs: JobTable): Activation

/** The live part of one activation (11). Nothing outside it depends on it, and a new activation makes a new one. */
trait Activation extends AutoCloseable:

  /** The effects of a turn (4.6). */
  def effects: TurnEffects

  /** Steps 2 and 3 of the path of a hard stop (10.3): it interrupts the cell thread, and stops the LM transport and the
    * process groups of the running `exec` calls with TERM, a grace period, then KILL. It returns at once. The worker
    * thread gets no interrupt, so its commits complete.
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
  *     journal, and `Activate` repairs an open turn with `TurnEnded(Crashed)` (4.4). A second `open` of an open session
  *     gives the same handle.
  *   - After each commit, when `next` gives work and no worker runs, the host starts a worker thread with a new ticket.
  *     The worker runs `drive` with a `WriterJournal` and the effects of the activation.
  *   - After `StopRequested`, the host calls `Activation.stop`, and a timer puts a message after `limits.grace`. When
  *     the worker still runs at that message, the writer fences its ticket, and commits the closing entries and
  *     `TurnEnded(Aborted)` (10.3, step 5). The next turn gets a new activation.
  *   - After each commit, the host copies the marks of the jobs of the state to the job table (10.3).
  *   - After a turn ends or the status changes, the host puts the row of the session into the catalog.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class LocalHost private (
    root   : Path,
    runtime: SessionRuntime,
    secrets: Secrets,
    limits : HostLimits,
    clock  : () => Long,
    catalog: Catalog,
    timers : Timers
) extends SessionHost,
      AutoCloseable:

  def open(id: SessionId, config: SessionConfig): Either[HostError, SessionHandle] = ???

  /** The rows of the catalog. */
  def list: Vector[CatalogRow] = ???

  /** Ends the writer of each open session and closes its activation. The journals stay. */
  def close(): Unit = ???

object LocalHost:

  /** Starts a host at `root`: it makes the folders, and it rebuilds the catalog from the files (10.2). */
  def start(
      root   : Path,
      runtime: SessionRuntime,
      secrets: Secrets,
      limits : HostLimits = HostLimits(),
      clock  : () => Long = () => System.currentTimeMillis()
  ): Either[HostError, LocalHost] = ???
