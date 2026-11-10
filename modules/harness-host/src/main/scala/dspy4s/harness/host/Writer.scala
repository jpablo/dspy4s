package dspy4s.harness.host

import dspy4s.harness.{Journal, Secrets}
import dspy4s.harness.kernel.{
  Command, Entry, Logged, Meta, Position, Rejection, SessionState, Source, Taint, TurnOutcome
}
import dspy4s.harness.kernel.{closing, decide, evolve, needsFlush, sourceOf, state as foldOf}

import java.util.concurrent.{ArrayBlockingQueue, CompletableFuture, ConcurrentHashMap, ExecutionException, TimeUnit}
import scala.util.Try

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
final class Writer(
    file    : SessionFile,
    start   : Vector[Logged],
    clock   : () => Long,
    secrets : Secrets,
    feeds   : Feeds,
    capacity: Int,
    after   : SessionState => Unit
) extends AutoCloseable:
  import Writer.*

  private val mailbox = ArrayBlockingQueue[Message](capacity.max(1))
  private val fenced  = ConcurrentHashMap.newKeySet[WorkerTicket]()

  // Only the writer thread changes these. The other threads read the last committed values.
  @volatile private var current: SessionState   = foldOf(start)
  @volatile private var entries: Vector[Logged] = start
  @volatile private var failure: Option[String] = None
  @volatile private var stopped                 = false
  private var next: Long                        = (file.lastSeq + 1).max(current.nextSeq)

  private val thread = Thread.ofVirtual().name(s"harness-writer-${file.session.value}").start(() => loop())

  /** Puts `command` into the mailbox, and waits for its commit. A command with the ticket of a fenced activation gets
    * `HostError.Fenced`.
    */
  def run(command: Command, sender: Source, ticket: Option[WorkerTicket] = None): Either[HostError, SessionState] =
    val reply = CompletableFuture[Either[HostError, SessionState]]()
    if !enqueue(Run(command, sender, ticket, reply)) then Left(endError)
    else awaitUninterruptibly(reply)

  /** Puts `command` into the mailbox, and returns before its commit (10.1: `send`). */
  def post(command: Command, sender: Source): Either[HostError, Unit] =
    Either.cond(enqueue(Run(command, sender, None, CompletableFuture())), (), endError)

  /** Refuses each later command of the activation `ticket` (10.3, step 5). */
  def fence(ticket: WorkerTicket): Unit = fenced.add(ticket): Unit

  /** True after `fence(ticket)`, so the host knows that the activation of `ticket` is not safe. */
  private[host] def isFenced(ticket: WorkerTicket): Boolean = fenced.contains(ticket)

  /** The end of the grace period of a stop (10.3, step 5). It puts a message, and returns at once. The writer handles
    * it on its own thread: when the turn whose `TurnStarted` has the `seq` `turn` is still open and stopping, it fences
    * `ticket` and commits the closing entries and `TurnEnded(Aborted)`. When that turn ended, it does nothing, so the
    * timer of a stop never touches a later turn.
    */
  def expire(turn: Long, ticket: WorkerTicket): Unit = enqueue(Expire(turn, ticket)): Unit

  /** The committed state. */
  def state: SessionState = current

  /** The committed entries. */
  def log: Vector[Logged] = entries

  /** True after the end of the session or after a failed write. */
  def ended: Boolean = stopped

  /** The end of the writer: no message after it commits, the feeds end, and the file closes. */
  def close(): Unit =
    stopped = true
    if Thread.currentThread() ne thread then thread.join(10_000)
    feeds.close()
    Try(file.close()): Unit

  private def endError: HostError = failure.fold(HostError.Ended)(HostError.WriteFailed(_))

  /** Puts `message` into the mailbox, and waits while the mailbox is full. False when the writer ended, also after the
    * put: then the message is not in the mailbox, or the last drain fails it.
    */
  private def enqueue(message: Message): Boolean =
    if stopped then false
    else
      uninterruptibly(mailbox.put(message))
      if stopped && mailbox.remove(message) then false else true

  private def loop(): Unit =
    try
      while !stopped do
        val message = mailbox.poll(50, TimeUnit.MILLISECONDS)
        if message != null then handle(message)
    finally
      var left = mailbox.poll()
      while left != null do
        left match
          case Run(_, _, _, reply) => reply.complete(Left(endError)): Unit
          case Expire(_, _)        => ()
        left = mailbox.poll()

  private def handle(message: Message): Unit =
    message match
      case Run(command, sender, ticket, reply) =>
        val result =
          if ticket.exists(fenced.contains) then Left(HostError.Fenced)
          else commit(command, sender)
        reply.complete(result): Unit
        result.foreach(s => Try(after(s)): Unit)
      case Expire(turn, ticket) =>
        val s = current
        if s.lastTurn == turn && s.position != Position.NoTurn && s.stopping then
          fence(ticket)
          commit(Command.Commit(closing(s) :+ Entry.TurnEnded(TurnOutcome.Aborted)), Source.Harness)
            .foreach(closed => Try(after(closed)): Unit)

  /** One commit: `decide`, the rows with their `seq`, meta and mask, one transaction, then the feeds. */
  private def commit(command: Command, sender: Source): Either[HostError, SessionState] =
    if stopped then Left(endError)
    else
      decide(command, current).left.map(HostError.Rejected(_)).flatMap { added =>
        if added.isEmpty then Right(current)
        else
          var s    = current
          val rows = added.zipWithIndex.map { (entry, i) =>
            val meta   = Meta(clock(), sourceOf(command, sender, entry), Taint.Trusted, required = true)
            val logged = Logged(next + i, meta, secrets.mask(entry))
            s = evolve(s, logged)
            logged
          }
          file.append(rows, flush = added.exists(needsFlush)) match
            case Left(error) =>
              failure = Some(error)
              stopped = true
              feeds.close()
              Left(HostError.WriteFailed(error))
            case Right(()) =>
              next += rows.size
              entries = entries ++ rows
              current = s
              feeds.publish(rows)
              Right(s)
      }

object Writer:

  private sealed trait Message

  private final case class Run(
      command: Command,
      sender : Source,
      ticket : Option[WorkerTicket],
      reply  : CompletableFuture[Either[HostError, SessionState]]
  ) extends Message

  private final case class Expire(turn: Long, ticket: WorkerTicket) extends Message

  /** Runs `body` again after each interrupt, and sets the interrupt flag again at the end. */
  private def uninterruptibly[A](body: => A): A =
    var interrupted = false
    var result      = Option.empty[A]
    while result.isEmpty do
      try result = Some(body)
      catch case _: InterruptedException => interrupted = true
    if interrupted then Thread.currentThread().interrupt()
    result.getOrElse(throw IllegalStateException("no result"))

  /** The value of `reply`, with the waits of `uninterruptibly`. */
  private def awaitUninterruptibly[A](reply: CompletableFuture[A]): A =
    try uninterruptibly(reply.get())
    catch case e: ExecutionException => throw e.getCause

/** The journal of one activation (4.6, 10.2): each commit of its worker, its effects and its recorder goes through the
  * mailbox of `writer` with the ticket of the activation.
  *
  * A proposal for the review: `Journal.run` gives a `Rejection`, and a write error is not one. So `HostError.Rejected`
  * gives its rejection, and the other errors throw an exception with a plain message. `drive` then stops, and the turn
  * stays open, as after a crash. The next `Activate` repairs it (4.4).
  */
final class WriterJournal(writer: Writer, ticket: WorkerTicket) extends Journal:

  def state: SessionState = writer.state

  def log: Vector[Logged] = writer.log

  def run(command: Command, sender: Source): Either[Rejection, SessionState] =
    writer.run(command, sender, Some(ticket)) match
      case Right(s)                         => Right(s)
      case Left(HostError.Rejected(reason)) => Left(reason)
      case Left(other)                      =>
        throw IllegalStateException(s"the journal of the session took no commit of this activation: $other")
