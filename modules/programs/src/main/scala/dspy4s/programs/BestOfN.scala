package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.updated
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.AttemptSelection
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue

/** Typed `BestOfN`: runs an inner typed program up to `n` times and keeps the highest-reward `Prediction[O]`,
  * short-circuiting once `rewardFn` reaches `threshold`. Output-preserving — it returns the inner program's `O`
  * unchanged (a `Module[ProgramCall[I], Prediction[O]]`), so it composes around any typed program (`Predict`,
  * `ChainOfThought`, …). The repeated samples are made distinct by a per-attempt `rolloutId` (cache-busting);
  * `failCount` bounds tolerated failures before giving up (defaults to `n`).
  *
  * Its lifecycle observation records empty inputs: a generic wrapper has no `Signature` to encode `I` for its own trace
  * entry, so the meaningful inputs live on the nested inner program's event. The best-of-`n` selection loop lives in
  * [[dspy4s.programs.runtime.AttemptSelection.bestOf]] (generic over the attempt result), shared with [[Refine]];
  * `BestOfN` is its independent-samples instance (no inter-attempt feedback).
  *
  * @tparam P
  *   the CONCRETE inner program type (mirroring [[Refine]]), so `I`/`O` infer from it and the pass-through `Predictors`
  *   instance ([[BestOfN.bestOfNPredictors]]) can delegate to the inner program's own instance; an abstract
  *   `Module[...]` field would erase that evidence.
  */
final case class BestOfN[P <: Module[ProgramCall[I], Prediction[O]], I, O](
    module: P,
    n: Int,
    rewardFn: (I, Prediction[O]) => Double,
    threshold: Double,
    failCount: Option[Int] = None
) extends Module[ProgramCall[I], Prediction[O]]:
  require(n > 0, "n must be greater than 0")

  override val moduleName: String = "best_of_n"

  override protected val lifecycle: ModuleLifecycle[ProgramCall[I], Prediction[O]] =
    ModuleLifecycle.typedWithoutInputs

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val rolloutStart = call.rolloutId.getOrElse(0)
    AttemptSelection.bestOf[Prediction[O]](n, threshold, failCount, moduleName)(
      runAttempt = idx =>
        module.apply(call.copy(
          rolloutId = Some(rolloutStart + idx),                                  // framework cache-busting selector
          config    = call.config.updated("temperature", DynamicValues.fromAny(1.0d))  // provider knob
        )),
      reward = prediction => AttemptSelection.guardedReward(moduleName)(rewardFn(call.input, prediction))
    )

  /** Convenience entry mirroring the typed caller signature; builds a [[ProgramCall]] and dispatches through the
    * wrapped [[apply]].
    */
  def apply(
      input: I,
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    apply(ProgramCall(input, config, traceEnabled))

object BestOfN:
  /** Pass-through addressability (the spec's `selectBest(p)` rule): `BestOfN` wraps without adding any learnable
    * predict of its own, so `inspect` / `replace` / `inspectNamed` delegate to the inner program's instance unchanged.
    */
  given bestOfNPredictors[P <: Module[ProgramCall[I], Prediction[O]], I, O](using
      inner: Predictors[P]
  ): Predictors[BestOfN[P, I, O]] with
    def inspect(program: BestOfN[P, I, O]): Vector[PredictorView] =
      inner.inspect(program.module)

    def replace(program: BestOfN[P, I, O], updates: Vector[PredictorState]): BestOfN[P, I, O] =
      program.copy(module = inner.replace(program.module, updates))

    override def inspectNamed(program: BestOfN[P, I, O]): Vector[(String, PredictorView)] =
      inner.inspectNamed(program.module)
