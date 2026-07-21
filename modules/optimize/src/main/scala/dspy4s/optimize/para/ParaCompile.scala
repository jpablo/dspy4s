package dspy4s.optimize.para

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.optimize.COPRO
import dspy4s.optimize.COPROConfig
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.programs.para.Program

/** PROTOTYPE: a packaged [[Program]] as the optimizer entry point, loop CLOSED (the earlier revision of this file
  * surfaced the finding; this one resolves it; see the "Para formalization" section of
  * `docs/refactor/algebra-2-program-composition.md`).
  *
  * The original finding: Para evidence alone was not enough to optimize. `COPRO[P]` needs `Predictors[P]` AND
  * `ProgramRunner[P]`; the first was packaged, the second had to be summoned against the packaging-refined
  * `program.Rep`, so it died under upcasts and did not exist for composed pipelines at all.
  *
  * The close: [[Program]] now also packages `decodeInput` (captured at `Program.of` time, threaded through
  * composition), which makes BOTH capabilities uniform over the packaged type:
  *
  *   - `Predictors[Program[I, O]]` (in the `Program` companion): `read` / `replace` delegate to the packaged evidence
  *     (Para projection / reparameterization).
  *   - `ProgramRunner[Program[I, O]]` (in the `Program` companion): decode via the packaged decoder, run, return
  *     `.raw`.
  *
  * So `Program[I, O]` is a first-class optimizable program: `new COPRO[Program[I, O]](config)` type-checks directly
  * (any `Teleprompter` does, not just COPRO), the report is already `Program`-typed with no re-packaging, and it works
  * on UPCAST values and on COMPOSED pipelines (`a >>> b`), which the ambient `Module` world cannot run from records at
  * all (bare user composites hand-write a `ProgramRunner`; `ProgramRunner`'s scaladoc documents that gap). [[copro]] is
  * retained as call-site sugar.
  */
object ParaCompile:
  extension [I, O](program: Program[I, O])
    /** Run COPRO over a packaged program. Sugar over `new COPRO[Program[I, O]](config)`, which resolves its
      * `Predictors` and `ProgramRunner` context bounds from the packaged evidence alone; no capability needs the
      * packaging-refined type, so this works on upcast and composed `Program`s.
      */
    def copro(
        config: COPROConfig,
        trainset: Vector[Example],
        valset: Option[Vector[Example]] = None
    )(using RuntimeContext): Either[DspyError, OptimizationReport[Program[I, O]]] =
      new COPRO[Program[I, O]](config).compile(program, trainset, valset = valset)
