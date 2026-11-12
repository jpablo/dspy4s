package dspy4s.harness.host

import scala.concurrent.duration.FiniteDuration

/** The waits of the host that an interrupt does not stop. A wait catches each `InterruptedException` and waits again,
  * and at the end it sets the interrupt flag of the thread again. So the caller keeps its interrupt, and the steps
  * after the wait still run. Use these waits only for a wait that is short or that has a limit.
  */
private[host] object Interrupts:

  /** Runs `body` again after each interrupt, and sets the interrupt flag again at the end, also when `body` throws.
    * `body` must be safe to run again after an `InterruptedException`, for example `Future.get` or `BlockingQueue.put`.
    */
  def uninterruptibly[A](body: => A): A =
    var interrupted = false
    try
      var result = Option.empty[A]
      while result.isEmpty do
        try result = Some(body)
        catch case _: InterruptedException => interrupted = true
      result.getOrElse(throw IllegalStateException("no result"))
    finally if interrupted then Thread.currentThread().interrupt()

  /** Gives `wait` the nanoseconds that are left of `max`, again after each interrupt, until `wait` returns or `max`
    * passes. An interrupt does not make the wait shorter. At the end, it sets the interrupt flag again.
    */
  def uninterruptiblyWithin(max: FiniteDuration)(wait: Long => Unit): Unit =
    val end         = System.nanoTime() + max.toNanos
    var interrupted = false
    try
      var done = false
      while !done do
        val left = end - System.nanoTime()
        if left <= 0L then done = true
        else
          try
            wait(left)
            done = true
          catch case _: InterruptedException => interrupted = true
    finally if interrupted then Thread.currentThread().interrupt()
