package dspy4s.core.runtime

import dspy4s.core.contracts.RuntimeContext

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object ContextPropagation:
  def capture: RuntimeContext = RuntimeEnvironment.current

  def inContext[A](context: RuntimeContext)(thunk: => A): A =
    RuntimeEnvironment.withContext(context)(thunk)

  def wrapExecutionContext(
      base: ExecutionContext,
      captured: RuntimeContext = capture
  ): ExecutionContext =
    new ExecutionContext:
      override def execute(runnable: Runnable): Unit =
        base.execute(new Runnable:
          override def run(): Unit =
            RuntimeEnvironment.withContext(captured) {
              RuntimeEnvironment.withGeneratedAsyncTask("future") {
                runnable.run()
              }
            }
        )

      override def reportFailure(cause: Throwable): Unit =
        base.reportFailure(cause)

  def future[A](
      body: => A
  )(using base: ExecutionContext): Future[A] =
    Future(body)(using wrapExecutionContext(base))
