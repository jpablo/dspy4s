package dspy4s.harness

import dspy4s.core.contracts.{RuntimeContext, RuntimeDelta}
import dspy4s.core.runtime.{ContextPropagation, RuntimeEnvironment}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{Callable, ConcurrentLinkedQueue, Executors, ThreadFactory}

/** The budget of model calls of one program call (CODE_HARNESS_DESIGN.md, 8.2: "`rlm` shares the budget of the
  * parent"). `forward` makes one budget, and each session of the tree of the call takes from it: each policy call of
  * the call and of each child, and each query. The finisher does not take from it. The calls of a batch run on several
  * threads, so `spend` is one atomic step.
  *
  * A proposal for the review: the design gives no number. `HarnessLimits.modelCalls` is 60.
  */
final class Budget(val limit: Int):
  private val used = AtomicInteger(0)

  /** Spends one call. A spent budget gives a plain error, and the count does not change. */
  def spend(): Either[String, Unit] =
    val before = used.getAndUpdate(n => if n < limit then n + 1 else n)
    Either.cond(before < limit, (), s"the budget of $limit model calls of this program call is spent")

  /** The calls that the tree spent. */
  def spent: Int = used.get()

/** The work of a program call on a thread that is not the thread of the call (6, 9). A query runs on the thread of its
  * cell, a child runs on the thread of the cell of its parent, and a batch runs on host threads. `run` gives its body
  * the context of the program call: its services, its scope and the carriers of `ContextPropagation`. The trace and the
  * history of the body go into a queue. `join` adds them to the trace of the thread that calls it, so an optimizer sees
  * each call of a leaf, also of the `subQuery` leaf and of the policy of a child.
  *
  * A proposal for the review: the design does not say how the trace of a call on another thread reaches the program
  * call. `HarnessEffects` joins the queue after each cell, as `ContextPropagation.futureExecuted` gives the trace of a
  * worker to its caller. A child joins into the trace of its own `run`, so the trace comes up the tree to the thread of
  * the program call.
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

  /** Adds the kept trace and history to the context of this thread, in the order of their ends. */
  def join(): Unit =
    var delta = deltas.poll()
    while delta != null do
      RuntimeEnvironment.propagate(delta)
      delta = deltas.poll()

object HostThreads:

  /** The host threads of the program call that runs on this thread. */
  def ofThisThread(): HostThreads = HostThreads(ContextPropagation.captureAll)

/** Runs `task` for each item, at most `width` items at the same time, each on a host thread (6: "the host runs its work
  * at the same time, with a limit"). Safe mode rejects a thread in a cell, and the host can start one. The result keeps
  * the order of the items: all the values, or the first failure in that order with the index of its item. After a
  * failure, an item that did not start does not run. Each item that started ends before the result.
  *
  * A proposal for the review: a batch does not stop an item that runs, because a query or a child has its own limits.
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
      val results = futures.map(_.get())
      results.zipWithIndex.collectFirst { case (Some(Left(error)), i) => (i, error) }
        .toLeft(results.collect { case Some(Right(value)) => value })
    finally pool.shutdownNow(): Unit
