package dspy4s.harness

import dspy4s.core.contracts.{RuntimeContext, RuntimeDelta}
import dspy4s.core.runtime.{ContextPropagation, RuntimeEnvironment}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{Callable, ConcurrentLinkedQueue, ExecutionException, Executors, ThreadFactory, TimeUnit}
import scala.concurrent.duration.*

/** The budget of model calls of one program call (CODE_HARNESS_DESIGN.md, 8.2: "`rlm` shares the budget of the
  * parent"). `forward` makes one budget, and each session of the tree of the call takes from it: each policy call of
  * the call and of each child, and each query. The finisher does not take from it. The calls of a batch run on several
  * threads, so `spend` is one atomic step.
  *
  * The budget is also the cancel of the tree (9). The end of the top call closes it. After `close`, `spend` and `check`
  * fail with a plain error, and the recorder of each session of the tree refuses each guest API call. So a child that
  * outlives its parent cannot make a model call, write or exec.
  *
  * A proposal for the review: the design gives no number. `HarnessLimits.modelCalls` is 60.
  */
final class Budget(val limit: Int):
  private val used  = AtomicInteger(0)
  private val ended = AtomicBoolean(false)

  /** Spends one call. A spent or closed budget gives a plain error, and the count does not change. */
  def spend(): Either[String, Unit] =
    if closed then Left(Budget.Closed)
    else
      val before = used.getAndUpdate(n => if n < limit then n + 1 else n)
      Either.cond(before < limit, (), spentError)

  /** The error of `spend` when no call is left, with no spend. A child checks it before it starts (8.2). */
  def check(): Either[String, Unit] =
    if closed then Left(Budget.Closed) else Either.cond(used.get() < limit, (), spentError)

  /** The calls that the tree spent. */
  def spent: Int = used.get()

  /** Ends the program call of the budget. Each later `spend` and `check` fails. */
  def close(): Unit = ended.set(true)

  /** True after `close`. */
  def closed: Boolean = ended.get()

  private def spentError: String = s"the budget of $limit model calls of this program call is spent"

object Budget:

  /** The error of a closed budget. */
  private val Closed = "the program call of this budget ended, so its tree can make no more calls"

/** The work of a program call on a thread that is not the thread of the call (6, 9). A query runs on the thread of its
  * cell, a child runs on the thread of the cell of its parent, and a batch runs on host threads. `run` gives its body
  * the context of the program call: its services, its scope and the carriers of `ContextPropagation`. The trace and the
  * history of the body go into a queue. `join` adds them to the trace of the thread that calls it, so an optimizer sees
  * each call of a leaf, also of the `subQuery` leaf and of the policy of a child.
  *
  * A proposal for the review: the design does not say how the trace of a call on another thread reaches the program
  * call. Only the top session joins the queue, after each cell, as `ContextPropagation.futureExecuted` gives the trace
  * of a worker to its caller. A child does not join, so it does not take the trace of a sibling that ended before it.
  * The queue holds the trace of the whole tree in the order of the ends, and `join` sorts it by the time of each entry.
  */
final class HostThreads(captured: ContextPropagation.Captured):
  private val deltas = ConcurrentLinkedQueue[RuntimeDelta]()

  /** Runs `body` with the context of the program call, and keeps its trace and its history for `join`, also when it
    * throws.
    */
  def run[A](body: RuntimeContext ?=> A): A =
    captured.run {
      RuntimeEnvironment.withContext(RuntimeEnvironment.current.withDelta(RuntimeDelta.empty)) {
        try body(using RuntimeEnvironment.current)
        finally deltas.add(RuntimeEnvironment.current.delta): Unit
      }
    }

  /** Adds the kept trace and history to the context of this thread, in the order of their timestamps. The children of a
    * batch end in another order than they run, so the order of the ends is not the order of the steps.
    */
  def join(): Unit =
    var kept  = RuntimeDelta.empty
    var delta = deltas.poll()
    while delta != null do
      kept = kept.combine(delta)
      delta = deltas.poll()
    RuntimeEnvironment.propagate(RuntimeDelta(kept.trace.sortBy(_.timestamp), kept.history.sortBy(_.timestamp)))

object HostThreads:

  /** The host threads of the program call that runs on this thread. */
  def ofThisThread(): HostThreads = HostThreads(ContextPropagation.captureAll)

/** The longest wait of `inBatch` for its items after an early end. It is shorter than the wait of the REPL for a cell
  * that it stops (5 seconds), so the cell of a batch ends inside that wait.
  */
private[harness] val BatchStop: FiniteDuration = 3.seconds

/** Runs `task` for each item, at most `width` items at the same time, each on a host thread (6: "the host runs its work
  * at the same time, with a limit"). Safe mode rejects a thread in a cell, and the host can start one. The result keeps
  * the order of the items: all the values, or the first failure in that order with the index of its item. After a
  * failure, an item that did not start does not run. Each item that started ends before the result.
  *
  * The batch ends early after an interrupt of its thread, for example when the REPL stops the cell (9), or after an
  * `Error` of an item. Then it interrupts its items, waits for them at most `BatchStop`, and the exception goes on. So
  * an item that stops ends before the cell, and an item that does not stop cannot hold the cell with no limit.
  *
  * A proposal for the review: a batch does not stop an item that runs after a failure of another item, because a query
  * or a child has its own limits.
  */
def inBatch[A, B](items: Vector[A], width: Int)(task: A => B): Either[(Int, Exception), Vector[B]] =
  if items.isEmpty then Right(Vector.empty)
  else
    val failed                 = AtomicBoolean(false)
    val threads: ThreadFactory = body =>
      val thread = Thread(body, "harness-batch")
      thread.setDaemon(true)
      thread
    val pool = Executors.newFixedThreadPool(width.max(1).min(items.size), threads)
    try
      val futures = items.map { item =>
        val job: Callable[Option[Either[Exception, B]]] = () =>
          if failed.get() then None
          else
            try Some(Right(task(item)))
            catch
              case e: Exception =>
                failed.set(true)
                Some(Left(e))
        pool.submit(job)
      }
      val results =
        try futures.map(_.get())
        catch
          case early @ (_: InterruptedException | _: ExecutionException) =>
            pool.shutdownNow(): Unit
            pool.awaitTermination(BatchStop.toMillis, TimeUnit.MILLISECONDS): Unit
            throw early
      results.zipWithIndex.collectFirst { case (Some(Left(error)), i) => (i, error) }
        .toLeft(results.collect { case Some(Right(value)) => value })
    finally pool.shutdownNow(): Unit
