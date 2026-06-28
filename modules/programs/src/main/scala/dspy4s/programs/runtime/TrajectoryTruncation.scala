package dspy4s.programs.runtime

import dspy4s.core.contracts.{ContextWindowExceededError, DspyError}

import scala.annotation.tailrec

/** The shared "render the view, run it, and on a context-window overflow drop the OLDEST entry and retry"
  * loop, used by ReAct's react/extract steps and CodeAct's extract step (Python's
  * `_call_with_potential_trajectory_truncation`). Generic over the trajectory entry type `S` and the run
  * result `A`. */
object TrajectoryTruncation:

  /** Render `view` and run it; on a [[ContextWindowExceededError]], drop the oldest entry and retry, up to
    * `maxAttempts` total. Returns the last attempt's result paired with the (possibly truncated) view it ran
    * against, so callers decide how a persistent overflow maps onto their control flow (ReAct's react step
    * breaks the loop on `None`; the extract steps surface the `Left`). */
  @tailrec
  def truncateOnOverflow[S, A](view: Vector[S], maxAttempts: Int)(render: Vector[S] => String)(
      run: String => Either[DspyError, A]
  ): (Either[DspyError, A], Vector[S]) =
    run(render(view)) match
      case Left(_: ContextWindowExceededError) if maxAttempts > 1 && view.nonEmpty =>
        truncateOnOverflow(view.drop(1), maxAttempts - 1)(render)(run)
      case result => (result, view)
