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
  * `COPRO[P]` needs `OptimizableTraversal[P]` and `ProgramRunner[P]`. The packaged entry point therefore retains
  * `Program.WithArity[I, O, N]`: the runner depends only on the codec-equipped input object, while optimizer traversal
  * additionally requires the parameter shape that a plain `Program[I, O]` has erased.
  */
object ParaCompile:
  extension [I, O, N <: Int](program: Program.WithArity[I, O, N])
    /** Run COPRO over a packaged program while preserving its static parameter arity. The required `ProgramRunner`
      * exists whenever `RecordCodec[I]` does (object-side decoding).
      */
    def copro(
        config: COPROConfig,
        trainset: Vector[Example],
        valset: Option[Vector[Example]] = None
    )(using
        RuntimeContext,
        ProgramRunner[Program.WithArity[I, O, N]]
    ): Either[DspyError, OptimizationReport[Program.WithArity[I, O, N]]] =
      new COPRO[Program.WithArity[I, O, N]](config).compile(program, trainset, valset = valset)
