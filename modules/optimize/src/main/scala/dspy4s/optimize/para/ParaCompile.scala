package dspy4s.optimize.para

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.optimize.COPRO
import dspy4s.optimize.COPROConfig
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.programs.ProgramRunner
import dspy4s.programs.algebra.Program

/** A packaged [[Program]] as the optimizer entry point.
  *
  * `COPRO[P]` needs `OptimizableStructure[P]` and `ProgramRunner[P]`. The packaged entry point therefore retains
  * `Program[I, O, N]`: optimizer access uses the parameter grade retained by `N`, while the runner separately requires
  * whatever capability can enter the program's input boundary (normally `RecordCodec[I]`).
  */
object ParaCompile:
  extension [I, O, N <: Int](program: Program[I, O, N])
    /** Run COPRO over a packaged program while preserving its static parameter arity. The usual `ProgramRunner`
      * instance exists whenever `RecordCodec[I]` does (record-boundary decoding).
      */
    def copro(
        config  : COPROConfig,
        trainset: Vector[Example],
        valset  : Option[Vector[Example]] = None
    )(using
        RuntimeContext,
        ProgramRunner[Program[I, O, N]]
    ): Either[DspyError, OptimizationReport[Program[I, O, N]]] =
      new COPRO[Program[I, O, N]](config).compile(program, trainset, valset = valset)
