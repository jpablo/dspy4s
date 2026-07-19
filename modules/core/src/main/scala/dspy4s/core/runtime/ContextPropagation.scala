package dspy4s.core.runtime

import dspy4s.core.contracts.Executed
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeDelta

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Carries the active [[RuntimeContext]] across thread boundaries.
  *
  * '''Why this is needed.''' [[RuntimeEnvironment]] keeps the active context -- the configured LM / adapter /
  * callbacks plus the accumulated trace, history, and call stack -- in a `ThreadLocal`. Thread-locals do not
  * follow work that hops to another thread, so when a program runs on a pool thread (a `Future`, a parallel
  * executor worker, a streaming consumer thread) that worker starts with a fresh, empty context. Without
  * propagation, a `Predict.run` executed there would find no LM configured and fire no callbacks.
  *
  * This object closes that gap: [[capture]] snapshots the submitting thread's context, and the run helpers
  * re-install it on the worker for the duration of the task, so dependencies and dynamic scope behave as if the
  * work had run inline. Worker mutations remain isolated when its thread-local is restored; [[futureExecuted]]
  * returns trace/history explicitly for callers that need to join that output. It is dspy4s's equivalent of
  * DSPy's context propagation into parallel and async execution.
  *
  * Scope: the [[RuntimeContext]] plus every registered [[Carrier]] travels (e.g. the usage-tracker stack
  * registered by the lm module). The [[ActivePredictContext]] stack lives in a separate thread-local and is
  * not copied here.
  */
object ContextPropagation:

  /** A module-owned piece of thread-local state that must accompany the [[RuntimeContext]] whenever work hops
    * threads. Modules register one via [[registerCarrier]]; every capture point then snapshots it on the
    * submitting thread and re-installs it on the worker for the duration of the task. */
  trait Carrier:
    /** Snapshot the calling thread's state. */
    def capture(): Snapshot

  trait Snapshot:
    /** Run `thunk` with this snapshot installed on the current thread, restoring the previous state on exit. */
    def restore[A](thunk: => A): A

  private val carriers = new java.util.concurrent.CopyOnWriteArrayList[Carrier]()

  /** Register a [[Carrier]] for the lifetime of the process. Idempotence is the caller's concern (register
    * from an `object` initializer so it runs once). */
  def registerCarrier(carrier: Carrier): Unit =
    val _ = carriers.add(carrier)

  /** Everything that must travel to a worker thread: the [[RuntimeContext]] plus a snapshot of every
    * registered carrier. */
  final class Captured private[ContextPropagation] (
      val context: RuntimeContext,
      snapshots: Vector[Snapshot]
  ):
    /** Run `thunk` with the captured context and every carrier snapshot installed, restoring on exit. */
    def run[A](thunk: => A): A =
      def installAll(remaining: List[Snapshot]): A = remaining match
        case Nil          => thunk
        case head :: tail => head.restore(installAll(tail))
      RuntimeEnvironment.withContext(context)(installAll(snapshots.toList))

  private def captureSnapshots(): Vector[Snapshot] =
    val out = Vector.newBuilder[Snapshot]
    carriers.forEach(carrier => out += carrier.capture())
    out.result()

  /** Snapshot the calling thread's active context, to be replayed on a worker thread. Prefer [[captureAll]] —
    * this legacy form carries only the [[RuntimeContext]], not registered carriers. */
  def capture: RuntimeContext = RuntimeEnvironment.current

  /** Snapshot the calling thread's active context AND every registered carrier's state. */
  def captureAll: Captured = new Captured(RuntimeEnvironment.current, captureSnapshots())

  /** Run `thunk` with `context` installed as the active context, restoring the previous one on exit. Used to
    * replay a captured context on a thread that didn't produce it (e.g. a streaming consumer thread). */
  def inContext[A](context: RuntimeContext)(thunk: => A): A =
    RuntimeEnvironment.withContext(context)(thunk)

  /** Wrap `base` so every `Runnable` it runs executes under `captured` (plus the carrier snapshots taken at
   * wrap time) and a fresh generated async-task id. `Future`s submitted to the returned `ExecutionContext`
   * therefore inherit the captured configuration and accumulated state; the per-task id makes each future a
   * distinct async task for [[RuntimeEnvironment.configure]] ownership purposes. */
  def wrapExecutionContext(base: ExecutionContext, captured: RuntimeContext = capture): ExecutionContext =
    val capturedAll = new Captured(captured, captureSnapshots())
    new ExecutionContext:
      override def execute(runnable: Runnable): Unit =
        base.execute(() => capturedAll.run {
          RuntimeEnvironment.withGeneratedAsyncTask("future") {
            runnable.run()
          }
        }
        )

      override def reportFailure(cause: Throwable): Unit =
        base.reportFailure(cause)

  /** Run `body` on a [[wrapExecutionContext]]-wrapped `base`, so the resulting `Future` executes under the
    * context captured at the call site rather than the worker thread's empty default. */
  def future[A](
      body: => A
  )(using base: ExecutionContext): Future[A] =
    Future(body)(using wrapExecutionContext(base))

  /** Run `body` asynchronously under the captured services/config/scope and a fresh output delta. Unlike
    * [[future]], the trace and history produced on the worker are returned explicitly in [[Executed]] instead of
    * disappearing when the worker's thread-local context is restored. The submitting thread is never mutated;
    * callers may deliberately replay the returned delta with [[RuntimeEnvironment.propagate]]. */
  def futureExecuted[A](
      body: => A
  )(using base: ExecutionContext): Future[Executed[A]] =
    val captured = captureAll
    Future(
      captured.run {
        RuntimeEnvironment.withGeneratedAsyncTask("future") {
          RuntimeEnvironment.withContext(RuntimeEnvironment.current.withDelta(RuntimeDelta.empty)) {
            val value = body
            Executed(value, RuntimeEnvironment.current.delta)
          }
        }
      }
    )(using base)
