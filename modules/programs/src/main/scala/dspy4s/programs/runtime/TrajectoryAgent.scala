package dspy4s.programs.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.programs.runtime.TrajectoryTruncation.truncateOnOverflow

/** The shared "gather a trajectory, then extract the answer from it" agent shape behind `ReAct` and `CodeAct` (the
  * trajectory-and-extractor flavor of `agentLoop`; see `docs/refactor/algebra-2-program-composition.md`).
  *
  * Both run a bounded loop building a `Vector[S]` of trajectory steps (via [[AgentLoop.run]]), then feed the rendered
  * trajectory to a reasoning-augmented extractor predict, truncating the OLDEST step and retrying on a context-window
  * overflow (Python's `_call_with_potential_trajectory_truncation`). What differs — the trajectory entry type, how it
  * renders, the per-iteration policy/tool/code step, and HOW the extractor is called (a typed `Predict` constructing
  * its augmented input, or a dynamic record update) — stays in the caller's `step` and `extract` closures, so the
  * helper is agnostic to the extractor's typing (`E` is the extractor's result: a typed `Prediction[..]` for the
  * converted programs).
  */
object TrajectoryAgent:

  /** One iteration's outcome: `Continue(view)` keeps gathering (the caller appended its entry, possibly after a durable
    * truncation of the view), `Done(view)` stops (the agent chose to finish, or a persistent overflow broke the loop).
    * The state and result are both the trajectory `Vector[S]`.
    */
  type Step[S] = AgentLoop.Step[Vector[S], Vector[S]]

  /** Run the bounded loop then the extractor. Returns the extractor's result paired with the rendered (complete,
    * untruncated) trajectory for the caller to attach to `.raw`.
    *
    * @param maxIterations
    *   loop budget; on exhaustion the accumulated trajectory is extracted as-is
    * @param render
    *   renders a trajectory to the prompt string (also used for the overflow-truncation retry)
    * @param extractAttempts
    *   truncate-and-retry budget for the extractor on context-window overflow
    * @param step
    *   one iteration: run the policy + action, append an entry, decide Continue / Done
    * @param extract
    *   the final extraction over the rendered trajectory (typically an inner predict call)
    */
  def runAndExtract[S, E](
      maxIterations: Int,
      render: Vector[S] => String,
      extractAttempts: Int = 3
  )(
      step: (Vector[S], Int) => Either[DspyError, Step[S]]
  )(
      extract: String => Either[DspyError, E]
  ): Either[DspyError, (E, String)] =
    for
      trajectory <- AgentLoop.run[Vector[S], Vector[S]](Vector.empty, 0, maxIterations)(onExhausted = Right(_))(step)
      rendered    = render(trajectory)
      extracted  <- truncateOnOverflow(trajectory, extractAttempts)(render)(extract)._1
    yield (extracted, rendered)
