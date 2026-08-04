package dspy4s.programs.contracts

import dspy4s.core.contracts.{DspyError, RuntimeContext}

/** The observable result of evaluating an agent action.
  *
  * Both cases contain an observation that can be recorded in the trajectory and shown to the agent on its next turn.
  * [[Failed]] represents a recoverable action failure; a failure that prevents the agent from continuing is instead
  * returned as `Left` from [[ActionInterpreter.execute]].
  */
enum ActionOutcome[+Observation]:
  case Succeeded(value: Observation)
  case Failed(value: Observation)

  def observation: Observation = this match
    case Succeeded(value) => value
    case Failed(value)    => value

  def isError: Boolean = this match
    case Succeeded(_) => false
    case Failed(_)    => true

/** Evaluates actions expressed in some agent language.
  *
  * Configuration and capabilities belong to the interpreter value itself. Parsing model output, deciding when an agent
  * is finished, rendering its trajectory, and owning interpreter resources remain separate concerns.
  *
  * `Right(ActionOutcome.Failed(observation))` means execution failed in a way the agent can observe and recover from;
  * `Left(error)` means the surrounding agent cannot continue.
  */
trait ActionInterpreter[-Action, +Observation]:
  def execute(action: Action)(using RuntimeContext): Either[DspyError, ActionOutcome[Observation]]
