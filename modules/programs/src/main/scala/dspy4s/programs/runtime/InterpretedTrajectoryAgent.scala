package dspy4s.programs.runtime

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.programs.contracts.{ActionInterpreter, ActionOutcome, ProgramCall}
import dspy4s.programs.runtime.InterpretedTrajectoryAgent.{ActionPreparation, StepGeneration}

/** A trajectory agent whose iterations interpret actions produced by a model.
  *
  * This is the shared inner loop behind `ReAct` and `CodeAct`:
  *
  * `generate step -> prepare action -> interpret action -> record outcome -> continue or stop`
  *
  * Associated types keep the public agent type focused on input, output, and trajectory entry while allowing each
  * action language to choose its own model-step, action, and observation types.
  *
  * The final [[trajectoryStep]] supplies the shared branch laws: `Halted` neither interprets nor records; `Rejected`
  * records one failed outcome without interpreting; `Ready` interprets exactly once and records exactly one outcome;
  * `stopAfter` is applied only after that outcome is recorded; and a fatal interpreter `Left` propagates without
  * appending an entry.
  */
trait InterpretedTrajectoryAgent[I, O, Entry] extends TrajectoryAgent[I, O, Entry]:

  /** Typed output emitted by the model for one iteration. */
  type ModelStep

  /** Executable action obtained by lowering a [[ModelStep]]. */
  type Action

  /** Value observed after interpreting an [[Action]]. */
  type Observation

  protected def actionInterpreter: ActionInterpreter[Action, Observation]

  /** Generate the next typed model step. Implementations may replace the trajectory view (for durable truncation) or
    * halt before producing an action (for example after a persistent context-window overflow).
    */
  protected def generateStep(
      call: ProgramCall[I],
      trajectory: Vector[Entry]
  )(using RuntimeContext): Either[DspyError, StepGeneration[ModelStep, Entry]]

  /** Lower a model step into an executable action and its post-execution stop decision. A rejected preparation carries
    * the failure observation to record and always continues to the next iteration.
    */
  protected def prepareAction(step: ModelStep): ActionPreparation[Action, Observation]

  /** Turn an optional interpreted action and its outcome into this language's trajectory entry. `None` means action
    * preparation was rejected, so no interpreter was invoked.
    */
  protected def recordStep(
      iteration: Int,
      step: ModelStep,
      action: Option[Action],
      outcome: ActionOutcome[Observation]
  ): Entry

  final override protected def trajectoryStep(call: ProgramCall[I])(using
      RuntimeContext
  ): (Vector[Entry], Int) => Either[DspyError, TrajectoryAgent.Step[Entry]] =
    (trajectory, iteration) =>
      generateStep(call, trajectory).flatMap {
        case StepGeneration.Halted(used) =>
          Right(AgentLoop.Step.Done(used))
        case StepGeneration.Generated(step, used) =>
          prepareAction(step) match
            case ActionPreparation.Rejected(observation) =>
              val outcome = ActionOutcome.Failed(observation)
              Right(AgentLoop.Step.Continue(used :+ recordStep(iteration, step, None, outcome)))
            case ActionPreparation.Ready(action, stopAfter) =>
              actionInterpreter.execute(action).map { outcome =>
                val updated = used :+ recordStep(iteration, step, Some(action), outcome)
                if stopAfter then AgentLoop.Step.Done(updated)
                else AgentLoop.Step.Continue(updated)
              }
      }

object InterpretedTrajectoryAgent:

  /** Result of the model-generation phase for one iteration. */
  enum StepGeneration[+ModelStep, +Entry]:
    case Generated(step: ModelStep, trajectory: Vector[Entry])
    case Halted(trajectory: Vector[Entry])

  /** Result of lowering a typed model step into the action language. */
  enum ActionPreparation[+Action, +Observation]:
    case Ready(action: Action, stopAfter: Boolean)
    case Rejected(observation: Observation)
