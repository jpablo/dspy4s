package dspy4s.optimize.para

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.optimize.COPRO
import dspy4s.optimize.COPROConfig
import dspy4s.optimize.Runnable
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.programs.para.Prog

/** PROTOTYPE: a packaged [[Prog]] as the optimizer entry point (the ergonomics probe for promoting the Para
  * category to the public optimize API; see the "Para formalization" section of
  * `docs/refactor/algebra-2-program-composition.md`).
  *
  * The pleasant part: the packaged evidence IS the instance the generic optimizer needs. `COPRO[P]` demands
  * `Predictors[P]` and `Runnable[P]`; a `Prog` carries the first, so the entry point is a path-dependent
  * instantiation `new COPRO[prog.Rep](config)(using prog.addressable, runnable)` and a re-packaging of the
  * report. No optimizer internals change.
  *
  * The finding this prototype exists to surface: **Para evidence alone is not enough to optimize.** Running a
  * candidate against examples needs [[Runnable]] (decode a `DynamicValue.Record` into the typed input and
  * run), which `Prog` does not package. It therefore arrives as a `using Runnable[prog.Rep]` on the entry
  * point, and that is resolvable ONLY while the caller still holds the packaging-refined type
  * (`Prog[I, O] { type Rep = Predict[I, O] }`): upcast to bare `Prog[I, O]` and `Rep` goes abstract, so the
  * instance cannot be summoned, even though `params` / `reparam` keep working (the Para operations survive
  * the upcast; evaluation does not). Composition has the same hole: `f >>> g` packages an `AndThen`, which
  * has no `Runnable`. The design conclusion, recorded in the spec: full Para adoption should package the
  * evaluation capability too, most naturally as an input decoder captured at `Prog.of` time (composition
  * would thread the first leg's decoder, which would also give composed pipelines the `Runnable` that bare
  * user composites must hand-write today). */
object ParaCompile:

  extension [I, O](prog: Prog[I, O])
    /** Run COPRO over a packaged program, returning a report of packaged programs. `Runnable[prog.Rep]` must
      * be summonable at the call site, which requires the packaging-refined type (see the object scaladoc). */
    def copro(
        config: COPROConfig,
        trainset: Vector[Example],
        valset: Option[Vector[Example]] = None
    )(using
        runnable: Runnable[prog.Rep],
        ctx: RuntimeContext
    ): Either[DspyError, OptimizationReport[Prog[I, O]]] =
      val optimizer = new COPRO[prog.Rep](config)(using prog.addressable, runnable)
      optimizer.compile(prog.program, trainset, valset = valset).map { report =>
        OptimizationReport(
          bestProgram = Prog.of[I, O, prog.Rep](report.bestProgram)(using prog.addressable),
          candidates = report.candidates.map { candidate =>
            CandidateProgram(
              program    = Prog.of[I, O, prog.Rep](candidate.program)(using prog.addressable),
              score      = candidate.score,
              evaluation = candidate.evaluation,
              metadata   = candidate.metadata
            )
          },
          metadata = report.metadata
        )
      }
