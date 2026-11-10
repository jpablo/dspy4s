package dspy4s.harness.host

import java.util.concurrent.{Executors, RejectedExecutionException, ScheduledExecutorService, TimeUnit}
import scala.concurrent.duration.FiniteDuration

/** Cancels a timer. A cancel after the run, or a second cancel, does nothing. */
trait Cancel:
  def cancel(): Unit

/** The timers of a host (CODE_HARNESS_DESIGN.md, 10.2). A timer only puts a message in the mailbox of a session, so the
  * writer stays the one place that commits. A stop starts a timer for the grace period of 10.3.
  */
final class Timers extends AutoCloseable:
  private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { body =>
    val thread = Thread(body, "harness-timers")
    thread.setDaemon(true)
    thread
  }

  /** Runs `put` once after `delay`, on a thread of the timers. `put` must only put a message in a mailbox. After
    * `close`, the timer does not run.
    */
  def after(delay: FiniteDuration)(put: () => Unit): Cancel =
    try
      val task: Runnable = () => put()
      val future         = executor.schedule(task, delay.toMillis.max(0L), TimeUnit.MILLISECONDS)
      () => future.cancel(false): Unit
    catch case _: RejectedExecutionException => () => ()

  /** Cancels each timer that did not run. */
  def close(): Unit = executor.shutdownNow(): Unit
