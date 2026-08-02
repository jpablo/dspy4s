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
  * `COPRO[P]` needs `OptimizableTraversal[P]` AND `ProgramRunner[P]`. Both are uniform over the packaged type:
  * `OptimizableTraversal[Program[I, O]]` delegates to the packaged parameter projection / reparameterization, and
  * `ProgramRunner[Program[I, O]]` decodes through the domain OBJECT's `RecordCodec[I]` (decoding is
  * object-side; nothing per-program is packaged). So `new COPRO[Program[I, O]](config)` type-checks directly
  * (any `Teleprompter` does), the report is already `Program`-typed, and it works on UPCAST values and on
  * COMPOSED pipelines (`a >>> b`). [[copro]] is retained as call-site sugar; it demands the runner, which
  * exists exactly when the pipeline's input object is codec-equipped.
  */
object ParaCompile:
  extension [I, O](program: Program[I, O])
    /** Run COPRO over a packaged program. Sugar over `new COPRO[Program[I, O]](config)`; the required
      * `ProgramRunner` exists whenever `RecordCodec[I]` does (object-side decoding).
      */
    def copro(
        config: COPROConfig,
        trainset: Vector[Example],
        valset: Option[Vector[Example]] = None
    )(using RuntimeContext, ProgramRunner[Program[I, O]]): Either[DspyError, OptimizationReport[Program[I, O]]] =
      new COPRO[Program[I, O]](config).compile(program, trainset, valset = valset)
