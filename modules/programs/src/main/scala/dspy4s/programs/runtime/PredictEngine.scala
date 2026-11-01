package dspy4s.programs.runtime

import dspy4s.adapters.AdapterCall
import dspy4s.adapters.DecisionAdapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.LenientValues
import dspy4s.core.contracts.LmDefaults
import dspy4s.core.contracts.PythonText
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
import dspy4s.core.decisions.{DecisionPlan, DecisionSettings}
import dspy4s.core.runtime.ActivePredictContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmRequest
import dspy4s.core.contracts.ToolCall
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ProgramRuntime
import zio.blocks.schema.DynamicValue

/** Shared execution body for the predict module: pushes the `ActivePredict` scope, resolves the model + adapter, builds
  * the invocation, runs [[AdapterCall.run]] (format → call → parse, the fallback, and the adapter / lm callback
  * events), and assembles the prediction. A System One model ([[LanguageModel.supportsDecisionRequests]]) gets a
  * decision request with no adapter instead (see [[DecisionAdapter.decide]]).
  *
  * This is the raw engine -- module-level lifecycle (the `withModule` callback scope and trace/history recording) is
  * added by `Module.apply`. Two sibling `Module`s call this engine in their `forward`: the record-valued
  * `DynamicPredict` (`Module[DynamicValue.Record, DynamicValue.Record]`) and the `Predict[I, O]` (`Module[I, O]`, which
  * encodes/decodes around `execute`). Neither wraps the other, so a call emits exactly one module event.
  */
private[dspy4s] final case class PredictEngine(
    layout    : SignatureLayout,
    demos     : Vector[Example],
    moduleName: String,
    runtime   : ProgramRuntime,
    /** Optional JSON Schema of the output as a JSON object. Populated by the [[dspy4s.programs.strategies.Predict]]
      * path (which has a `Schema[O]` to render via `Shape.jsonSchema`); left `None` by
      * [[dspy4s.programs.strategies.DynamicPredict]]. Passed straight through to [[AdapterInvocation]]; adapters that
      * understand it inline the schema in their prompt instruction.
      */
    outputJsonSchema: Option[DynamicValue.Record] = None,
    /** Module-level LM option bag, the analogue of Python's `dspy.Predict(signature, **config)` `self.config`. Merged
      * *under* the per-call [[ProgramCall.config]] in [[requestOptions]] (per-call keys win on collision), so it
      * supplies defaults a call may override. It is merged *over* the options of `RuntimeScope.lmDefaults`, so it wins
      * over the temperature of an attempt of `BestOfN`.
      */
    config: DynamicValue.Record,
    /** Optional per-module bound LM (Python's `set_lm`/`get_lm`). When set, it is used in preference to the ambient
      * `RuntimeContext` LM (`runtime.resolveModel`), so different predictors in one program can pin different models.
      * `None` falls back to ambient resolution. See PORT_GAPS G-3.
      */
    lm: Option[LanguageModel],
    /** Tool schemas surfaced to the adapter via [[AdapterInvocation.tools]]. Only an adapter with native
      * function-calling enabled (and a `tool_calls` output field in the layout) acts on them; others ignore them. See
      * PORT_GAPS G-7b.
      */
    tools: Vector[ToolSpec],
    /** Experimental. The explicit settings of the decision outputs, by output name (upstream `Predict.fields`). The
      * engine builds the [[DecisionPlan]] of the layout with them. See [[DecisionAdapter.resolve]].
      */
    decisions: Map[String, DecisionSettings],
    /** Experimental. The output fields whose type holds a decision type inside an `Option`, a collection, or a map
      * ([[dspy4s.signatures.Shape.nestedDecisionFields]]). Each call warns that they get no evidence decoding.
      */
    nestedDecisionFields: Vector[String] = Vector.empty
):
  // The layout is immutable for the engine's lifetime, so the declared-input key set is computed once, not per call.
  private val inputKeys: Set[String] = layout.inputFields.map(_.name).toSet

  // The layout and the settings are immutable for the engine's lifetime, so the plans are computed once.
  private lazy val decisionPlan: Either[DspyError, DecisionPlan]  = DecisionPlan.of(layout, decisions)
  private lazy val systemOnePlan: Either[DspyError, DecisionPlan] = DecisionPlan.forSystemOne(layout, decisions)

  def execute(call: ProgramCall[DynamicValue.Record])(using context: RuntimeContext): Either[DspyError, RawPrediction] =
    // `Module.apply` gives the effective context, whose scope is the scope of this thread.
    val lmDefaults = context.scope.lmDefaults
    val rolloutId  = call.rolloutId.orElse(lmDefaults.flatMap(_.rolloutId))
    ActivePredictContext.withActive(moduleName, layout) {
      lm.fold(runtime.resolveModel)(Right(_)).flatMap { model =>
        warnOnInputMismatch(call)
        val options = requestOptions(call, model, lmDefaults)
        if model.supportsDecisionRequests then
          for
            plan   <- systemOnePlan
            values <- DecisionAdapter.decide(
                        plan,
                        layout,
                        demos,
                        moduleName,
                        model,
                        call.input,
                        options,
                        rolloutId
                      )
            prediction <- buildPrediction(Vector(values), Vector.empty)
          yield prediction
        else
          for
            base       <- runtime.resolveAdapter
            adapter    <- DecisionAdapter.resolve(base, decisionPlan, layout, nestedDecisionFields, moduleName, model)
            answer     <- AdapterCall.run(adapter, model, buildInvocation(call, model, options, rolloutId))
            _           = answer.parsed.map(_.evidence).foreach(DecisionAdapter.recordEvidence(moduleName, layout, _))
            prediction <- buildPrediction(
                            answer.parsed.map(_.values),
                            answer.response.outputs.headOption.map(_.toolCalls).getOrElse(Vector.empty)
                          )
          yield prediction
      }
    }

  private def buildInvocation(
      call     : ProgramCall[DynamicValue.Record],
      model    : LanguageModel,
      options  : DynamicValue.Record,
      rolloutId: Option[Int]
  ): AdapterInvocation =
    AdapterInvocation(
      layout = layout,
      demos = demos,
      inputs = Example(values = call.input, inputKeys = inputKeys),
      outputJsonSchema = outputJsonSchema,
      tools = tools,
      request = LmRequest(
        model = model.id,
        options = options,
        rolloutId = rolloutId
      )
    )

  /** The options of the model request: the options of `lmDefaults`, then [[config]], then the per-call config. A later
    * record wins. `lmDefaults` is the analog of the kwargs of an upstream LM copy (`lm.copy(temperature=1.0)`).
    *
    * The temperature rule of upstream `_forward_preprocess` (`dspy/predict/predict.py`) applies: when the temperature
    * is not set or is at most 0.15, and `n` is above 1, the temperature becomes 0.7 to keep randomness. Each number
    * comes from the merge of [[config]] and the per-call config, else from `lmDefaults`, else from the defaults of
    * `model` ([[LanguageModel.defaultOptions]]; for `n`, also its `num_generations`). As with the Python `or`, a value
    * of 0 counts as not set at each step. When the rule does not start, the merged options go out unchanged, so a
    * config temperature of 0 stays 0 in an attempt.
    */
  private def requestOptions(
      call      : ProgramCall[DynamicValue.Record],
      model     : LanguageModel,
      lmDefaults: Option[LmDefaults]
  ): DynamicValue.Record =
    def number(record: DynamicValue.Record, key: String): Option[Double] =
      DynamicValues.recordGet(record, key).flatMap(LenientValues.toDouble).filter(_ != 0.0)

    val own       = DynamicValues.mergeRecords(config, call.config)
    val fromScope = lmDefaults.fold(DynamicValue.Record.empty)(_.options)
    val fromModel = model.defaultOptions

    def read(key: String): Option[Double] =
      number(own, key).orElse(number(fromScope, key)).orElse(number(fromModel, key))

    val options     = DynamicValues.mergeRecords(fromScope, own)
    val temperature = read("temperature")
    val generations = read("n").orElse(number(fromModel, "num_generations"))
    if temperature.forall(_ <= 0.15) && generations.exists(_ > 1) then
      DynamicValues.mergeRecords(options, DynamicValues.record("temperature" := 0.7))
    else options

  /** The warnings of upstream `_forward_preprocess` (`dspy/predict/predict.py`), with its texts. The call still
    * proceeds: these are diagnostics, not errors. One-off diagnostics are not callback events (those are strictly
    * Start/End scope pairs), so this routes through [[RuntimeEnvironment.warn]].
    *
    *   - A key of the input that is not a declared input field. The adapter drops it, because [[AdapterInvocation]] has
    *     `inputKeys` restricted to the layout.
    *   - A declared input field with no value that is not optional (upstream `annotation_allows_none`). A field with a
    *     default counts as present, because upstream fills the defaults first. `Predict` has its own check for a
    *     missing input before this engine, thus only `DynamicPredict` gets this warning.
    */
  private def warnOnInputMismatch(call: ProgramCall[DynamicValue.Record]): Unit =
    val provided  = DynamicValues.recordKeys(call.input)
    val fields    = layout.inputFields
    val extra     = provided.filterNot(inputKeys.contains)
    val component = s"Predict '${layout.name}'"
    if extra.nonEmpty then
      RuntimeEnvironment.warn(
        component,
        s"Input contains fields not in signature. These fields will be ignored: ${PythonText.nameList(extra)}. " +
          s"Expected fields: ${PythonText.nameList(fields.map(_.name))}."
      )
    val (present, absent) = fields.partition(field => provided.contains(field.name) || field.defaultValue.isDefined)
    val missing           = absent.filterNot(_.optional)
    if missing.nonEmpty then
      RuntimeEnvironment.warn(
        component,
        s"Not all input fields were provided to module. Present: ${PythonText.nameList(present.map(_.name))}. " +
          s"Missing: ${PythonText.nameList(missing.map(_.name))}."
      )

  /** The prediction of the output `rows` (one row for each completion). It has no usage: `Module.apply` gives the
    * result the total of the usage scope of the call, thus each model call of the call counts, also a call whose reply
    * failed to parse before the JSON fallback (decision D9 of UPGRADE-3.2.1-to-3.4.0).
    *
    * Native `toolCalls` give the prediction one more value, [[ToolCall.ToolCallsKey]] (upstream: a `tool_calls` value
    * exists only on tool turns). [[dspy4s.programs.Aggregation.majority]] skips it in its "last output field" default.
    */
  private def buildPrediction(
      rows     : Vector[DynamicValue.Record],
      toolCalls: Vector[ToolCall]
  ): Either[DspyError, RawPrediction] =
    RawPrediction.fromRows(rows).map { first =>
      if toolCalls.isEmpty then first
      else first.withValue(ToolCall.ToolCallsKey, NativeFunctionCalling.encodeToolCalls(toolCalls))
    }
