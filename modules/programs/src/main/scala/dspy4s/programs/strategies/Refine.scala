package dspy4s.programs.strategies

import dspy4s.programs.{AttemptCount, FailureCount}
import dspy4s.programs.optimization.*
import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.DelegatingAdapter
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.SignatureOps.*
import dspy4s.algebra.Lens
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.AttemptSelection
import dspy4s.programs.runtime.SettingsProgramRuntime
import dspy4s.programs.contracts.Prediction

import scala.compiletime.ops.int.+

/** `Refine`: runs an inner program up to `n` times (varying `rolloutId` at `temperature=1.0`), keeps the highest-reward
  * `Prediction[O]`, and short-circuits once `rewardFn` reaches `threshold` — the same selection surface as [[BestOfN]]
  * — but, on each sub-threshold attempt that is not the last, it generates LM **advice** grounded in that attempt's
  * trajectory and injects it as a `hint_` input into the next attempt via a [[Refine.HintInjectingAdapter]]. A port of
  * DSPy 3.x's `dspy.Refine` (`OfferFeedback` iterative feedback loop).
  *
  * The advice is produced by an [[RefineFeedback.signature OfferFeedback]] sub-program (a [[Predict]]) grounded in the
  * attempt's runtime trace plus the program I/O, the reward value, and the threshold. It is run with the ambient
  * LM/adapter (NOT under the hint adapter), and yields a per-module advice map (component name -> advice) that is
  * routed to the matching predictor of the next attempt via a [[Refine.HintInjectingAdapter]].
  *
  * '''Per-module advice (parity with Python).''' OfferFeedback returns a JSON object `{componentName: advice}` keyed by
  * the inner program's named predictors ([[OptimizableStructure.inspectNamed]], the dspy4s analogue of
  * `named_predictors()`). Each predictor's call is matched to its advice by its [[SignatureLayout]] — the dspy4s
  * stand-in for Python's `signature2name[signature]` object-identity routing — and only that predictor's `hint_` is
  * injected. A predictor whose advice is absent or `N/A` gets no hint. When OfferFeedback returns a bare (non-JSON)
  * string, it degrades to uniform advice across every component.
  *
  * Like [[BestOfN]], the winning attempt's isolated trace/history are propagated to the caller; `failCount` bounds
  * tolerated failures before giving up (defaults to `n`).
  *
  * The LM usage of the result is the total of each attempt, of each OfferFeedback call, and of the model calls of
  * `rewardFn`, not only of the selected attempt (decision D9 of UPGRADE-3.2.1-to-3.4.0; `Module.apply` opens the usage
  * scope). Upstream runs all of them in the usage tracker of the `Refine` call (`dspy/predict/refine.py`,
  * `dspy/primitives/module.py`).
  *
  * ==Deltas from Python==
  *   - '''Trace-grounded, not source-grounded.''' Grounding is the runtime TRACE + I/O, not the program's / reward
  *     function's SOURCE CODE: dspy4s has no source introspection, so Python's `program_code`, `reward_code`, and
  *     `inspect_modules` `modules_defn` inputs are omitted (the per-module I/O the trace already records covers the
  *     trajectory).
  *   - '''Layout-keyed routing.''' Python routes advice by `signature` object identity; dspy4s routes by
  *     [[SignatureLayout]] value equality. Two predictors with structurally identical layouts therefore collapse to one
  *     advice entry — acceptable, since identical layouts also yield identical advice. The match ignores the
  *     instructions: the view of a `ChainOfThought` has the instructions of its base signature, and its inner predict
  *     has the effective instructions of that base signature.
  *
  * @tparam P
  *   the inner program type; a module (so `I`/`O` infer from it) that is also introspectable for its named optimizable
  *   leaves ([[optimization]]).
  */
final case class Refine[P <: Module[I, O], I, O](
    module   : P,
    n        : AttemptCount,
    rewardFn : (I, Prediction[O]) => Double,
    threshold: Double,
    failCount: Option[FailureCount] = None,
    /** Optional override for the OfferFeedback critic predict. When `None` (the default), it is built from
      * [[RefineFeedback.signature]]. Carrying it as a defaulted, `copy`-reachable field makes the critic addressable +
      * immutably replaceable (see [[Refine.refineOptimizableStructure]]), mirroring the ReAct override pattern.
      */
    criticPredictOverride: Option[RefineFeedback.Critic] = None
)(using
    optimization: OptimizableStructure[P]
) extends Module[I, O]:
  override val moduleName: String = "refine"

  /** The OfferFeedback critic predict, built once (mirrors the `reactPredict` pattern) — a [[Predict]] over
    * [[RefineFeedback.signature]] (the critic's shape is fully static). The feedback hook runs this member rather than
    * rebuilding a predict per attempt, so optimizers can tune the critic's instructions/demos like any other learnable.
    * Tunable via [[criticPredictOverride]].
    */
  val criticPredict: RefineFeedback.Critic =
    criticPredictOverride.getOrElse(Predict(signature = RefineFeedback.signature, name = Some("offer_feedback")))

  override protected val lifecycle: ModuleLifecycle[I, O] = ModuleLifecycle.typedWithoutInputs

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    // Refine is the SEQUENTIAL instance of the shared best-of-`n` reducer: a `feedback` hook turns each
    // sub-threshold attempt into per-module advice, routed (matched by layout) into the next attempt's `hint_`
    // via a HintInjectingAdapter. Selection / failure-budget / trace propagation are the shared `bestOf` loop.
    AttemptSelection.sample(
      module,
      call,
      rewardFn,
      n,
      threshold,
      failCount,
      moduleName,
      feedback = Some { (prediction, trace, score) => base ?=>
        // Generate per-module advice grounded in this attempt's trajectory + I/O + reward (under the ambient
        // LM/adapter, NOT the hint adapter), then build the next attempt's adapter routing each predictor's OWN
        // advice into ITS `hint_`. Auxiliary: a generation failure charges the budget and keeps `best` (handled
        // by `bestOf`). `base` has the call scope of this module, and the next attempt needs that scope.
        val named = optimization.inspectNamed(module)
        RefineFeedback
          .generateAdvice(criticPredict, module.observedInputs(call), prediction, trace, score, threshold, named)
          .map { adviceMap =>
            val byLayout = named.iterator.map { case (name, view) =>
              view.layout -> adviceMap.getOrElse(name, "N/A")
            }.toMap
            val hint = Refine.HintInjectingAdapter(SettingsProgramRuntime.adapterOrChat(base), byLayout)
            base.withServices(base.services.copy(adapter = Some(hint)))
          }
      }
    )

object Refine:

  /** Addressability (the spec's `feedback` rule): `read = read(module) ++ [critic]`, the critic LAST. `replace` routes
    * the leading states to the inner program and the trailing state to the critic. An unchanged critic state retains
    * the existing override exactly; a changed state preserves the critic's execution bindings.
    */
  given refineOptimizableStructure[P <: Module[I, O], I, O, N <: Int](using
      inner: OptimizableStructure.WithArity[P, N]
  ): OptimizableStructure.Of[Refine[P, I, O], N + 1] =
    val children = new Lens[Refine[P, I, O], (P, RefineFeedback.Critic)]:
      def get(program: Refine[P, I, O]): (P, RefineFeedback.Critic) = program.module -> program.criticPredict

      def set(program: Refine[P, I, O], replacement: (P, RefineFeedback.Critic)): Refine[P, I, O] =
        val nextCriticOverride =
          if replacement._2.equals(program.criticPredict) then program.criticPredictOverride
          else Some(replacement._2)
        program.copy(module = replacement._1, criticPredictOverride = nextCriticOverride)

    val critic = ParameterOptic.leaf[RefineFeedback.Critic](
      "self",
      _.optimizableView,
      (current, updated) => current.withOptimizableParameters(updated)
    )

    ParameterOptic.toStructure(
      "Refine",
      ParameterOptic.pairThrough(
        children,
        ParameterOptic.fromStructure(inner),
        critic,
        None,
        Some("critic")
      )
    )

  /** Wrapper [[Adapter]] that injects each predictor's OWN advice into ITS call, mirroring Python's `WrapperAdapter`
    * (`inputs["hint_"] = advice.get(signature2name[signature], "N/A")`). The predictor is identified by its
    * [[SignatureLayout]] (dspy4s's stand-in for Python's `signature` object identity); `adviceByLayout` maps each
    * predictor's layout to its advice. `format` looks up the invocation's layout: on a real (non-`N/A`, non-empty)
    * advice it appends a `hint_` INPUT field carrying that advice and delegates to `baseAdapter.format`; otherwise (no
    * advice for this predictor) it delegates unchanged. Each member other than `name` and `format` comes from the base
    * adapter ([[DelegatingAdapter]]). The lookup compares the fields and the name of the layouts, and not their
    * instructions.
    */
  private[programs] final case class HintInjectingAdapter(
      baseAdapter   : Adapter,
      adviceByLayout: Map[SignatureLayout, String]
  ) extends DelegatingAdapter:
    override def delegate: Adapter = baseAdapter

    override def name: String = s"${baseAdapter.name}+hint"

    private val adviceByStructure: Map[SignatureLayout, String] =
      adviceByLayout.map((layout, advice) => layout.withInstructions(None) -> advice)

    /** The fallback of the base adapter gets the same advice (upstream `WrapperAdapter` calls the base adapter, which
      * does its own fallback).
      */
    override protected def wrap(next: Adapter): Adapter = copy(baseAdapter = next)

    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      adviceByStructure.get(invocation.layout.withInstructions(None)).map(_.trim)
        .filter(a => a.nonEmpty && a != "N/A") match
        case None         => baseAdapter.format(invocation) // no advice routed to this predictor
        case Some(advice) =>
          val hintField = FieldSpec(
            name = "hint_",
            description = Some("A hint to the module from an earlier run")
          )
          val layoutWithHint = invocation.layout.appendInput(hintField)
          val inputsWithHint =
            invocation.inputs.withRawValue("hint_", advice).copy(inputKeys = invocation.inputs.inputKeys + "hint_")
          baseAdapter.format(invocation.copy(layout = layoutWithHint, inputs = inputsWithHint))
