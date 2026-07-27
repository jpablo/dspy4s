package dspy4s.optimize

import dspy4s.programs.ProgramRunner

import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.evaluate.Evaluate
import dspy4s.evaluate.contracts.Metric
import dspy4s.programs.predictors.PredictorTraversal

/** Cross-optimizer helpers shared by the teleprompter family (COPRO, MIPROv2, GroundedProposer). Kept in one place so
  * the seed→rolloutId mapping and the Evaluate+ProgramRunner scoring wiring stay identical across optimizers.
  */
private[optimize] object OptimizerSupport:

  /** Apply `instruction` to the `idx`-th predictor of `program` (an instruction-only edit) via
    * [[dspy4s.programs.predictors.PredictorTraversal.replace]]. The single home for the per-leaf instruction rewrite shared by the
    * positional instruction optimizers (COPRO, InferRules).
    */
  def applyInstruction[P](program: P, idx: Int, instruction: String)(using ps: PredictorTraversal[P]): P =
    val leaves = ps.read(program)
    val leaf   = leaves(idx)
    ps.replace(program, leaves.updated(idx, leaf.copy(instructions = Some(instruction))))

  /** Map an optimizer `seed` to a base `rolloutId` in `[0, 1024)`. Optimizers offset this base to carve out
    * deterministic, non-overlapping rolloutId windows per predictor / round / candidate, so candidate sampling is
    * reproducible across runs while distinct calls still vary the selector. Shared so every optimizer derives the same
    * base from a given seed.
    */
  def seedBase(seed: Long): Int = math.floorMod(seed.toInt, 1024)

  /** Run `program` on `evalset` with `metric` via `runner`, returning the aggregate score (0..100), or `None` when the
    * WHOLE evaluation fails (timeout / maxErrors exceeded). A failed eval is "unknown", not "scored zero" — callers
    * that select a best candidate must not silently collapse `None` into `0.0` (MIPROv2 chooses `0.0` for a
    * non-selectable trial; COPRO keeps it as `None`).
    */
  def evalScore[P](program: P, evalset: Vector[Example], metric: Metric, runner: ProgramRunner[P])(using
      RuntimeContext
  ): Option[Double] =
    Evaluate(devset = evalset, metric = metric)()((ex: Example) => runner.run(program, ex.inputs)) match
      case Right(r) => Some(r.score)
      case Left(_)  => None
