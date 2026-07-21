package dspy4s.programs

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.core.data.DynamicPrediction
import dspy4s.programs.contracts.{DynamicModule, Module, ProgramCall}
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue

/** Capability for evaluating a program from the dynamic record boundary.
  *
  * Optimizers, evaluation, and streaming all consume this same boundary: a record-valued [[ProgramCall]] goes in and
  * the program's raw [[DynamicPrediction]] comes out. The capability is external to `Module` because record decoding is
  * not available on every typed module representation; third-party programs can supply their own instance.
  */
trait ProgramRunner[P]:
  def run(program: P, call: ProgramCall[DynamicValue.Record])(using
      RuntimeContext
  ): Either[DspyError, DynamicPrediction]

  final def run(program: P, inputs: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    run(program, ProgramCall(inputs))

object ProgramRunner:

  /** Dynamic modules already inhabit the record boundary. */
  given fromDynamicModule[P <: DynamicModule]: ProgramRunner[P] with
    def run(program: P, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, DynamicPrediction] =
      program.apply(call)

  /** A typed module is runnable whenever its record decoder is available. The decoded input is mapped into the same
    * call envelope, preserving config, trace selection, and rollout identity; the typed result is erased to `.raw`.
    */
  given fromTypedModule[I, O, P <: Module[ProgramCall[I], Prediction[O]]](using
      input: ProgramInput[P, I]
  ): ProgramRunner[P] with
    def run(program: P, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, DynamicPrediction] =
      input.decoder(program)(call.input).flatMap { decoded =>
        program.apply(call.mapInput(_ => decoded)).map(_.raw)
      }

  def apply[P](using runner: ProgramRunner[P]): ProgramRunner[P] = runner
