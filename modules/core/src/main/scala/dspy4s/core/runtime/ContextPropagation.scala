package dspy4s.core.runtime

import dspy4s.core.contracts.RuntimeContext

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Carries the active [[RuntimeContext]] across thread boundaries.
  *
  * '''Why this is needed.''' [[RuntimeEnvironment]] keeps the active context -- the configured LM / adapter /
  * callbacks plus the accumulated trace, history, and call stack -- in a `ThreadLocal`. Thread-locals do not
  * follow work that hops to another thread, so when a program runs on a pool thread (a `Future`, a parallel
  * executor worker, a streaming consumer thread) that worker starts with a fresh, empty context. Without
  * propagation, a `Predict.run` executed there would find no LM configured, fire no callbacks, and record
  * nothing back to the originating trace.
  *
  * This object closes that gap: [[capture]] snapshots the submitting thread's context, and the run helpers
  * re-install it on the worker for the duration of the task, so async / parallel work behaves as if it had run
  * inline. It is dspy4s's equivalent of DSPy's context propagation into parallel and async execution.
  *
  * Scope: only the [[RuntimeContext]] travels. The [[ActivePredictContext]] stack lives in a separate
  * thread-local and is not copied here.
  */
object ContextPropagation:

  /** Snapshot the calling thread's active context, to be replayed on a worker thread. */
  def capture: RuntimeContext = RuntimeEnvironment.current

  /** Run `thunk` with `context` installed as the active context, restoring the previous one on exit. Used to
    * replay a captured context on a thread that didn't produce it (e.g. a streaming consumer thread). */
  def inContext[A](context: RuntimeContext)(thunk: => A): A =
    RuntimeEnvironment.withContext(context)(thunk)

  /** Wrap `base` so every `Runnable` it runs executes under `captured` and a fresh generated async-task id.
   * `Future`s submitted to the returned `ExecutionContext` therefore inherit the captured configuration and
   * accumulated state; the per-task id makes each future a distinct async task for
   * [[RuntimeEnvironment.configure]] ownership purposes. */
  def wrapExecutionContext(base: ExecutionContext, captured: RuntimeContext = capture): ExecutionContext =
    new ExecutionContext:
      override def execute(runnable: Runnable): Unit =
        base.execute(() => RuntimeEnvironment.withContext(captured) {
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
