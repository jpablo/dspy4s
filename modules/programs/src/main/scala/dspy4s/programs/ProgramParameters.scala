package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.programs.optimization.OptimizableParameters

/** Generic read/write capability for explicit, stable optimizer parameters. */
trait ProgramParameters[P]:
  def read(program   : P): ParameterStore
  def replace(program: P, values: Map[ParameterId, OptimizableParameters]): Either[DspyError, P]

object ProgramParameters:
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
