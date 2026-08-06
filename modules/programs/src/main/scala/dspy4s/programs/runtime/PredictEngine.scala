package dspy4s.programs.runtime

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.data.Completions
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.ActivePredictContext
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.core.contracts.ToolCall
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ProgramRuntime
import zio.blocks.schema.DynamicValue

/** Shared execution body for the predict module: pushes the `ActivePredict` scope, resolves the model + adapter, runs
  * format → call → parse → prediction assembly, and dispatches the adapter / lm callback events along the way.
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
    /** Optional pre-rendered JSON Schema for the output. Populated by the [[dspy4s.programs.strategies.Predict]] path
      * (which has a `Schema[O]` to render via `Shape.jsonSchemaString`); left `None` by
      * [[dspy4s.programs.strategies.DynamicPredict]]. Passed straight through to [[AdapterInvocation]]; adapters that
      * understand it inline the schema in their prompt instruction.
      */
    outputJsonSchema: Option[String] = None,
    /** Module-level LM option bag, the analogue of Python's `dspy.Predict(signature, **config)` `self.config`. Merged
      * *under* the per-call [[ProgramCall.config]] in [[buildInvocation]] (per-call keys win on collision), so it
      * supplies defaults a call may override. Empty by default, in which case the merged options are exactly the
      * per-call config.
      */
    config: DynamicValue.Record = DynamicValue.Record.empty,
    /** Optional per-module bound LM (Python's `set_lm`/`get_lm`). When set, it is used in preference to the ambient
      * `RuntimeContext` LM (`runtime.resolveModel`), so different predictors in one program can pin different models.
      * `None` (the default) falls back to ambient resolution. See PORT_GAPS G-3.
      */
    lm: Option[LanguageModel] = None,
    /** Tool schemas surfaced to the adapter via [[AdapterInvocation.tools]]. Only an adapter with native
      * function-calling enabled (and a `tool_calls` output field in the layout) acts on them; others ignore them. Empty
      * by default. See PORT_GAPS G-7b.
      */
    tools: Vector[ToolSpec] = Vector.empty
):
  // The layout is immutable for the engine's lifetime, so the declared-input key set is computed once, not per call.
  private val inputKeys: Set[String] = layout.inputFields.map(_.name).toSet

  def execute(call: ProgramCall[DynamicValue.Record])(using RuntimeContext): Either[DspyError, RawPrediction] =
    ActivePredictContext.withActive(moduleName, layout) {
      for
        model     <- lm.fold(runtime.resolveModel)(Right(_))
        adapter   <- runtime.resolveAdapter
        invocation = buildInvocation(call, model)
        prompt    <- CallbackDispatcher.withAdapter(
                    adapterName = adapter.name,
                    inputs = DynamicValues.record("phase" := "format", "signature" := layout.name)
                  ) {
                    adapter.format(invocation)
                  }
        response <- CallbackDispatcher.withLm(
                      modelId = model.id,
                      request = DynamicValues.record("model" := model.id, "mode" := model.mode.toString)
                    ) {
                      // G-7: merge the adapter-contributed request options (e.g. `response_format`) UNDER the existing
                      // request options, so explicit per-call/module config wins on key collision.
                      val mergedOptions =
                        FormattedPrompt.mergeOptions(prompt.requestOptions, invocation.request.options)
                      model.call(invocation.request.copy(messages = prompt.messages, options = mergedOptions))
                    }
        parsed     <- parseOutputs(adapter, response.outputs)
        prediction <-
          buildPrediction(parsed, response, response.outputs.headOption.map(_.toolCalls).getOrElse(Vector.empty))
      yield prediction
    }

  private def buildInvocation(call: ProgramCall[DynamicValue.Record], model: LanguageModel): AdapterInvocation =
    warnOnExtraInputs(call)
    AdapterInvocation(
      layout = layout,
      demos = demos,
      inputs = Example(values = call.input, inputKeys = inputKeys),
      outputJsonSchema = outputJsonSchema,
      tools = tools,
      request = LmRequest(
        model = model.id,
        mode = model.mode,
        options = mergeConfig(config, call.config),
        rolloutId = call.rolloutId
      )
    )

  /** Merge the module-level `config` with the per-call config so that per-call keys win on collision: start from the
    * module config and upsert each per-call field by name (later wins, preserving insertion order via the `updated`
    * extension). Mirrors Python's `{**self.config, **call_kwargs}`. When module `config` is empty the result is exactly
    * `callConfig`, so behavior is unchanged for callers that don't set a module config.
    *
    * (Deferred: a per-module bound LM — Python's `set_lm`/`get_lm` — is intentionally not handled here; the LM is
    * resolved from the ambient `RuntimeContext` via `runtime.resolveModel`. See PORT_GAPS G-3.)
    */
  private def mergeConfig(moduleConfig: DynamicValue.Record, callConfig: DynamicValue.Record): DynamicValue.Record =
    DynamicValues.mergeRecords(moduleConfig, callConfig)

  /** Mirror upstream dspy 3.2.1 `predict.py` `_forward_preprocess`: input keys that are not declared input fields are
    * tolerated (the extras are dropped downstream, since [[AdapterInvocation]] is built with `inputKeys` restricted to
    * the layout), but their presence is surfaced as a warning naming the unexpected keys and the expected fields. The
    * call still proceeds -- this is a diagnostic, not an error. One-off diagnostics are not callback events (those are
    * strictly Start/End scope pairs), so this routes through [[RuntimeEnvironment.warn]].
    */
  private def warnOnExtraInputs(call: ProgramCall[DynamicValue.Record]): Unit =
    val extra = DynamicValues.recordKeys(call.input).filterNot(inputKeys.contains)
    if extra.nonEmpty then
      val expected = layout.inputFields.map(_.name).mkString(", ")
      RuntimeEnvironment.warn(
        s"Predict '${layout.name}'",
        s"ignoring unexpected input field(s) [${extra.sorted.mkString(", ")}] not declared in the signature; " +
          s"expected input fields: [$expected]"
      )

  private def parseOutputs(adapter: Adapter, outputs: Vector[LmOutput])(using
      RuntimeContext
  ): Either[DspyError, Vector[ParsedOutput]] =
    outputs.zipWithIndex.foldLeft[Either[DspyError, Vector[ParsedOutput]]](Right(Vector.empty)) { (acc, pair) =>
      val (output, index) = pair
      for
        soFar  <- acc
        parsed <- CallbackDispatcher.withAdapter(
                    adapterName = adapter.name,
                    inputs = DynamicValues.record("phase" := "parse", "index" := index)
                  ) {
                    adapter.parse(layout, output)
                  }
      yield soFar :+ parsed
    }

  private def buildPrediction(
      parsedOutputs: Vector[ParsedOutput],
      response     : LmResponse,
      toolCalls    : Vector[ToolCall]
  ): Either[DspyError, RawPrediction] =
    for
      completions <- Completions.fromRows(parsedOutputs.map(_.values))
      first       <- RawPrediction.fromCompletions(completions)
      withUsage    = first.copy(lmUsage = response.usage)
      prediction   =
        if toolCalls.isEmpty then withUsage
        else withUsage.withValue(PredictEngine.ToolCallsKey, NativeFunctionCalling.encodeToolCalls(toolCalls))
    yield prediction

private[dspy4s] object PredictEngine:
  /** Name of the synthetic prediction value the engine appends when the LM returned native tool calls (upstream
    * parity: a `tool_calls` value exists only on tool turns). Consumers that iterate a prediction's values
    * positionally — e.g. [[dspy4s.programs.Aggregation.majority]]'s "last output field" default — must still skip
    * this key when present.
    */
  val ToolCallsKey: String = "tool_calls"
