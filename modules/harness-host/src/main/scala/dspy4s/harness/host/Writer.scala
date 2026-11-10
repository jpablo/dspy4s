package dspy4s.harness.host

import dspy4s.harness.{Journal, Secrets}
import dspy4s.harness.kernel.{Command, Logged, Rejection, SessionState, Source}
import scala.annotation.nowarn

/** The one writer of a session (CODE_HARNESS_DESIGN.md, 10.2). One virtual thread takes the messages of a bounded
  * mailbox of `capacity` messages, and it is the only code that commits to `file`.
  *
  *   - A message is a command, its sender, a ticket or none, and a `CompletableFuture`. The writer runs `decide`, gives
  *     each entry the next `seq`, the time of `clock`, its source from `sourceOf` and the mask of `secrets` (5.6), and
  *     appends the entries of the command in one transaction. A commit that holds an entry that `needsFlush` reaches
  *     the disk before the future completes (4.1).
  *   - The first new entry gets the `seq` after `file.lastSeq`, so a row that a reader skipped keeps its `seq`.
  *   - After the commit, the writer publishes the entries to `feeds`, completes the future, and calls `after` with the
  *     new state. The host uses `after` to start a worker, to update the catalog and to copy the marks of the jobs.
  *     `after` must return at once, because the writer waits for it.
  *   - A thread that waits for its future and gets an interrupt waits again, because the commit stands. Then it sets
  *     its interrupt flag again. So an interrupt of a cell thread reaches only its own wait (10.2).
  *   - A failed write ends this session only: the writer ends, the feeds end, and each caller that waits gets
  *     `HostError.WriteFailed`.
  *   - The end of a session (`close`) sets a flag, and then fails each message in the mailbox with `HostError.Ended`. A
  *     caller checks the flag after its put, so a message after the last drain also fails, and no call waits for ever.
  *
  * `start` is the journal in the file at the open, and the state is its fold.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class Writer(
    file    : SessionFile,
    start   : Vector[Logged],
    clock   : () => Long,
    secrets : Secrets,
    feeds   : Feeds,
    capacity: Int,
    after   : SessionState => Unit
) extends AutoCloseable:

  /** Puts `command` into the mailbox, and waits for its commit. A command with the ticket of a fenced activation gets
    * `HostError.Fenced`.
    */
  def run(command: Command, sender: Source, ticket: Option[WorkerTicket] = None): Either[HostError, SessionState] = ???

  /** Puts `command` into the mailbox, and returns before its commit (10.1: `send`). */
  def post(command: Command, sender: Source): Either[HostError, Unit] = ???

  /** Refuses each later command of the activation `ticket` (10.3, step 5). */
  def fence(ticket: WorkerTicket): Unit = ???

  /** The end of the grace period of a stop (10.3, step 5). It puts a message, and returns at once. The writer handles
    * it on its own thread: when the turn whose `TurnStarted` has the `seq` `turn` is still open and stopping, it fences
    * `ticket` and commits the closing entries and `TurnEnded(Aborted)`. When that turn ended, it does nothing, so the
    * timer of a stop never touches a later turn.
    */
  def expire(turn: Long, ticket: WorkerTicket): Unit = ???

  /** The committed state. */
  def state: SessionState = ???

  /** The committed entries. */
  def log: Vector[Logged] = ???

  /** True after the end of the session or after a failed write. */
  def ended: Boolean = ???

  /** The end of the writer: no message after it commits, the feeds end, and the file closes. */
  def close(): Unit = ???

/** The journal of one activation (4.6, 10.2): each commit of its worker, its effects and its recorder goes through the
  * mailbox of `writer` with the ticket of the activation.
  *
  * A proposal for the review: `Journal.run` gives a `Rejection`, and a write error is not one. So `HostError.Rejected`
  * gives its rejection, and the other errors throw an exception with a plain message. `drive` then stops, and the turn
  * stays open, as after a crash. The next `Activate` repairs it (4.4).
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class WriterJournal(writer: Writer, ticket: WorkerTicket) extends Journal:

  def state: SessionState = ???

  def log: Vector[Logged] = ???

  def run(command: Command, sender: Source): Either[Rejection, SessionState] = ???
