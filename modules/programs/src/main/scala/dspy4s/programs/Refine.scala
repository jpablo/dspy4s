package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.HistoryEntry
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.updated
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmOutput
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema

/** Typed `Refine`: runs an inner typed program up to `n` times (varying `rolloutId` at `temperature=1.0`), keeps
  * the highest-reward `Prediction[O]`, and short-circuits once `rewardFn` reaches `threshold` — the same selection
  * surface as [[BestOfN]] — but, on each sub-threshold attempt that is not the last, it generates LM **advice**
  * grounded in that attempt's trajectory and injects it as a `hint_` input into the next attempt via a
  * [[Refine.HintInjectingAdapter]]. A port of DSPy 3.x's `dspy.Refine` (`OfferFeedback` iterative feedback loop).
  *
  * The advice is produced by an [[Refine.offerFeedbackLayout OfferFeedback]] sub-program (a [[DynamicPredict]])
  * grounded in the attempt's runtime trace plus the program I/O, the reward value, and the threshold. It is run
  * with the ambient LM/adapter (NOT under the hint adapter), and yields a per-module advice map (component name ->
  * advice) that is routed to the matching predictor of the next attempt via a [[Refine.HintInjectingAdapter]].
  *
  * '''Per-module advice (parity with Python).''' OfferFeedback returns a JSON object `{componentName: advice}`
  * keyed by the inner program's named predictors ([[Predictors.readNamed]], the dspy4s analogue of
  * `named_predictors()`). Each predictor's call is matched to its advice by its [[SignatureLayout]] — the dspy4s
  * stand-in for Python's `signature2name[signature]` object-identity routing — and only that predictor's `hint_`
  * is injected. A predictor whose advice is absent or `N/A` gets no hint. When OfferFeedback returns a bare
  * (non-JSON) string, it degrades to uniform advice across every component.
  *
  * Like [[BestOfN]], the winning attempt's isolated trace/history are propagated to the caller; `failCount` bounds
  * tolerated failures before giving up (defaults to `n`).
  *
  * ==Deltas from Python==
  *   - '''Trace-grounded, not source-grounded.''' Grounding is the runtime TRACE + I/O, not the program's /
  *     reward function's SOURCE CODE: dspy4s has no source introspection, so Python's `program_code`,
  *     `reward_code`, and `inspect_modules` `modules_defn` inputs are omitted (the per-module I/O the trace
  *     already records covers the trajectory).
  *   - '''Layout-keyed routing.''' Python routes advice by `signature` object identity; dspy4s routes by
  *     [[SignatureLayout]] value equality. Two predictors with structurally identical layouts therefore collapse
  *     to one advice entry — acceptable, since identical layouts also yield identical advice.
  *
  * @tparam P the inner program type; a typed module (so `I`/`O` infer from it) that is also introspectable for its
  *           named predictors ([[predictors]]).
  */
final case class Refine[P <: Module[TypedCall[I], Prediction[O]], I, O](
    module: P,
    n: Int,
    rewardFn: (I, Prediction[O]) => Double,
    threshold: Double,
    failCount: Option[Int] = None
)(using
    predictors: Predictors[P]
) extends Module[TypedCall[I], Prediction[O]]:
  require(n > 0, "n must be greater than 0")

  override val moduleName: String = "refine"

  override protected def callInputs(call: TypedCall[I]): DynamicValue.Record = DynamicValue.Record.empty
  override protected def callTraceEnabled(call: TypedCall[I]): Boolean       = call.traceEnabled
  override protected def tracePayload(prediction: Prediction[O]): DynamicValue.Record = prediction.raw.values

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    val baseContext       = RuntimeEnvironment.current
    val rolloutStart      = call.rolloutId.getOrElse(0)
    var remainingFailures = failCount.getOrElse(n)
    var bestReward        = Double.NegativeInfinity
    var best: Option[Prediction[O]]  = None
    var bestTrace         = Vector.empty[TraceEntry]
    var bestHistory       = Vector.empty[HistoryEntry]
    var lastError: Option[DspyError] = None
    // The per-module advice map (component name -> advice) carried from a sub-threshold attempt into the next one
    // (None on the first attempt).
    var advice: Option[Map[String, String]] = None

    var idx = 0
    while idx < n do
      // Each attempt runs in an isolated trace/history context (like BestOfN.selectBest). On retry attempts where
      // advice exists, swap the context's adapter to a HintInjectingAdapter that injects each predictor's OWN advice
      // (matched by its layout) into that predictor's `hint_`.
      val attemptAdapter = advice.map { adviceMap =>
        val byLayout = predictors.readNamed(module).iterator.map { case (name, predict) =>
          predict.layout -> adviceMap.getOrElse(name, "N/A")
        }.toMap
        Refine.HintInjectingAdapter(Refine.resolveBaseAdapter(baseContext), byLayout)
      }
      val isolated = baseContext.copy(
        trace   = Vector.empty,
        history = Vector.empty,
        adapter = attemptAdapter.orElse(baseContext.adapter)
      )
      val (attemptResult, trace, history) = RuntimeEnvironment.withContext(isolated) {
        given RuntimeContext = RuntimeEnvironment.current
        val result  = module.apply(call.copy(
          rolloutId = Some(rolloutStart + idx),                                          // cache-busting selector
          config    = call.config.updated("temperature", DynamicValues.fromAny(1.0d))    // provider knob
        ))
        val current = RuntimeEnvironment.current
        (result, current.trace, current.history)
      }

      attemptResult match
        case Right(prediction) =>
          BestOfN.guardedReward(moduleName)(rewardFn(call.input, prediction)) match
            case Left(error) => return Left(error)
            case Right(score) =>
              if score > bestReward then
                bestReward  = score
                best        = Some(prediction)
                bestTrace   = trace
                bestHistory = history

              if score >= threshold then
                idx = n // short-circuit at threshold
              else if idx == n - 1 then
                idx += 1 // last attempt; no feedback to generate
              else
                // Generate per-module advice grounded in this attempt's trajectory + I/O + reward, for the next
                // attempt. Advice is auxiliary: a failure here must not discard `best`. Mirror the module-failure
                // path — record the error, charge the failure budget, and continue without new advice.
                val moduleNames = predictors.readNamed(module).map(_._1)
                Refine.generateAdvice(call.input, prediction, trace, score, threshold, moduleNames)(using baseContext) match
                  case Right(adviceMap) => advice = Some(adviceMap)
                  case Left(error) =>
                    lastError = Some(error)
                    if idx > remainingFailures then return Left(error)
                    remainingFailures -= 1
                idx += 1

        case Left(error) =>
          lastError = Some(error)
          if idx > remainingFailures then return Left(error)
          remainingFailures -= 1
          idx += 1

    best match
      case Some(value) =>
        bestTrace.foreach(RuntimeEnvironment.appendTrace)
        bestHistory.foreach(RuntimeEnvironment.appendHistory)
        Right(value)
      case None =>
        Left(lastError.getOrElse(RuntimeError(moduleName, "No successful predictions were produced")))

  /** Convenience entry mirroring the typed caller signature; builds a [[TypedCall]] and dispatches through the
    * wrapped [[apply]]. */
  def apply(
      input: I,
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[O]] =
    apply(TypedCall(input, config, traceEnabled))

object Refine:

  /** Resolve the base adapter from the ambient context, narrowing the `AdapterRef` to a concrete [[Adapter]];
    * falls back to a default [[ChatAdapter]] when none is configured (mirrors Python's
    * `dspy.settings.adapter or dspy.ChatAdapter()`). */
  private[programs] def resolveBaseAdapter(context: RuntimeContext): Adapter =
    context.adapter match
      case Some(adapter: Adapter) => adapter
      case _                      => ChatAdapter()

  /** Wrapper [[Adapter]] that injects each predictor's OWN advice into ITS call, mirroring Python's `WrapperAdapter`
    * (`inputs["hint_"] = advice.get(signature2name[signature], "N/A")`). The predictor is identified by its
    * [[SignatureLayout]] (dspy4s's stand-in for Python's `signature` object identity); `adviceByLayout` maps each
    * predictor's layout to its advice. `format` looks up the invocation's layout: on a real (non-`N/A`, non-empty)
    * advice it appends a `hint_` INPUT field carrying that advice and delegates to `baseAdapter.format`; otherwise
    * (no advice for this predictor) it delegates unchanged. `parse` is always unchanged. */
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

  /** The OfferFeedback signature layout: a paraphrase of upstream's `OfferFeedback` docstring, with the input
    * fields dspy4s can ground from the runtime (program I/O, the runtime trajectory, reward + threshold, and the
    * `module_names` for which advice is sought) and the `discussion` / `advice` outputs. Per parity, `advice` is a
    * JSON object keyed by module name (`{module_name: advice}`). */
  private[programs] val offerFeedbackLayout: SignatureLayout =
    SignatureLayout.create(
      name = "OfferFeedback",
      fields = Vector(
        FieldSpec("program_inputs", FieldRole.Input, description = Some("The inputs to the program that we are analyzing")),
        FieldSpec("program_trajectory", FieldRole.Input, description = Some("The trajectory of the program's execution, showing each module's I/O")),
        FieldSpec("program_outputs", FieldRole.Input, description = Some("The outputs of the program that we are analyzing")),
        FieldSpec("reward_value", FieldRole.Input, description = Some("The reward value assigned to the program's outputs")),
        FieldSpec("target_threshold", FieldRole.Input, description = Some("The target threshold for the reward function")),
        FieldSpec("module_names", FieldRole.Input, description = Some("The names of the modules in the program, for which we seek advice")),
        FieldSpec("discussion", FieldRole.Output, description = Some("Discussing blame of where each module went wrong, if it did")),
        FieldSpec("advice", FieldRole.Output, description = Some(
          "A JSON object mapping each module name (from module_names) to concrete, actionable advice for that " +
            "module: the specific scenarios in which it made mistakes and what it should do differently on the " +
            "same or similar inputs in the future. Each module will NOT see its own history, so its advice must be " +
            "entirely self-contained. Use \"N/A\" for a module that is not to blame. Example: " +
            "{\"module_a\": \"...\", \"module_b\": \"N/A\"}."
        ))
      ),
      instructions = Some(
        "Assign blame for the final reward being below the threshold to each named module. Then prescribe " +
          "concrete, actionable advice for how each module should act on its future input if it were to receive " +
          "the same or similar inputs on a retry. A module will not see its own history, so it must rely entirely " +
          "on concrete and actionable advice from you to avoid the same mistake. Return the advice as a JSON " +
          "object keyed by module name; if a module is not to blame, its advice should be \"N/A\"."
      )
    ).getOrElse(throw new IllegalStateException("OfferFeedback layout failed to construct"))

  /** Render an attempt's runtime [[TraceEntry]] vector as a readable text block — dspy4s's stand-in for Python's
    * source-grounded trajectory. One block per component: `component: <inputs> -> <outputs>`. */
  private[programs] def renderTrajectory(trace: Vector[TraceEntry]): String =
    if trace.isEmpty then "(no recorded module calls)"
    else
      trace.map { entry =>
        val inputs  = DynamicValues.renderText(entry.inputs)
        val outputs = DynamicValues.renderText(entry.outputs)
        s"${entry.component}: $inputs -> $outputs"
      }.mkString("\n")

  /** Run the OfferFeedback sub-program with the ambient LM/adapter (NOT under the hint adapter) to produce a
    * per-module advice map, grounded in the attempt's trace, the program I/O, the reward value, the threshold, and
    * the `moduleNames` for which advice is sought. The raw `advice` output (a JSON object keyed by module name) is
    * parsed via [[parseAdvice]], which degrades to uniform advice across `moduleNames` for a non-JSON output. */
  private[programs] def generateAdvice[I, O](
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
    val feedback = DynamicPredict(offerFeedbackLayout, name = Some("offer_feedback"))
    feedback.apply(ProgramCall(inputs = DynamicValues.record(
      "program_inputs"     := programInputs,
      "program_trajectory" := renderTrajectory(trace),
      "program_outputs"    := programOutputs,
      "reward_value"       := reward,
      "target_threshold"   := threshold,
      "module_names"       := moduleNames.mkString(", ")
    ))).flatMap(_.asString("advice")).map(parseAdvice(_, moduleNames))

  /** Parse the OfferFeedback `advice` output into a per-module advice map. Faithful path: the output is a JSON
    * object `{module_name: advice}`, decoded leniently (an embedded object is extracted first, tolerating prose or
    * code fences around it). Fallback: a non-JSON output is treated as uniform advice applied to every module
    * (degrading gracefully to the old single-advice behavior, and to the natural single-predictor case). */
  private[programs] def parseAdvice(raw: String, moduleNames: Vector[String]): Map[String, String] =
    extractJsonObject(raw).flatMap(decodeStringMap).filter(_.nonEmpty)
      .getOrElse(moduleNames.iterator.map(_ -> raw.trim).toMap)

  /** Extract the first balanced-looking JSON object substring (first `{` to the last `}`), or `None` if absent. */
  private def extractJsonObject(raw: String): Option[String] =
    val start = raw.indexOf('{')
    val end   = raw.lastIndexOf('}')
    if start >= 0 && end > start then Some(raw.substring(start, end + 1)) else None

  /** Decode a JSON object into a `Map[String, String]` via the dynamic codec, rendering each value as text. Returns
    * `None` if the JSON does not decode to an object. */
  private def decodeStringMap(json: String): Option[Map[String, String]] =
    Schema.dynamic.jsonCodec.decode(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toOption.collect {
      case record: DynamicValue.Record =>
        record.fields.iterator.map((name, value) => name -> DynamicValues.renderText(value)).toMap
    }
