package dspy4s.programs.plan

import dspy4s.core.contracts.{DspyError, RuntimeError}
import dspy4s.programs.runtime.GeneratedPython

/** Program-of-thought orchestration as a constructor over four visible child programs. */
object ProgramOfThought:

  final case class GeneratedCode(code: Option[String])
  final case class RetryInput[I](input: I, previousCode: String, error: String)
  final case class AnswerInput[I](input: I, finalCode: String, codeOutput: String)

  private final case class FailedAttempt(code: String, error: String)
  private final case class State[I](input: I, previous: Option[FailedAttempt], index: Int)

  /** Build generate, retry, execute, and answer orchestration.
    *
    * `executor` is an explicit child program. A failed value is retryable. A failure in the program error channel is
    * fatal. `parseCode` can normalize generated source or reject it before execution.
    */
  def apply[I, O, RG, RR, RE, RA](
      generator  : ProgramWithEnv[I, GeneratedCode, RG],
      regenerator: ProgramWithEnv[RetryInput[I], GeneratedCode, RR],
      executor   : ProgramWithEnv[String, CodeExecutionResult, RE],
      answerer   : ProgramWithEnv[AnswerInput[I], O, RA],
      maxAttempts: Int,
      parseCode  : String => Either[DspyError, String] = value => Right(value)
  ): ProgramWithEnv[I, O, RG & RR & RE & RA] =
    require(maxAttempts > 0, "ProgramOfThought maxAttempts must be positive")

    val selectGenerator = Program.lift[State[I], Either[I, RetryInput[I]]] { state =>
      state.previous match
        case None          => Left(state.input)
        case Some(failure) => Right(RetryInput(state.input, failure.code, failure.error))
    }
    val generate = (selectGenerator >>> (generator ||| regenerator)).localWithInput { (state, options) =>
      options.copy(rolloutId = Some(options.rolloutId.getOrElse(0) + state.index))
    }
    val generated = Program.identity[State[I]] &&& generate

    type Decision = LoopDecision[State[I], AnswerInput[I]]
    val prepare = Program.liftEither[(State[I], GeneratedCode), Either[Decision, (State[I], String)]] {
      case (state, result) =>
        result.code match
          case None => reject(
              state,
              "",
              "The model response did not contain generated code",
              maxAttempts
            ).map(Left(_))
          case Some(rawCode) =>
            parseCode(rawCode) match
              case Left(error) => reject(state, rawCode, error.message, maxAttempts).map(Left(_))
              case Right(code) => Right(Right(state -> code))
    }

    val execute = (
      Program.identity[(State[I], String)] &&& executor.contramap[(State[I], String)](_._2)
    ) >>> Program.liftEither[((State[I], String), CodeExecutionResult), Decision] {
      case ((state, code), CodeExecutionResult.Succeeded(output)) =>
        Right(LoopDecision.Done(AnswerInput(state.input, code, output)))
      case ((state, code), CodeExecutionResult.Failed(error)) =>
        reject(state, code, error, maxAttempts)
    }

    val step = generated >>> prepare >>> (Program.identity[Decision] ||| execute)
    val initial = Program.lift[I, State[I]](input => State(input, None, 0))

    initial >>> Program.iterate(step, maxAttempts) >>> answerer

  /** Shared tolerant parser used by the legacy code agents. */
  val parseGeneratedPython: String => Either[DspyError, String] = raw =>
    GeneratedPython.parse(raw).left.map(error => RuntimeError("program_of_thought_parse", error))

  private def reject[I](
      state      : State[I],
      code       : String,
      error      : String,
      maxAttempts: Int
  ): Either[DspyError, LoopDecision[State[I], AnswerInput[I]]] =
    val nextIndex = state.index + 1
    if nextIndex >= maxAttempts then
      Left(RuntimeError(
        "program_of_thought",
        s"Program of thought did not produce executable code within $maxAttempts attempts. Last error: $error"
      ))
    else
      Right(LoopDecision.Continue[State[I], AnswerInput[I]](
        State(state.input, Some(FailedAttempt(code, error)), nextIndex)
      ))
