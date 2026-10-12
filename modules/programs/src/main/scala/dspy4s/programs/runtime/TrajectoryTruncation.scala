package dspy4s.programs.runtime

import dspy4s.core.contracts.{ContextWindowExceededError, DspyError}

import scala.annotation.tailrec

/** The shared "render the view, run it, and on a context-window overflow drop the OLDEST entry and retry" loop, used by
  * ReAct's react/extract steps (Python's `_call_with_potential_trajectory_truncation`). Generic over the trajectory
  * entry type `S` and the run result `A`. The loop keeps at least one entry.
  */
object TrajectoryTruncation:

  /** Render `view` and run it; on a [[ContextWindowExceededError]], drop the oldest entry and retry, up to
    * `maxAttempts` total. Returns the last attempt's result paired with the (possibly truncated) view it ran against,
    * so callers decide how a persistent overflow maps onto their control flow (ReAct's react step breaks the loop on
    * `None`; the extract steps surface the `Left`).
    *
    * Truncation stops at the last entry. A view with one entry (or none) is never truncated: the overflow `Left` comes
    * back with the view unchanged. A run on an empty view is not a retry of the same task, because the model answers
    * with no evidence. Upstream `ReAct.truncate_trajectory` raises `ContextWindowExceededError` for a trajectory with
    * one tool call.
    */
  @tailrec
  def truncateOnOverflow[S, A](view: Vector[S], maxAttempts: Int)(render: Vector[S] => String)(
      run: String => Either[DspyError, A]
  ): (Either[DspyError, A], Vector[S]) =
    run(render(view)) match
      case Left(_: ContextWindowExceededError) if maxAttempts > 1 && view.sizeIs > 1 =>
        truncateOnOverflow(view.drop(1), maxAttempts - 1)(render)(run)
      case result => (result, view)
