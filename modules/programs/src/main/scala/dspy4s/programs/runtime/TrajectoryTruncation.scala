package dspy4s.programs.runtime

import dspy4s.core.contracts.{ContextWindowExceededError, DspyError}

import scala.annotation.tailrec

/** The shared "run the view, and on a context-window overflow drop the OLDEST entry and retry" loop, used by ReAct's
  * react/extract steps (Python's `_call_with_potential_trajectory_truncation`). Generic over the trajectory entry type
  * `S` and the run result `A`. The loop keeps at least one entry.
  */
object TrajectoryTruncation:

  /** The total count of the runs of one view: the first run and the runs after a truncation. */
  val MaxAttempts: Int = 3

  /** Run `view`; on a [[ContextWindowExceededError]], drop the oldest entry and retry, up to `maxAttempts` total.
    * Returns the last attempt's result paired with the (possibly truncated) view it ran against, so callers decide how
    * a persistent overflow maps onto their control flow (ReAct's react step breaks the loop on the overflow `Left`; the
    * extract step surfaces it).
    *
    * Truncation stops at the last entry. A view with one entry (or none) is never truncated: the overflow `Left` comes
    * back with the view unchanged. A run on an empty view is not a retry of the same task, because the model answers
    * with no evidence. Upstream `ReAct.truncate_trajectory` raises `ContextWindowExceededError` for a trajectory with
    * one tool call.
    */
  @tailrec
  def truncateOnOverflow[S, A](view: Vector[S], maxAttempts: Int = MaxAttempts)(
      run: Vector[S] => Either[DspyError, A]
  ): (Either[DspyError, A], Vector[S]) =
    run(view) match
      case Left(_: ContextWindowExceededError) if maxAttempts > 1 && view.sizeIs > 1 =>
        truncateOnOverflow(view.drop(1), maxAttempts - 1)(run)
      case result => (result, view)
