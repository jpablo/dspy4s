package dspy4s.programs.runtime

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.programs.contracts.{ActionInterpreter, ActionOutcome, ProgramCall}
import dspy4s.programs.runtime.InterpretedTrajectoryAgent.{
  ActionDecision,
  ActionPreparation,
  DecisionTransition,
  GenerationTransition,
  InterpretationTransition,
  PreparationTransition,
  State,
  StepGeneration
}

/** A trajectory agent whose iterations interpret actions produced by a model.
  *
  * This is the shared inner loop behind `ReAct` and `CodeAct`:
  *
  * `generate step -> prepare action -> interpret action -> record outcome -> decide whether to continue or stop`
  *
  * Associated types keep the public agent type focused on input, output, and trajectory entry while allowing each
  * action language to choose its own model-step, action, and observation types.
  *
  * The phases are explicit state ADTs under [[InterpretedTrajectoryAgent.State State]], and private transition ADTs
  * enumerate each phase's legal successors. The final [[trajectoryStep]] interprets that machine and supplies the
  * shared branch laws: `Halted` neither interprets nor records; `Rejected` records one failed outcome without
  * interpreting; `Ready` interprets exactly once and records exactly one outcome; [[decide]] runs exactly once after
  * that outcome is recorded; and a fatal interpreter `Left` propagates without recording or deciding.
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
      call      : ProgramCall[I],
      trajectory: Vector[Entry]
  )(using RuntimeContext): Either[DspyError, StepGeneration[ModelStep, Entry]]

  /** Lower a model step into an executable action. A rejected preparation carries the failure observation to record and
    * always continues to the next iteration.
    */
  protected def prepareAction(step: ModelStep): ActionPreparation[Action, Observation]

  /** Choose the next control-flow branch after a ready action has been interpreted and its outcome recorded. Receiving
    * both the model step and the interpreted outcome supports languages whose terminal condition is expressed before
    * execution (`ReAct.finish`, `CodeAct.finished`) as well as languages whose terminal condition depends on execution
    * success. Rejected preparations always continue and do not invoke this hook.
    */
  protected def decide(
      step   : ModelStep,
      action : Action,
      outcome: ActionOutcome[Observation]
  ): ActionDecision

  /** Record a rejected preparation. This state has an observation but no action or interpreted outcome. */
  protected def recordRejection(
      iteration  : Int,
      step       : ModelStep,
      observation: Observation
  ): Entry

  /** Record an interpreted action and its observable outcome. */
  protected def recordOutcome(
      iteration: Int,
      step     : ModelStep,
      action   : Action,
      outcome  : ActionOutcome[Observation]
  ): Entry

  private final def transitionGeneration(
      call   : ProgramCall[I],
      current: State.Generating[Entry]
  )(using RuntimeContext): GenerationTransition[ModelStep, Entry] =
    generateStep(call, current.trajectory) match
      case Left(error)                                 => GenerationTransition.Fail(State.Failed(error))
      case Right(StepGeneration.Halted(used))          => GenerationTransition.Complete(State.Completed(used))
      case Right(StepGeneration.Generated(step, used)) =>
        GenerationTransition.Prepare(State.Preparing(step, used, current.iteration))

  private final def transitionPreparation(
      current: State.Preparing[ModelStep, Entry]
  ): PreparationTransition[ModelStep, Action, Observation, Entry] =
    prepareAction(current.step) match
      case ActionPreparation.Rejected(observation) => PreparationTransition.RecordRejection(
          State.RecordingRejection(current.step, observation, current.trajectory, current.iteration)
        )
      case ActionPreparation.Ready(action) => PreparationTransition.Interpret(
          State.Interpreting(current.step, action, current.trajectory, current.iteration)
        )

  private final def transitionInterpretation(
      current: State.Interpreting[ModelStep, Action, Entry]
  )(using RuntimeContext): InterpretationTransition[ModelStep, Action, Observation, Entry] =
    actionInterpreter.execute(current.action) match
      case Left(error)    => InterpretationTransition.Fail(State.Failed(error))
      case Right(outcome) => InterpretationTransition.RecordOutcome(
          State.RecordingOutcome(
            current.step,
            current.action,
            outcome,
            current.trajectory,
            current.iteration
          )
        )

  private final def transitionRejectionRecording(
      current: State.RecordingRejection[ModelStep, Observation, Entry]
  ): State.Continuing[Entry] =
    val entry = recordRejection(current.iteration, current.step, current.observation)
    State.Continuing(current.trajectory :+ entry)

  private final def transitionOutcomeRecording(
      current: State.RecordingOutcome[ModelStep, Action, Observation, Entry]
  ): State.Deciding[ModelStep, Action, Observation, Entry] =
    val entry = recordOutcome(current.iteration, current.step, current.action, current.outcome)
    State.Deciding(current.step, current.action, current.outcome, current.trajectory :+ entry)

  private final def transitionDecision(
      current: State.Deciding[ModelStep, Action, Observation, Entry]
  ): DecisionTransition[Entry] =
    decide(current.step, current.action, current.outcome) match
      case ActionDecision.Continue => DecisionTransition.Continue(State.Continuing(current.trajectory))
      case ActionDecision.Stop     => DecisionTransition.Complete(State.Completed(current.trajectory))

  private final def runStateMachine(
      call   : ProgramCall[I],
      initial: State.Generating[Entry]
  )(using RuntimeContext): State.Terminal[Entry] =
    transitionGeneration(call, initial) match
      case GenerationTransition.Fail(failed)       => failed
      case GenerationTransition.Complete(complete) => complete
      case GenerationTransition.Prepare(preparing) => transitionPreparation(preparing) match
          case PreparationTransition.RecordRejection(recording) => transitionRejectionRecording(recording)
          case PreparationTransition.Interpret(interpreting)    => transitionInterpretation(interpreting) match
              case InterpretationTransition.Fail(failed)             => failed
              case InterpretationTransition.RecordOutcome(recording) =>
                transitionDecision(transitionOutcomeRecording(recording)) match
                  case DecisionTransition.Continue(continuing) => continuing
                  case DecisionTransition.Complete(complete)   => complete

  final override protected def trajectoryStep(call: ProgramCall[I])(using
      RuntimeContext
  ): (Vector[Entry], Int) => Either[DspyError, TrajectoryAgent.Step[Entry]] =
    (trajectory, iteration) =>
      runStateMachine(call, State.Generating(trajectory, iteration)) match
        case State.Continuing(updated) => Right(AgentLoop.Step.Continue(updated))
        case State.Completed(updated)  => Right(AgentLoop.Step.Done(updated))
        case State.Failed(error)       => Left(error)

object InterpretedTrajectoryAgent:

  /** Explicit control states for one interpreted trajectory iteration. Each nonterminal state contains exactly the
    * values available to its legal outgoing transition.
    */
  object State:
    final case class Generating[+Entry](trajectory: Vector[Entry], iteration: Int)

    final case class Preparing[+ModelStep, +Entry](
        step      : ModelStep,
        trajectory: Vector[Entry],
        iteration : Int
    )

    final case class Interpreting[+ModelStep, +Action, +Entry](
        step      : ModelStep,
        action    : Action,
        trajectory: Vector[Entry],
        iteration : Int
    )

    final case class RecordingRejection[+ModelStep, +Observation, +Entry](
        step       : ModelStep,
        observation: Observation,
        trajectory : Vector[Entry],
        iteration  : Int
    )

    final case class RecordingOutcome[+ModelStep, +Action, +Observation, +Entry](
        step      : ModelStep,
        action    : Action,
        outcome   : ActionOutcome[Observation],
        trajectory: Vector[Entry],
        iteration : Int
    )

    final case class Deciding[+ModelStep, +Action, +Observation, +Entry](
        step      : ModelStep,
        action    : Action,
        outcome   : ActionOutcome[Observation],
        trajectory: Vector[Entry]
    )

    sealed trait Terminal[+Entry]
    final case class Continuing[+Entry](trajectory: Vector[Entry]) extends Terminal[Entry]
    final case class Completed[+Entry](trajectory: Vector[Entry])  extends Terminal[Entry]
    final case class Failed(error: DspyError)                      extends Terminal[Nothing]

  /** Legal successors of [[State.Generating]]. */
  private enum GenerationTransition[+ModelStep, +Entry]:
    case Prepare(next: State.Preparing[ModelStep, Entry])
    case Complete(next: State.Completed[Entry])
    case Fail(next: State.Failed)

  /** Legal successors of [[State.Preparing]]. */
  private enum PreparationTransition[+ModelStep, +Action, +Observation, +Entry]:
    case Interpret(next: State.Interpreting[ModelStep, Action, Entry])
    case RecordRejection(next: State.RecordingRejection[ModelStep, Observation, Entry])

  /** Legal successors of [[State.Interpreting]]. */
  private enum InterpretationTransition[+ModelStep, +Action, +Observation, +Entry]:
    case RecordOutcome(next: State.RecordingOutcome[ModelStep, Action, Observation, Entry])
    case Fail(next: State.Failed)

  /** Legal successors of [[State.Deciding]]. */
  private enum DecisionTransition[+Entry]:
    case Continue(next: State.Continuing[Entry])
    case Complete(next: State.Completed[Entry])

  /** Result of the model-generation phase for one iteration. */
  enum StepGeneration[+ModelStep, +Entry]:
    case Generated(step: ModelStep, trajectory: Vector[Entry])
    case Halted(trajectory: Vector[Entry])

  /** Result of lowering a typed model step into the action language. */
  enum ActionPreparation[+Action, +Observation]:
    case Ready(action: Action)
    case Rejected(observation: Observation)

  /** Post-interpretation control decision for a ready action. */
  enum ActionDecision derives CanEqual:
    case Continue
    case Stop
