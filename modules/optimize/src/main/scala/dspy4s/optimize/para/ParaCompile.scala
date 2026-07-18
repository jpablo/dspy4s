package dspy4s.optimize.para

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.optimize.COPRO
import dspy4s.optimize.COPROConfig
import dspy4s.optimize.Runnable
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.programs.contracts.TypedCall
import dspy4s.programs.para.Prog
import zio.blocks.schema.DynamicValue

/** PROTOTYPE: a packaged [[Prog]] as the optimizer entry point, loop CLOSED (the earlier revision of this
  * file surfaced the finding; this one resolves it; see the "Para formalization" section of
  * `docs/refactor/algebra-2-program-composition.md`).
  *
  * The original finding: Para evidence alone was not enough to optimize. `COPRO[P]` needs `Predictors[P]`
  * AND `Runnable[P]`; the first was packaged, the second had to be summoned against the packaging-refined
  * `prog.Rep`, so it died under upcasts and did not exist for composed pipelines at all.
  *
  * The close: [[Prog]] now also packages `decodeInput` (captured at `Prog.of` time, threaded through
  * composition), which makes BOTH capabilities uniform over the packaged type:
  *
  *   - `Predictors[Prog[I, O]]` (in the `Prog` companion): `read` / `replace` delegate to the packaged
  *     evidence (Para projection / reparameterization).
  *   - [[progRunnable]] `Runnable[Prog[I, O]]` (here): decode via the packaged decoder, run, return `.raw`.
  *
  * So `Prog[I, O]` is a first-class optimizable program: `new COPRO[Prog[I, O]](config)` type-checks
  * directly (any `Teleprompter` does, not just COPRO), the report is already `Prog`-typed with no
  * re-packaging, and it works on UPCAST values and on COMPOSED pipelines (`a >>> b`), which the ambient
  * `Module` world cannot run from records at all (bare user composites hand-write a `Runnable`;
  * `Runnable`'s scaladoc documents that gap). [[copro]] is retained as call-site sugar. */
object ParaCompile:

  /** Uniform record-based evaluation for any packaged program: decode via the packaged `decodeInput`, run
    * the program, hand back the raw prediction. The evaluation-functor half of the Para package. */
  given progRunnable[I, O]: Runnable[Prog[I, O]] with
    def run(program: Prog[I, O], inputs: DynamicValue.Record)(using
        RuntimeContext
    ): Either[DspyError, DynamicPrediction] =
      program.decodeInput(inputs).flatMap(i => program.apply(TypedCall(i)).map(_.raw))

  extension [I, O](prog: Prog[I, O])
    /** Run COPRO over a packaged program. Sugar over `new COPRO[Prog[I, O]](config)`, which resolves its
      * `Predictors` and `Runnable` context bounds from the packaged evidence alone; no capability needs the
      * packaging-refined type, so this works on upcast and composed `Prog`s. */
    def copro(
        config: COPROConfig,
        trainset: Vector[Example],
        valset: Option[Vector[Example]] = None
    )(using RuntimeContext): Either[DspyError, OptimizationReport[Prog[I, O]]] =
      new COPRO[Prog[I, O]](config).compile(prog, trainset, valset = valset)
