package dspy4s.core.runtime

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Test helpers for code that can block. A test that uses them fails, and does not block the suite, when the code under
  * test hangs.
  */
object BoundedWait:

  /** The result of `body`, run on a daemon thread, or `None` when `body` does not end in `seconds`. An exception of
    * `body` goes to the caller.
    */
  def within[A](seconds: Long)(body: => A): Option[A] =
    val result = new CompletableFuture[A]()
    val thread = new Thread(
      () =>
        try
          val _ = result.complete(body)
        catch
          case error: Throwable => val _ = result.completeExceptionally(error)
      ,
      "dspy4s-bounded-wait"
    )
    thread.setDaemon(true)
    thread.start()
    try Some(result.get(seconds, TimeUnit.SECONDS))
    catch
      case _: TimeoutException       => None
      case error: ExecutionException => throw error.getCause

  /** True when the process `pid` does not run, or stops in `seconds`. */
  def processEnds(pid: Long, seconds: Long): Boolean =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
    def alive    = ProcessHandle.of(pid).map(_.isAlive).orElse(false)
    while alive && System.nanoTime() < deadline do Thread.sleep(50)
    !alive

  /** Kill the process `pid` when it runs. A test calls it at the end, so a failed test leaves no process. */
  def killProcess(pid: Long): Unit =
    ProcessHandle.of(pid).ifPresent(handle =>
      val _ = handle.destroyForcibly()
    )
