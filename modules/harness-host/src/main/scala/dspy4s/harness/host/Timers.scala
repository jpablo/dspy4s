package dspy4s.harness.host

import scala.concurrent.duration.FiniteDuration

/** Cancels a timer. A cancel after the run, or a second cancel, does nothing. */
trait Cancel:
  def cancel(): Unit

/** The timers of a host (CODE_HARNESS_DESIGN.md, 10.2). A timer only puts a message in the mailbox of a session, so the
  * writer stays the one place that commits. A stop starts a timer for the grace period of 10.3.
  */
final class Timers extends AutoCloseable:

  /** Runs `put` once after `delay`, on a thread of the timers. `put` must only put a message in a mailbox. */
  def after(delay: FiniteDuration)(put: () => Unit): Cancel = ???

  /** Cancels each timer that did not run. */
  def close(): Unit = ???
