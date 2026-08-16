package dspy4s.programs.plan

import dspy4s.core.contracts.{DspyError, RuntimeError}
import dspy4s.programs.contracts.Prediction

/** A bounded retry strategy with a visible feedback program.
  *
  * The task and feedback programs remain ordinary [[Program]] values. The constructor uses composition, typed choice,
  * and [[Program.iterate]]. It does not add an executable wrapper or a new interpreter instruction.
  */
object FeedbackRetry:

  /** Data that the feedback program receives after a rejected attempt. */
  final case class Attempt[I, O](
      input      : I,
      prediction : Prediction[O],
      number     : Int,
      maxAttempts: Int
  )

  private final case class State[I](input: I, index: Int)

  /** Retry `task` until `accept` returns true.
    *
    * A rejected attempt runs `feedback`, which produces the next task input. Each task attempt gets a distinct rollout
    * ID. A final rejection fails without running feedback again.
    */
  def apply[I, O, RT, RF](
      task       : ProgramWithEnv[I, O, RT],
      feedback   : ProgramWithEnv[Attempt[I, O], I, RF],
      maxAttempts: Int
  )(
      accept: Attempt[I, O] => Either[DspyError, Boolean]
  ): ProgramWithEnv[I, O, RT & RF] =
    require(maxAttempts > 0, "FeedbackRetry maxAttempts must be positive")

    val taskAttempt = task.withEvidence
      .contramap[State[I]](_.input)
      .localWithInput { (state, options) =>
        options.copy(rolloutId = Some(options.rolloutId.getOrElse(0) + state.index))
      }

    val attempt = (Program.identity[State[I]] &&& taskAttempt).map { case (state, prediction) =>
      Attempt(state.input, prediction, state.index + 1, maxAttempts)
    }

    val decide = Program.liftEither[Attempt[I, O], Either[O, Attempt[I, O]]] { current =>
      accept(current).flatMap { accepted =>
        if accepted then Right(Left(current.prediction.output))
        else if current.number >= current.maxAttempts then
          Left(RuntimeError(
            "feedback_retry",
            s"Feedback retry did not produce an accepted result within ${current.maxAttempts} attempts"
          ))
        else Right(Right(current))
      }
    }

    val finish = Program.lift[O, LoopDecision[State[I], O]](LoopDecision.Done(_))
    val retry: ProgramWithEnv[Attempt[I, O], LoopDecision[State[I], O], RF] =
      (Program.identity[Attempt[I, O]] &&& feedback).map { case (current, nextInput) =>
        LoopDecision.Continue[State[I], O](State(nextInput, current.number))
      }
    val step = attempt >>> decide >>> (finish ||| retry)

    Program.lift[I, State[I]](State(_, 0)) >>> Program.iterate(step, maxAttempts)
