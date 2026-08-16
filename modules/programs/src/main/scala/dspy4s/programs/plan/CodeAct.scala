package dspy4s.programs.plan

import dspy4s.core.contracts.DspyError

/** Typed iterative code-generation orchestration over visible child programs. */
object CodeAct:

  final case class Step(generatedCode: String, finished: Boolean)

  enum Observation:
    case Succeeded(output: String)
    case Failed(error: String)

  final case class TrajectoryEntry(
      iteration      : Int,
      generatedCode  : String,
      executedCode   : Option[String],
      requestedFinish: Boolean,
      observation    : Observation
  )

  final case class StepInput[I](input: I, trajectory: Vector[TrajectoryEntry])
  final case class ExtractInput[I](input: I, trajectory: Vector[TrajectoryEntry])

  private final case class State[I](input: I, trajectory: Vector[TrajectoryEntry], index: Int)
  private final case class Pending[I](state: State[I], step: Step, code: String)

  /** Build a bounded code agent.
    *
    * Parse failures are observations and consume one iteration. Execution-domain failures are also observations.
    * Executor infrastructure failures stay in the typed error channel. Extraction runs when the model requests
    * finish after valid code, or after the final allowed iteration.
    */
  def apply[I, O, RG, RX, RE](
      generator    : ProgramWithEnv[StepInput[I], Step, RG],
      executor     : ProgramWithEnv[String, CodeExecutionResult, RX],
      extractor    : ProgramWithEnv[ExtractInput[I], O, RE],
      maxIterations: Int,
      parseCode    : String => Either[DspyError, String] = ProgramOfThought.parseGeneratedPython
  ): ProgramWithEnv[I, O, RG & RX & RE] =
    require(maxIterations > 0, "CodeAct maxIterations must be positive")

    def transition(state: State[I], entry: TrajectoryEntry, stop: Boolean): LoopDecision[State[I], ExtractInput[I]] =
      val trajectory = state.trajectory :+ entry
      val nextIndex  = state.index + 1
      if stop || nextIndex >= maxIterations then LoopDecision.Done(ExtractInput(state.input, trajectory))
      else LoopDecision.Continue(State(state.input, trajectory, nextIndex))

    val generate = generator
      .contramap[State[I]](state => StepInput(state.input, state.trajectory))
      .localWithInput { (state, options) =>
        options.copy(rolloutId = Some(options.rolloutId.getOrElse(0) + state.index))
      }
    val generated = Program.identity[State[I]] &&& generate

    type Decision = LoopDecision[State[I], ExtractInput[I]]
    val prepare = Program.lift[(State[I], Step), Either[Decision, Pending[I]]] { case (state, step) =>
      parseCode(step.generatedCode) match
        case Right(code) => Right(Pending(state, step, code))
        case Left(error) =>
          val entry = TrajectoryEntry(
            iteration = state.index + 1,
            generatedCode = step.generatedCode,
            executedCode = None,
            requestedFinish = step.finished,
            observation = Observation.Failed(error.message)
          )
          Left(transition(state, entry, stop = false))
    }

    val execute = (
      Program.identity[Pending[I]] &&& executor.contramap[Pending[I]](_.code)
    ).map { case (pending, result) =>
      val observation = result match
        case CodeExecutionResult.Succeeded(output) => Observation.Succeeded(output)
        case CodeExecutionResult.Failed(error)     => Observation.Failed(error)
      val entry = TrajectoryEntry(
        iteration = pending.state.index + 1,
        generatedCode = pending.step.generatedCode,
        executedCode = Some(pending.code),
        requestedFinish = pending.step.finished,
        observation = observation
      )
      transition(pending.state, entry, stop = pending.step.finished)
    }

    val step    = generated >>> prepare >>> (Program.identity[Decision] ||| execute)
    val initial = Program.lift[I, State[I]](input => State(input, Vector.empty, 0))

    initial >>> Program.iterate(step, maxIterations) >>> extractor
