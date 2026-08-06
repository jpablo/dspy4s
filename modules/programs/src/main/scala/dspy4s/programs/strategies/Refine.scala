package dspy4s.programs.strategies

import dspy4s.programs.{AttemptCount, FailureCount}
import dspy4s.programs.compose.{Mode, mode}
import dspy4s.programs.optimization.*
import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.SignatureOps.*
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.updated
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmOutput
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.AttemptSelection
import dspy4s.programs.contracts.Prediction
import dspy4s.signatures.Signature
import zio.blocks.schema.Schema

import scala.compiletime.ops.int.+

/** `Refine`: runs an inner program up to `n` times (varying `rolloutId` at `temperature=1.0`), keeps the highest-reward
  * `Prediction[O]`, and short-circuits once `rewardFn` reaches `threshold` — the same selection surface as [[BestOfN]]
  * — but, on each sub-threshold attempt that is not the last, it generates LM **advice** grounded in that attempt's
  * trajectory and injects it as a `hint_` input into the next attempt via a [[Refine.HintInjectingAdapter]]. A port of
  * DSPy 3.x's `dspy.Refine` (`OfferFeedback` iterative feedback loop).
  *
  * The advice is produced by an [[Refine.offerFeedbackSignature OfferFeedback]] sub-program (a [[Predict]]) grounded in
  * the attempt's runtime trace plus the program I/O, the reward value, and the threshold. It is run with the ambient
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
  * ==Deltas from Python==
  *   - '''Trace-grounded, not source-grounded.''' Grounding is the runtime TRACE + I/O, not the program's / reward
  *     function's SOURCE CODE: dspy4s has no source introspection, so Python's `program_code`, `reward_code`, and
  *     `inspect_modules` `modules_defn` inputs are omitted (the per-module I/O the trace already records covers the
  *     trajectory).
  *   - '''Layout-keyed routing.''' Python routes advice by `signature` object identity; dspy4s routes by
  *     [[SignatureLayout]] value equality. Two predictors with structurally identical layouts therefore collapse to one
  *     advice entry — acceptable, since identical layouts also yield identical advice.
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
      * [[Refine.offerFeedbackSignature]]. Carrying it as a defaulted, `copy`-reachable field makes the critic
      * addressable + immutably replaceable (see [[Refine.refineOptimizableStructure]]), mirroring the ReAct/CodeAct
      * override pattern.
      */
    criticPredictOverride: Option[Predict[Refine.OfferFeedbackInputs, Refine.OfferFeedbackAdvice]] = None
)(using
    optimization: OptimizableStructure[P]
) extends Module[I, O]:
  override val moduleName: String = "refine"

  /** The OfferFeedback critic predict, built once (mirrors the `reactPredict` pattern) — a [[Predict]] over
    * [[Refine.offerFeedbackSignature]] (the critic's shape is fully static). The feedback hook runs this member rather
    * than rebuilding a predict per attempt, so optimizers can tune the critic's instructions/demos like any other
    * learnable. Tunable via [[criticPredictOverride]].
    */
  val criticPredict: Predict[Refine.OfferFeedbackInputs, Refine.OfferFeedbackAdvice] =
    criticPredictOverride.getOrElse(Predict(signature = Refine.offerFeedbackSignature, name = Some("offer_feedback")))

  override protected val lifecycle: ModuleLifecycle[I, O] = ModuleLifecycle.typedWithoutInputs

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val baseContext  = RuntimeEnvironment.current
    val rolloutStart = call.rolloutId.getOrElse(0)
    // Refine is the SEQUENTIAL instance of the shared best-of-`n` reducer: a `feedback` hook turns each
    // sub-threshold attempt into per-module advice, routed (matched by layout) into the next attempt's `hint_`
    // via a HintInjectingAdapter. Selection / failure-budget / trace propagation are the shared `bestOf` loop.
    AttemptSelection.bestOf[Prediction[O]](n, threshold, failCount, moduleName)(
      runAttempt = idx =>
        module
          .mode(Mode.temperature(1.0d) ++ Mode.rolloutId(rolloutStart + idx))(call),
      reward = prediction => AttemptSelection.guardedReward(moduleName)(rewardFn(call.input, prediction)),
      feedback = Some { (prediction, trace, score) =>
        // Generate per-module advice grounded in this attempt's trajectory + I/O + reward (under the ambient
        // LM/adapter, NOT the hint adapter), then build the next attempt's adapter routing each predictor's OWN
        // advice into ITS `hint_`. Auxiliary: a generation failure charges the budget and keeps `best` (handled
        // by `bestOf`).
        val named       = optimization.inspectNamed(module)
        val moduleNames = named.map(_._1)
        Refine.generateAdvice(criticPredict, call.input, prediction, trace, score, threshold, moduleNames)(using
          baseContext
        )
          .map { adviceMap =>
            val byLayout = named.iterator.map { case (name, view) =>
              view.layout -> adviceMap.getOrElse(name, "N/A")
            }.toMap
            Some(Refine.HintInjectingAdapter(Refine.resolveBaseAdapter(baseContext), byLayout))
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
  ): OptimizableStructure.Of[Refine[P, I, O], N + 1] with
    def arity(program: Refine[P, I, O]): Int                       = inner.arity(program.module) + 1
    def inspect(program: Refine[P, I, O]): Vector[OptimizableView] =
      inner.inspect(program.module) :+ program.criticPredict.optimizableView

    def replace(program: Refine[P, I, O], updates: Vector[OptimizableParameters]): Refine[P, I, O] =
      val innerArity = inner.read(program.module).size
      require(
        updates.size == innerArity + 1,
        s"Refine expects ${innerArity + 1} updates (inner predicts ++ critic), got ${updates.size}"
      )
      val nextCritic =
        if updates(innerArity) == program.criticPredict.optimizableParameters then program.criticPredictOverride
        else Some(program.criticPredict.withOptimizableParameters(updates(innerArity)))
      program.copy(
        module = inner.replace(program.module, updates.take(innerArity)),
        criticPredictOverride = nextCritic
      )

    override def inspectNamed(program: Refine[P, I, O]): Vector[(String, OptimizableView)] =
      inner.inspectNamed(program.module) :+ ("critic" -> program.criticPredict.optimizableView)

  /** Resolve the base adapter from the ambient context, narrowing the `AdapterRef` to a concrete [[Adapter]]; falls
    * back to a default [[ChatAdapter]] when none is configured (mirrors Python's `dspy.settings.adapter or
    * dspy.ChatAdapter()`).
    */
  private[programs] def resolveBaseAdapter(context: RuntimeContext): Adapter =
    context.adapter match
      case Some(adapter: Adapter) => adapter
      case _                      => ChatAdapter()

  /** Wrapper [[Adapter]] that injects each predictor's OWN advice into ITS call, mirroring Python's `WrapperAdapter`
    * (`inputs["hint_"] = advice.get(signature2name[signature], "N/A")`). The predictor is identified by its
    * [[SignatureLayout]] (dspy4s's stand-in for Python's `signature` object identity); `adviceByLayout` maps each
    * predictor's layout to its advice. `format` looks up the invocation's layout: on a real (non-`N/A`, non-empty)
    * advice it appends a `hint_` INPUT field carrying that advice and delegates to `baseAdapter.format`; otherwise (no
    * advice for this predictor) it delegates unchanged. `parse` is always unchanged.
    */
  private[programs] final case class HintInjectingAdapter(
      baseAdapter   : Adapter,
      adviceByLayout: Map[SignatureLayout, String]
  ) extends Adapter:
    override def name: String = s"${baseAdapter.name}+hint"

    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      adviceByLayout.get(invocation.layout).map(_.trim).filter(a => a.nonEmpty && a != "N/A") match
        case None         => baseAdapter.format(invocation) // no advice routed to this predictor
        case Some(advice) =>
          val hintField = FieldSpec(
            name = "hint_",
            description = Some("A hint to the module from an earlier run")
          )
          val layoutWithHint = invocation.layout.appendInput(hintField)
          val inputsWithHint = invocation.inputs.copy(
            values = invocation.inputs.values.updated("hint_", DynamicValues.fromAny(advice)),
            inputKeys = invocation.inputs.inputKeys + "hint_"
          )
          baseAdapter.format(invocation.copy(layout = layoutWithHint, inputs = inputsWithHint))

    override def parse(layout: SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      baseAdapter.parse(layout, output)

  /** The critic's input: the six grounding fields of [[RefineFeedback.layout]], field names matching the layout
    * exactly.
    */
  private[programs] final case class OfferFeedbackInputs(
      program_inputs    : String,
      program_trajectory: String,
      program_outputs   : String,
      reward_value      : Double,
      target_threshold  : Double,
      module_names      : String
  ) derives Schema

  /** The critic's output. `discussion` is prompt-guidance only ([[generateAdvice]] never reads it), which the lenient
    * shape below reflects.
    */
  private[programs] final case class OfferFeedbackAdvice(discussion: String, advice: String)

  /** The critic's signature: the hand-built [[RefineFeedback.layout]] (descriptions + instructions preserved verbatim,
    * so prompt rendering is unchanged) paired with a derived input shape and the lenient output shape.
    */
  private[programs] val offerFeedbackSignature: Signature[OfferFeedbackInputs, OfferFeedbackAdvice] =
    RefineFeedback.signature

  /** Run the OfferFeedback critic (the instance's addressable [[Refine.criticPredict]], passed in) with the ambient
    * LM/adapter (NOT under the hint adapter) to produce a per-module advice map, grounded in the attempt's trace, the
    * program I/O, the reward value, the threshold, and the `moduleNames` for which advice is sought. The raw `advice`
    * output (a JSON object keyed by module name) is parsed via [[parseAdvice]], which degrades to uniform advice across
    * `moduleNames` for a non-JSON output.
    */
  private[programs] def generateAdvice[I, O](
      critic     : Predict[OfferFeedbackInputs, OfferFeedbackAdvice],
      input      : I,
      prediction : Prediction[O],
      trace      : Vector[TraceEntry],
      reward     : Double,
      threshold  : Double,
      moduleNames: Vector[String]
  )(using RuntimeContext): Either[DspyError, Map[String, String]] =
    RefineFeedback.generateAdvice(critic, input, prediction, trace, reward, threshold, moduleNames)

  /** Parse the OfferFeedback `advice` output into a per-module advice map. Faithful path: the output is a JSON object
    * `{module_name: advice}`, decoded leniently (an embedded object is extracted first, tolerating prose or code fences
    * around it). Fallback: a non-JSON output is treated as uniform advice applied to every module (degrading gracefully
    * to the old single-advice behavior, and to the natural single-predictor case).
    */
  private[programs] def parseAdvice(raw: String, moduleNames: Vector[String]): Map[String, String] =
    RefineFeedback.parseAdvice(raw, moduleNames)
