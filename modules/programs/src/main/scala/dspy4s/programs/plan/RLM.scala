package dspy4s.programs.plan

import dspy4s.core.contracts.{DspyError, DynamicValues}
import dspy4s.signatures.Shape

/** Typed recursive-language-model orchestration over visible child programs. */
object RLM:

  final case class ActionInput[I](
      input        : I,
      history      : Vector[TrajectoryEntry],
      iteration    : Int,
      maxIterations: Int
  )

  final case class ActionStep(reasoning: String, code: String)

  final case class ExecutionInput[I](
      input    : I,
      reasoning: String,
      code     : String,
      history  : Vector[TrajectoryEntry]
  )

  enum ExecutionResult[+O]:
    case Observed(output: String, isError: Boolean)
    case Submitted(output: O)

  final case class TrajectoryEntry(
      iteration : Int,
      reasoning : String,
      code      : String,
      observation: String,
      isError   : Boolean
  )

  final case class ExtractInput[I](input: I, history: Vector[TrajectoryEntry])

  private final case class State[I](input: I, history: Vector[TrajectoryEntry], index: Int)
  private final case class Pending[I](state: State[I], action: ActionStep, code: String)

  /** Adapt the neutral persistent-REPL capability to typed RLM execution. */
  def replExecutor[I, O](
      inputShape : Shape[I],
      outputShape: Shape[O]
  ): ProgramWithEnv[ExecutionInput[I], ExecutionResult[O], ReplExecutionBackend] =
    Program.executeRepl
      .contramap[ExecutionInput[I]](input =>
        ReplExecutionRequest(input.code, inputShape.encode(input.input).fields.iterator.toMap)
      )
      .map { result =>
        result.finalOutput match
          case Some(json) =>
            DynamicValues.parseJsonRecord(json) match
              case Some(record) => outputShape.decode(record) match
                  case Right(output) => ExecutionResult.Submitted(output)
                  case Left(error)   => ExecutionResult.Observed(s"[Type Error] ${error.message}", isError = true)
              case None => ExecutionResult.Observed("[Error] SUBMIT returned a non-record value", isError = true)
          case None =>
            if result.exitCode == 0 then
              ExecutionResult.Observed(
                Option(result.stdout.stripTrailing).filter(_.nonEmpty).getOrElse("(no output)"),
                isError = false
              )
            else ExecutionResult.Observed(result.stderr.stripTrailing, isError = true)
      }

  /** Build a bounded RLM control loop.
    *
    * The executor owns the REPL semantics. It receives the original typed input on every call, so a production child
    * can expose those values as session variables. A submitted typed value ends the loop. Exhaustion runs the visible
    * extractor. Executor infrastructure failure remains in the typed error channel.
    */
  def apply[I, O, RG, RX, RE](
      generator    : ProgramWithEnv[ActionInput[I], ActionStep, RG],
      executor     : ProgramWithEnv[ExecutionInput[I], ExecutionResult[O], RX],
      extractor    : ProgramWithEnv[ExtractInput[I], O, RE],
      maxIterations: Int,
      parseCode    : String => Either[DspyError, String] = ProgramOfThought.parseGeneratedPython
  ): ProgramWithEnv[I, O, RG & RX & RE] =
    require(maxIterations > 0, "RLM maxIterations must be positive")

    type Result   = Either[O, ExtractInput[I]]
    type Decision = LoopDecision[State[I], Result]

    def continueOrExtract(state: State[I], entry: TrajectoryEntry): Decision =
      val history   = state.history :+ entry
      val nextIndex = state.index + 1
      if nextIndex >= maxIterations then LoopDecision.Done(Right(ExtractInput(state.input, history)))
      else LoopDecision.Continue(State(state.input, history, nextIndex))

    val generate = generator
      .contramap[State[I]](state => ActionInput(state.input, state.history, state.index + 1, maxIterations))
      .localWithInput { (state, options) =>
        options.copy(rolloutId = Some(options.rolloutId.getOrElse(0) + state.index))
      }
    val generated = Program.identity[State[I]] &&& generate

    val prepare = Program.lift[(State[I], ActionStep), Either[Decision, Pending[I]]] { case (state, action) =>
      parseCode(action.code) match
        case Right(code) => Right(Pending(state, action, code))
        case Left(error) =>
          val entry = TrajectoryEntry(
            state.index + 1,
            action.reasoning,
            action.code,
            error.message,
            isError = true
          )
          Left(continueOrExtract(state, entry))
    }

    val execute = (
      Program.identity[Pending[I]] &&& executor.contramap[Pending[I]](pending =>
        ExecutionInput(
          pending.state.input,
          pending.action.reasoning,
          pending.code,
          pending.state.history
        )
      )
    ).map { case (pending, result) =>
      result match
        case ExecutionResult.Observed(output, isError) =>
          val entry = TrajectoryEntry(
            pending.state.index + 1,
            pending.action.reasoning,
            pending.code,
            output,
            isError
          )
          continueOrExtract(pending.state, entry)
        case ExecutionResult.Submitted(output) =>
          LoopDecision.Done(Left(output))
    }

    val step    = generated >>> prepare >>> (Program.identity[Decision] ||| execute)
    val initial = Program.lift[I, State[I]](input => State(input, Vector.empty, 0))

    initial >>> Program.iterate(step, maxIterations) >>> (Program.identity[O] ||| extractor)
