package dspy4s.programs

import dspy4s.programs.optimization.*
import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.updated
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmOutput
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.runtime.AttemptSelection
import dspy4s.typed.Prediction
import dspy4s.typed.Shape
import dspy4s.typed.Signature
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema

/** Typed `Refine`: runs an inner typed program up to `n` times (varying `rolloutId` at `temperature=1.0`), keeps the
  * highest-reward `Prediction[O]`, and short-circuits once `rewardFn` reaches `threshold` — the same selection surface
  * as [[BestOfN]] — but, on each sub-threshold attempt that is not the last, it generates LM **advice** grounded in
  * that attempt's trajectory and injects it as a `hint_` input into the next attempt via a
  * [[Refine.HintInjectingAdapter]]. A port of DSPy 3.x's `dspy.Refine` (`OfferFeedback` iterative feedback loop).
  *
  * The advice is produced by an [[Refine.offerFeedbackSignature OfferFeedback]] sub-program (a typed [[Predict]])
  * grounded in the attempt's runtime trace plus the program I/O, the reward value, and the threshold. It is run with
  * the ambient LM/adapter (NOT under the hint adapter), and yields a per-module advice map (component name -> advice)
  * that is routed to the matching predictor of the next attempt via a [[Refine.HintInjectingAdapter]].
  *
  * '''Per-module advice (parity with Python).''' OfferFeedback returns a JSON object `{componentName: advice}` keyed by
  * the inner program's named predictors ([[OptimizableTraversal.inspectNamed]], the dspy4s analogue of `named_predictors()`).
  * Each predictor's call is matched to its advice by its [[SignatureLayout]] — the dspy4s stand-in for Python's
  * `signature2name[signature]` object-identity routing — and only that predictor's `hint_` is injected. A predictor
  * whose advice is absent or `N/A` gets no hint. When OfferFeedback returns a bare (non-JSON) string, it degrades to
  * uniform advice across every component.
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
  *   the inner program type; a typed module (so `I`/`O` infer from it) that is also introspectable for its named
  *   optimizable leaves ([[optimization]]).
  */
final case class Refine[P <: Module[I, O], I, O](
    module: P,
    n: AttemptCount,
    rewardFn: (I, Prediction[O]) => Double,
    threshold: Double,
    failCount: Option[FailureCount] = None,
    /** Optional override for the OfferFeedback critic predict. When `None` (the default), it is built from
      * [[Refine.offerFeedbackSignature]]. Carrying it as a defaulted, `copy`-reachable field makes the critic
      * addressable + immutably replaceable (see [[Refine.refineOptimizableTraversal]]), mirroring the ReAct/CodeAct override
      * pattern.
      */
    criticPredictOverride: Option[Predict[Refine.OfferFeedbackInputs, Refine.OfferFeedbackAdvice]] = None
)(using
    optimization: OptimizableTraversal[P]
) extends Module[I, O]:
  override val moduleName: String = "refine"

  /** The OfferFeedback critic predict, built once (mirrors the `reactPredict` pattern) — a TYPED [[Predict]] over
    * [[Refine.offerFeedbackSignature]] (the critic's shape is fully static). The feedback hook runs this member rather
    * than rebuilding a predict per attempt, so optimizers can tune the critic's instructions/demos like any other
    * learnable. Tunable via [[criticPredictOverride]].
    */
  val criticPredict: Predict[Refine.OfferFeedbackInputs, Refine.OfferFeedbackAdvice] =
    criticPredictOverride.getOrElse(Predict(signature = Refine.offerFeedbackSignature, name = Some("offer_feedback")))

  override protected val lifecycle: ModuleLifecycle[I, O] =
    ModuleLifecycle.typedWithoutInputs

  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val baseContext  = RuntimeEnvironment.current
    val rolloutStart = call.rolloutId.getOrElse(0)
    // Refine is the SEQUENTIAL instance of the shared best-of-`n` reducer: a `feedback` hook turns each
    // sub-threshold attempt into per-module advice, routed (matched by layout) into the next attempt's `hint_`
    // via a HintInjectingAdapter. Selection / failure-budget / trace propagation are the shared `bestOf` loop.
    AttemptSelection.bestOf[Prediction[O]](n, threshold, failCount, moduleName)(
      runAttempt = idx =>
        module
          .mode(Mode.temperature(1.0d) ++ Mode.rolloutId(rolloutStart + idx))
          .apply(call),
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

  /** Convenience entry mirroring the typed caller signature; builds a [[ProgramCall]] and dispatches through the
    * wrapped [[apply]].
    */
  def apply(
      input: I,
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    apply(ProgramCall(input, config, traceEnabled))

object Refine:

  /** Addressability (the spec's `feedback` rule): `read = read(module) ++ [critic]`, the critic LAST. `replace` routes
    * the leading states to the inner program and the trailing state to the critic. An unchanged critic state retains
    * the existing override exactly; a changed state preserves the critic's execution bindings.
    */
  given refineOptimizableTraversal[P <: Module[I, O], I, O](using
      inner: OptimizableTraversal[P]
  ): OptimizableTraversal[Refine[P, I, O]] with
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
        module                = inner.replace(program.module, updates.take(innerArity)),
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
      baseAdapter: Adapter,
      adviceByLayout: Map[SignatureLayout, String]
  ) extends Adapter:
    override def name: String = s"${baseAdapter.name}+hint"

    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      adviceByLayout.get(invocation.layout).map(_.trim).filter(a => a.nonEmpty && a != "N/A") match
        case None => baseAdapter.format(invocation) // no advice routed to this predictor
        case Some(advice) =>
          val hintField = FieldSpec(
            name        = "hint_",
            role        = FieldRole.Input,
            description = Some("A hint to the module from an earlier run")
          )
          val layoutWithHint = invocation.layout.append(hintField)
          val inputsWithHint = invocation.inputs.copy(
            values    = invocation.inputs.values.updated("hint_", DynamicValues.fromAny(advice)),
            inputKeys = invocation.inputs.inputKeys + "hint_"
          )
          baseAdapter.format(invocation.copy(layout = layoutWithHint, inputs = inputsWithHint))

    override def parse(layout: SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      baseAdapter.parse(layout, output)

  /** The OfferFeedback signature layout: a paraphrase of upstream's `OfferFeedback` docstring, with the input fields
    * dspy4s can ground from the runtime (program I/O, the runtime trajectory, reward + threshold, and the
    * `module_names` for which advice is sought) and the `discussion` / `advice` outputs. Per parity, `advice` is a JSON
    * object keyed by module name (`{module_name: advice}`).
    */
  private[programs] val offerFeedbackLayout: SignatureLayout =
    SignatureLayout.create(
      name = "OfferFeedback",
      fields = Vector(
        FieldSpec(
          "program_inputs",
          FieldRole.Input,
          description = Some("The inputs to the program that we are analyzing")
        ),
        FieldSpec(
          "program_trajectory",
          FieldRole.Input,
          description = Some("The trajectory of the program's execution, showing each module's I/O")
        ),
        FieldSpec(
          "program_outputs",
          FieldRole.Input,
          description = Some("The outputs of the program that we are analyzing")
        ),
        FieldSpec(
          "reward_value",
          FieldRole.Input,
          description = Some("The reward value assigned to the program's outputs")
        ),
        FieldSpec(
          "target_threshold",
          FieldRole.Input,
          description = Some("The target threshold for the reward function")
        ),
        FieldSpec(
          "module_names",
          FieldRole.Input,
          description = Some("The names of the modules in the program, for which we seek advice")
        ),
        FieldSpec(
          "discussion",
          FieldRole.Output,
          description = Some("Discussing blame of where each module went wrong, if it did")
        ),
        FieldSpec(
          "advice",
          FieldRole.Output,
          description = Some(
          "A JSON object mapping each module name (from module_names) to concrete, actionable advice for that " +
            "module: the specific scenarios in which it made mistakes and what it should do differently on the " +
            "same or similar inputs in the future. Each module will NOT see its own history, so its advice must be " +
            "entirely self-contained. Use \"N/A\" for a module that is not to blame. Example: " +
            "{\"module_a\": \"...\", \"module_b\": \"N/A\"}."
          )
        )
      ),
      instructions = Some(
        "Assign blame for the final reward being below the threshold to each named module. Then prescribe " +
          "concrete, actionable advice for how each module should act on its future input if it were to receive " +
          "the same or similar inputs on a retry. A module will not see its own history, so it must rely entirely " +
          "on concrete and actionable advice from you to avoid the same mistake. Return the advice as a JSON " +
          "object keyed by module name; if a module is not to blame, its advice should be \"N/A\"."
      )
    ).getOrElse(throw new IllegalStateException("OfferFeedback layout failed to construct"))

  /** The critic's typed input: the six grounding fields of [[offerFeedbackLayout]], field names matching the layout
    * exactly. */
  private[programs] final case class OfferFeedbackInputs(
      program_inputs: String,
      program_trajectory: String,
      program_outputs: String,
      reward_value: Double,
      target_threshold: Double,
      module_names: String
  ) derives Schema

  /** The critic's typed output. `discussion` is prompt-guidance only ([[generateAdvice]] never reads it), which the
    * lenient shape below reflects. */
  private[programs] final case class OfferFeedbackAdvice(discussion: String, advice: String)

  /** Hand-written LENIENT output shape mirroring the prior dynamic consumption exactly: `advice` is required (with
    * `RawPrediction.asString`'s primitive coercion, the accessor the dynamic path used), `discussion` tolerates
    * absence (defaults to ""). A derived shape would reject completions that omit `discussion`, which today's critic
    * consumers accept; `jsonSchemaString` stays `None` for parity with the prior direct `DynamicPredict`
    * construction. */
  private val offerFeedbackOutputShape: Shape[OfferFeedbackAdvice] = new Shape[OfferFeedbackAdvice]:
    val fieldSpecs: Vector[FieldSpec] = offerFeedbackLayout.outputFields
    def encode(value: OfferFeedbackAdvice): DynamicValue.Record =
      DynamicValues.record("discussion" := value.discussion, "advice" := value.advice)
    def decode(raw: DynamicValue.Record): Either[DspyError, OfferFeedbackAdvice] =
      RawPrediction(values = raw).asString("advice").map { advice =>
        OfferFeedbackAdvice(
          discussion = DynamicValues.recordGet(raw, "discussion").map(DynamicValues.renderText).getOrElse(""),
          advice     = advice
        )
      }

  /** The critic's typed signature: the hand-built [[offerFeedbackLayout]] (descriptions + instructions preserved
    * verbatim, so prompt rendering is unchanged) paired with a derived input shape and the lenient output shape. */
  private[programs] val offerFeedbackSignature: Signature[OfferFeedbackInputs, OfferFeedbackAdvice] =
    Signature(
      name        = "OfferFeedback",
      layout      = offerFeedbackLayout,
      inputShape  = Shape.derivedWithRole[OfferFeedbackInputs](FieldRole.Input),
      outputShape = offerFeedbackOutputShape
    )

  /** Render an attempt's runtime [[TraceEntry]] vector as a readable text block — dspy4s's stand-in for Python's
    * source-grounded trajectory. One block per component: `component: <inputs> -> <outputs>`.
    */
  private[programs] def renderTrajectory(trace: Vector[TraceEntry]): String =
    if trace.isEmpty then "(no recorded module calls)"
    else
      trace.map { entry =>
        val inputs  = DynamicValues.renderText(entry.inputs)
        val outputs = DynamicValues.renderText(entry.outputs)
        s"${entry.component}: $inputs -> $outputs"
      }.mkString("\n")

  /** Run the OfferFeedback critic (the instance's addressable [[Refine.criticPredict]], passed in) with the ambient
    * LM/adapter (NOT under the hint adapter) to produce a per-module advice map, grounded in the attempt's trace, the
    * program I/O, the reward value, the threshold, and the `moduleNames` for which advice is sought. The raw `advice`
    * output (a JSON object keyed by module name) is parsed via [[parseAdvice]], which degrades to uniform advice across
    * `moduleNames` for a non-JSON output.
    */
  private[programs] def generateAdvice[I, O](
      critic: Predict[OfferFeedbackInputs, OfferFeedbackAdvice],
      input: I,
      prediction: Prediction[O],
      trace: Vector[TraceEntry],
      reward: Double,
      threshold: Double,
      moduleNames: Vector[String]
  )(using RuntimeContext): Either[DspyError, Map[String, String]] =
    val programInputs = trace.headOption
      .map(e => DynamicValues.renderText(e.inputs))
      .getOrElse(input.toString)
    val programOutputs = DynamicValues.renderText(prediction.raw.values)
    critic.apply(ProgramCall(input = OfferFeedbackInputs(
      program_inputs     = programInputs,
      program_trajectory = renderTrajectory(trace),
      program_outputs    = programOutputs,
      reward_value       = reward,
      target_threshold   = threshold,
      module_names       = moduleNames.mkString(", ")
    ))).map(result => parseAdvice(result.output.advice, moduleNames))

  /** Parse the OfferFeedback `advice` output into a per-module advice map. Faithful path: the output is a JSON object
    * `{module_name: advice}`, decoded leniently (an embedded object is extracted first, tolerating prose or code fences
    * around it). Fallback: a non-JSON output is treated as uniform advice applied to every module (degrading gracefully
    * to the old single-advice behavior, and to the natural single-predictor case).
    */
  private[programs] def parseAdvice(raw: String, moduleNames: Vector[String]): Map[String, String] =
    extractJsonObject(raw).flatMap(decodeStringMap).filter(_.nonEmpty)
      .getOrElse(moduleNames.iterator.map(_ -> raw.trim).toMap)

  /** Extract the first balanced-looking JSON object substring (first `{` to the last `}`), or `None` if absent. */
  private def extractJsonObject(raw: String): Option[String] =
    val start = raw.indexOf('{')
    val end   = raw.lastIndexOf('}')
    if start >= 0 && end > start then Some(raw.substring(start, end + 1)) else None

  /** Decode a JSON object into a `Map[String, String]` via the dynamic codec, rendering each value as text. Returns
    * `None` if the JSON does not decode to an object.
    */
  private def decodeStringMap(json: String): Option[Map[String, String]] =
    Schema.dynamic.jsonCodec.decode(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toOption.collect {
      case record: DynamicValue.Record =>
        record.fields.iterator.map((name, value) => name -> DynamicValues.renderText(value)).toMap
    }
