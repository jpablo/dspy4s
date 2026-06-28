package dspy4s.programs.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.updated
import dspy4s.programs.DynamicPredict
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.TrajectoryTruncation.truncateOnOverflow
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** The shared "gather a trajectory, then extract the answer from it" agent shape behind `ReAct` and `CodeAct`
  * (the trajectory-and-extractor flavor of `agentLoop`; see `docs/refactor/algebra-2-program-composition.md`).
  *
  * Both run a bounded loop building a `Vector[S]` of trajectory steps (via [[AgentLoop.run]]), then feed the
  * rendered trajectory to a reasoning-augmented extractor predict, truncating the OLDEST step and retrying on a
  * context-window overflow (Python's `_call_with_potential_trajectory_truncation`). What differs — the
  * trajectory entry type, how it renders, and the per-iteration policy/tool/code step — stays in the caller's
  * `step` closure and `render` function. The final `decodePrepended` into the typed `WithReasoning[O]` stays in
  * the module (where the prepend evidence lives). */
object TrajectoryAgent:

  /** One iteration's outcome: `Continue(view)` keeps gathering (the caller appended its entry, possibly after a
    * durable truncation of the view), `Done(view)` stops (the agent chose to finish, or a persistent overflow
    * broke the loop). The state and result are both the trajectory `Vector[S]`. */
  type Step[S] = AgentLoop.Step[Vector[S], Vector[S]]

  /** Run the bounded loop then the extractor. Returns the extractor's raw prediction paired with the rendered
    * (complete, untruncated) trajectory for the caller to attach to `.raw`.
    *
    * @param baseCall      the underlying program call (controls inherited from the typed call)
    * @param inputs        the base task inputs the extractor sees alongside the trajectory
    * @param maxIterations loop budget; on exhaustion the accumulated trajectory is extracted as-is
    * @param trajectoryKey the input field name the rendered trajectory is written to ("trajectory")
    * @param render        renders a trajectory to the prompt string (also used for the overflow-truncation retry)
    * @param extractor     the final extractor predict
    * @param step          one iteration: run the policy + action, append an entry, decide Continue / Done */
  def runAndExtract[S](
      baseCall: ProgramCall,
      inputs: DynamicValue.Record,
      maxIterations: Int,
      trajectoryKey: String,
      render: Vector[S] => String,
      extractor: DynamicPredict,
      extractAttempts: Int = 3
  )(
      step: (Vector[S], Int) => Either[DspyError, Step[S]]
  )(using RuntimeContext): Either[DspyError, (DynamicPrediction, String)] =
    for
      trajectory <- AgentLoop.run[Vector[S], Vector[S]](Vector.empty, 0, maxIterations)(onExhausted = Right(_))(step)
      rendered    = render(trajectory)
      extracted  <- truncateOnOverflow(trajectory, extractAttempts)(render) { view =>
                      extractor.apply(baseCall.copy(
                        inputs = inputs.updated(trajectoryKey, DynamicValue.Primitive(PrimitiveValue.String(view)))
                      ))
                    }._1
    yield (extracted, rendered)
