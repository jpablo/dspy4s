package dspy4s.programs

import dspy4s.programs.optimization.*
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.AttemptSelection
import dspy4s.typed.Prediction

/** Typed `BestOfN`: runs an inner typed program up to `n` times and keeps the highest-reward `Prediction[O]`,
  * short-circuiting once `rewardFn` reaches `threshold`. Output-preserving — it returns the inner program's `O`
  * unchanged (a `Module[I, O]`), so it composes around any typed program (`Predict`,
  * `ChainOfThought`, …). The repeated samples are made distinct by a per-attempt `rolloutId` (cache-busting);
  * `failCount` bounds tolerated failures before giving up (defaults to `n`).
  *
  * Its lifecycle observation records empty inputs: a generic wrapper has no `Signature` to encode `I` for its own trace
  * entry, so the meaningful inputs live on the nested inner program's event. The best-of-`n` selection loop lives in
  * [[dspy4s.programs.runtime.AttemptSelection.bestOf]] (generic over the attempt result), shared with [[Refine]];
  * `BestOfN` is its independent-samples instance (no inter-attempt feedback).
  *
  * @tparam P
  *   the CONCRETE inner program type (mirroring [[Refine]]), so `I`/`O` infer from it and the pass-through `OptimizableTraversal`
  *   instance ([[BestOfN.bestOfNOptimizableTraversal]]) can delegate to the inner program's own instance; an abstract
  *   `Module[...]` field would erase that evidence.
  */
final case class BestOfN[P <: Module[I, O], I, O](
    module: P,
    n: AttemptCount,
    rewardFn: (I, Prediction[O]) => Double,
    threshold: Double,
    failCount: Option[FailureCount] = None
) extends Module[I, O]:
  override val moduleName: String = "best_of_n"

  override protected val lifecycle: ModuleLifecycle[I, O] =
    ModuleLifecycle.typedWithoutInputs

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val rolloutStart = call.rolloutId.getOrElse(0)
    AttemptSelection.bestOf[Prediction[O]](n, threshold, failCount, moduleName)(
      runAttempt = idx =>
        module
          .mode(Mode.temperature(1.0d) ++ Mode.rolloutId(rolloutStart + idx))(call),
      reward = prediction => AttemptSelection.guardedReward(moduleName)(rewardFn(call.input, prediction))
    )

object BestOfN:
  /** Pass-through addressability (the spec's `selectBest(p)` rule): `BestOfN` wraps without adding any learnable
    * predict of its own, so `inspect` / `replace` / `inspectNamed` delegate to the inner program's instance unchanged.
    */
  given bestOfNOptimizableTraversal[P <: Module[I, O], I, O, N <: Int](using
      inner: OptimizableTraversal.WithArity[P, N]
  ): OptimizableTraversal.Of[BestOfN[P, I, O], N] with
    def arity(program: BestOfN[P, I, O]): Int = inner.arity(program.module)
    def inspect(program: BestOfN[P, I, O]): Vector[OptimizableView] =
      inner.inspect(program.module)

    def replace(program: BestOfN[P, I, O], updates: Vector[OptimizableParameters]): BestOfN[P, I, O] =
      program.copy(module = inner.replace(program.module, updates))

    override def inspectNamed(program: BestOfN[P, I, O]): Vector[(String, OptimizableView)] =
      inner.inspectNamed(program.module)
