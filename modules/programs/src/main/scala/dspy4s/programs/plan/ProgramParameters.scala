package dspy4s.programs.plan

import dspy4s.core.contracts.DspyError
import dspy4s.programs.optimization.{OptimizableParameters, OptimizableStructure}

/** Generic read/write capability for optimizer parameters.
  *
  * New programs expose explicit stable IDs. The low-priority legacy bridge derives temporary ordinal IDs from an
  * `OptimizableStructure`, so current optimizer clients can move to this capability before old module trees disappear.
  */
trait ProgramParameters[P]:
  def read(program   : P): ParameterStore
  def replace(program: P, values: Map[ParameterId, OptimizableParameters]): Either[DspyError, P]

private[plan] trait LowPriorityProgramParameters:
  given fromOptimizableStructure[P](using structure: OptimizableStructure[P]): ProgramParameters[P] with
    def read(program: P): ParameterStore =
      val bindings = structure.inspect(program).zipWithIndex.map { case (view, index) =>
        ParameterBinding(ParameterId(s"legacy/$index"), view.metadata, view.parameters)
      }
      ParameterStore.fromBindings(bindings)

    def replace(program: P, values: Map[ParameterId, OptimizableParameters]): Either[DspyError, P] =
      read(program).replace(values).map(store => structure.replace(program, store.all.map(_.value)))

object ProgramParameters extends LowPriorityProgramParameters:
  def apply[P](using parameters: ProgramParameters[P]): ProgramParameters[P] = parameters

  given program[I, O, R]: ProgramParameters[ProgramWithEnv[I, O, R]] with
    def read(program: ProgramWithEnv[I, O, R]): ParameterStore = program.parameters

    def replace(
        program: ProgramWithEnv[I, O, R],
        values : Map[ParameterId, OptimizableParameters]
    ): Either[DspyError, ProgramWithEnv[I, O, R]] =
      program.replaceParameters(values)

  given recordProgram[I, O, R]: ProgramParameters[RecordProgramWithEnv[I, O, R]] with
    def read(program: RecordProgramWithEnv[I, O, R]): ParameterStore = program.program.parameters

    def replace(
        program: RecordProgramWithEnv[I, O, R],
        values : Map[ParameterId, OptimizableParameters]
    ): Either[DspyError, RecordProgramWithEnv[I, O, R]] =
      program.program.replaceParameters(values).map(updated => program.copy(program = updated))
