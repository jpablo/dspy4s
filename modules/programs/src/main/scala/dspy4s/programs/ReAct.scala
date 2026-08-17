package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.programs.contracts.{ToolCallRequest, ToolCallResult}
import zio.blocks.schema.DynamicValue

/** Typed reasoning-and-action orchestration over visible child programs. */
object ReAct:

  /** The model chooses control flow as data. Finish is not a synthetic tool call. */
  enum Action:
    case Invoke(request: ToolCallRequest)
    case Finish()

  final case class Step(thought: String, action: Action)

  enum Observation:
    case Succeeded(value: DynamicValue)
    case Failed(error: DspyError)
    case Finished

  final case class TrajectoryEntry(
      iteration  : Int,
      thought    : String,
      action     : Action,
      observation: Observation
  )

  final case class StepInput[I](input: I, trajectory: Vector[TrajectoryEntry])
  final case class ExtractInput[I](input: I, trajectory: Vector[TrajectoryEntry])

  private final case class State[I](input: I, trajectory: Vector[TrajectoryEntry], index: Int)
  private final case class Pending[I](state: State[I], step: Step, request: ToolCallRequest)

  /** Build a bounded tool-agent program.
    *
    * Generator, tool invocation, and extraction remain ordinary visible programs. Tool failures become trajectory data.
    * The extractor runs after an explicit `Finish` action or after the final allowed invocation.
    */
  def apply[I, O, RG, RT, RE](
      generator    : ProgramWithEnv[StepInput[I], Step, RG],
      toolInvoker  : ProgramWithEnv[ToolCallRequest, ToolCallResult, RT],
      extractor    : ProgramWithEnv[ExtractInput[I], O, RE],
      maxIterations: Int
  ): ProgramWithEnv[I, O, RG & RT & RE] =
    require(maxIterations > 0, "ReAct maxIterations must be positive")

    val generate = generator
      .contramap[State[I]](state => StepInput(state.input, state.trajectory))
      .localWithInput { (state, options) =>
        options.copy(rolloutId = Some(options.rolloutId.getOrElse(0) + state.index))
      }
    val generated = Program.identity[State[I]] &&& generate

    type Decision = LoopDecision[State[I], ExtractInput[I]]
    val route = Program.lift[(State[I], Step), Either[Decision, Pending[I]]] { case (state, step) =>
      step.action match
        case Action.Finish() =>
          val entry = TrajectoryEntry(state.index + 1, step.thought, step.action, Observation.Finished)
          Left(LoopDecision.Done(ExtractInput(state.input, state.trajectory :+ entry)))
        case Action.Invoke(request) => Right(Pending(state, step, request))
    }

    val invoke = (
      Program.identity[Pending[I]] &&& toolInvoker.attempt.contramap[Pending[I]](_.request)
    ).map { case (pending, result) =>
      val observation = result match
        case Left(error)       => Observation.Failed(error)
        case Right(callResult) => callResult.result match
            case Left(error)  => Observation.Failed(error)
            case Right(value) => Observation.Succeeded(value)
      val entry = TrajectoryEntry(
        pending.state.index + 1,
        pending.step.thought,
        pending.step.action,
        observation
      )
      val trajectory = pending.state.trajectory :+ entry
      val nextIndex  = pending.state.index + 1

      if nextIndex >= maxIterations then LoopDecision.Done(ExtractInput(pending.state.input, trajectory))
      else LoopDecision.Continue(State(pending.state.input, trajectory, nextIndex))
    }

    val step    = generated >>> route >>> (Program.identity[Decision] ||| invoke)
    val initial = Program.lift[I, State[I]](input => State(input, Vector.empty, 0))

    initial >>> Program.iterate(step, maxIterations) >>> extractor
