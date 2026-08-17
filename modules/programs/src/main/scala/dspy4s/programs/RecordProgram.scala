package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.signatures.Shape
import zio.blocks.schema.DynamicValue

/** A typed program with one explicit dynamic-record input boundary.
  *
  * Core composition does not need a record codec. Evaluation and optimization do, because their datasets use dynamic
  * records. Keeping the codec in this wrapper prevents every custom composite from needing a hand-written runner.
  */
type RecordProgram[I, O] = RecordProgramWithEnv[I, O, PredictionBackend]

final case class RecordProgramWithEnv[I, O, R](program: ProgramWithEnv[I, O, R], inputShape: Shape[I]):

  def updatedParameter(
      id   : ParameterId,
      value: OptimizableParameters
  ): Either[DspyError, RecordProgramWithEnv[I, O, R]] =
    program.updatedParameter(id, value).map(updated => copy(program = updated))

  def updatedParameter(
      ref  : ParameterRef,
      value: OptimizableParameters
  ): Either[DspyError, RecordProgramWithEnv[I, O, R]] =
    program.updatedParameter(ref, value).map(updated => copy(program = updated))

  def modifyParameter(id: ParameterId)(
      update: OptimizableParameters => OptimizableParameters
  ): Either[DspyError, RecordProgramWithEnv[I, O, R]] =
    program.modifyParameter(id)(update).map(updated => copy(program = updated))

  def modifyParameter(ref: ParameterRef)(
      update: OptimizableParameters => OptimizableParameters
  ): Either[DspyError, RecordProgramWithEnv[I, O, R]] =
    program.modifyParameter(ref)(update).map(updated => copy(program = updated))

  def replaceParameters(
      values: Map[ParameterId, OptimizableParameters]
  ): Either[DspyError, RecordProgramWithEnv[I, O, R]] =
    program.replaceParameters(values).map(updated => copy(program = updated))

  def loadParameterState(state: DynamicValue.Record): Either[DspyError, RecordProgramWithEnv[I, O, R]] =
    program.loadParameterState(state).map(updated => copy(program = updated))
