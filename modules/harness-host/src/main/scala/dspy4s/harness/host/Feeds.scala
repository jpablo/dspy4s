package dspy4s.harness.host

import dspy4s.harness.kernel.Logged

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable.ArrayDeque

/** A client lost `lost` entries, because its queue was full (CODE_HARNESS_DESIGN.md, 10.2). The client subscribes
  * again, reads the journal after its last `seq`, and skips each entry with a `seq` that it has. So it misses nothing.
  */
final case class Behind(lost: Int) derives CanEqual

/** The committed entries of one session for one client (10.1). */
trait Feed extends AutoCloseable:

  /** The next entry, or `Behind` after a full queue, or none when no entry comes in `waitMillis` milliseconds. */
  def poll(waitMillis: Long = 0): Option[Either[Behind, Logged]]

  /** True after the end of the feeds, when the queue is empty: the session ended or its writer failed, so no entry
    * comes. A client then reads the state of the session, and it ends.
    */
  def ended: Boolean

  def close(): Unit

/** The feeds of one session (10.2). Only the writer calls `publish`, and only after a commit, so a client never sees an
  * entry that is not in the file. The writer never waits for a client: when the queue of a client is full, `publish`
  * empties that queue, and the next `poll` of that client gives `Behind`.
  *
  * After a full queue, each later entry is lost too until the client takes its `Behind`. So `Behind` always comes
  * before an entry that is newer than the lost ones, and the read of the journal after the last `seq` of the client
  * finds each lost entry.
  */
final class Feeds:
  private val clients          = CopyOnWriteArrayList[Client]()
  @volatile private var closed = false

  /** A new client with a queue of `capacity` entries. It gets the entries of the commits after this call. */
  def subscribe(capacity: Int): Feed =
    val client = Client(capacity.max(1), c => clients.remove(c): Unit)
    clients.add(client): Unit
    if closed then client.finish()
    client

  /** Gives the entries of one commit to each client, in the order of `seq`. */
  def publish(entries: Vector[Logged]): Unit = if entries.nonEmpty then clients.forEach(_.offer(entries))

  /** Ends each client: a later `poll` gives the entries of its queue, then none. */
  def close(): Unit =
    closed = true
    clients.forEach(_.finish())

/** One client of the feeds. One lock guards the queue, the count of lost entries and the end. */
private final class Client(capacity: Int, unsubscribe: Client => Unit) extends Feed:
  private val lock     = ReentrantLock()
  private val ready    = lock.newCondition()
  private val queue    = ArrayDeque.empty[Logged]
  private var lost     = 0
  private var finished = false

  private def locked[A](body: => A): A =
    lock.lock()
    try body
    finally lock.unlock()

  def offer(entries: Vector[Logged]): Unit =
    locked {
      entries.foreach { entry =>
        if lost > 0 then lost += 1
        else if queue.size >= capacity then
          lost = queue.size + 1
          queue.clear()
        else queue.append(entry): Unit
      }
      ready.signalAll()
    }

  def finish(): Unit =
    locked {
      finished = true
      ready.signalAll()
    }

  def poll(waitMillis: Long = 0): Option[Either[Behind, Logged]] =
    locked {
      var nanos = waitMillis.max(0L) * 1_000_000L
      while queue.isEmpty && lost == 0 && !finished && nanos > 0 do nanos = ready.awaitNanos(nanos)
      if lost > 0 then
        val n = lost
        lost = 0
        Some(Left(Behind(n)))
      else Option.when(queue.nonEmpty)(Right(queue.removeHead()))
    }

  def ended: Boolean = locked(finished && queue.isEmpty && lost == 0)

  def close(): Unit =
    locked {
      queue.clear()
      lost = 0
      finished = true
    }
    unsubscribe(this)
