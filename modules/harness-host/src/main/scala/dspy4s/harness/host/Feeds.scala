package dspy4s.harness.host

import dspy4s.harness.kernel.Logged

/** A client lost `lost` entries, because its queue was full (CODE_HARNESS_DESIGN.md, 10.2). The client subscribes
  * again, reads the journal after its last `seq`, and skips each entry with a `seq` that it has. So it misses nothing.
  */
final case class Behind(lost: Int) derives CanEqual

/** The committed entries of one session for one client (10.1). */
trait Feed extends AutoCloseable:

  /** The next entry, or `Behind` after a full queue, or none when no entry comes in `waitMillis` milliseconds. */
  def poll(waitMillis: Long = 0): Option[Either[Behind, Logged]]

  def close(): Unit

/** The feeds of one session (10.2). Only the writer calls `publish`, and only after a commit, so a client never sees an
  * entry that is not in the file. The writer never waits for a client: when the queue of a client is full, `publish`
  * empties that queue, and the next `poll` of that client gives `Behind`.
  */
final class Feeds:

  /** A new client with a queue of `capacity` entries. It gets the entries of the commits after this call. */
  def subscribe(capacity: Int): Feed = ???

  /** Gives the entries of one commit to each client, in the order of `seq`. */
  def publish(entries: Vector[Logged]): Unit = ???

  /** Ends each client: a later `poll` gives none. */
  def close(): Unit = ???
