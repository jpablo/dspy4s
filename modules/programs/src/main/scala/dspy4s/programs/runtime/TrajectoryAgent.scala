package dspy4s.programs.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.IterationLimit
import dspy4s.programs.contracts.{Module, ProgramCall}
import dspy4s.programs.runtime.TrajectoryTruncation.truncateOnOverflow
import dspy4s.typed.Prediction
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** A module that gathers a typed trajectory and then extracts its user-visible result.
  *
  * `ReAct` and `CodeAct` share this execution shape even though their transitions are different: ReAct chooses and
  * invokes a tool, while CodeAct generates and executes code. Implementations provide that domain-specific
  * [[trajectoryStep]], plus a renderer and extractor; this trait owns the common module boundary and guarantees that
  * the complete trajectory is attached to the extractor's preserved raw prediction.
  *
  * Because [[forward]] is final, every implementation has the same orchestration guarantees: extraction runs exactly
  * once after either `Done` or budget exhaustion, never after a failed transition; the extractor's complete prediction
  * envelope is preserved; and extractor-local overflow truncation never changes the complete trajectory attached to
  * that envelope.
  *
  * @tparam I
  *   the program input
  * @tparam O
  *   the extracted program output
  * @tparam S
  *   one entry in the accumulated trajectory
  */
trait TrajectoryAgent[I, O, S] extends Module[I, O]:

  /** Maximum number of transitions before extracting from the accumulated trajectory. */
  def maxIterations: IterationLimit

  /** The final module that turns the original input and rendered trajectory into the user-visible prediction. */
  protected def extractorPredict: Module[(I, String), O]

  /** Raw-prediction field under which the complete rendered trajectory is exposed. */
  protected def trajectoryKey: String

  /** Render the typed trajectory for both the iterative policy and final extractor. */
  protected def renderTrajectory(trajectory: Vector[S]): String

  /** One domain-specific transition: act, append an entry, and decide whether the trajectory is complete. */
  protected def trajectoryStep(call: ProgramCall[I])(using
      RuntimeContext
  ): (Vector[S], Int) => Either[DspyError, TrajectoryAgent.Step[S]]

  final override protected def forward(call: ProgramCall[I])(using
      RuntimeContext
  ): Either[DspyError, Prediction[O]] =
    TrajectoryAgent.runAndExtractPrediction[S, O](
      maxIterations,
      renderTrajectory,
      trajectoryKey
    )(trajectoryStep(call)) {
      rendered =>
        extractorPredict(call.mapInput(input => (input, rendered)))
    }

/** The shared "gather a trajectory, then extract the answer from it" agent shape behind `ReAct` and `CodeAct` (the
  * trajectory-and-extractor flavor of `agentLoop`; see `docs/refactor/algebra-2-program-composition.md`).
  *
  * Both run a bounded loop building a `Vector[S]` of trajectory steps (via [[AgentLoop.run]]), then feed the rendered
  * trajectory to a reasoning-augmented extractor predict, truncating the OLDEST step and retrying on a context-window
  * overflow (Python's `_call_with_potential_trajectory_truncation`). The generic helpers remain available separately
  * from the [[TrajectoryAgent]] module template so non-module runtimes can reuse the same loop mechanics.
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
      maxIterations  : Int,
      render         : Vector[S] => String,
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

  /** Prediction-specialized [[runAndExtract]]: preserve the extractor's complete typed/raw result and add the full,
    * pre-extractor-truncation trajectory to its raw values.
    *
    * The extractor may have seen an oldest-first truncated view after a context-window overflow; the attached value is
    * still the complete trajectory returned by the loop. Using [[dspy4s.core.data.RawPrediction.withValue withValue]]
    * rather than rebuilding the raw envelope also preserves completions, LM usage, and any future raw metadata.
    */
  def runAndExtractPrediction[S, O](
      maxIterations  : Int,
      render         : Vector[S] => String,
      trajectoryKey  : String,
      extractAttempts: Int = 3
  )(
      step: (Vector[S], Int) => Either[DspyError, Step[S]]
  )(
      extract: String => Either[DspyError, Prediction[O]]
  ): Either[DspyError, Prediction[O]] =
    runAndExtract[S, Prediction[O]](maxIterations, render, extractAttempts)(step)(extract).map {
      case (extracted, rendered) => extracted.copy(raw =
          extracted.raw.withValue(
            trajectoryKey,
            DynamicValue.Primitive(PrimitiveValue.String(rendered))
          )
        )
    }
