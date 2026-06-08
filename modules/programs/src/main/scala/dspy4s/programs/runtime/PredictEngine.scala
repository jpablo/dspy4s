package dspy4s.programs.runtime

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.Completions
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.updated
import dspy4s.core.runtime.ActivePredictContext
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmUsage
import dspy4s.core.contracts.ToolCall
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ProgramRuntime
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Shared execution body for the predict module: pushes the
  * `ActivePredict` scope, resolves the model + adapter, runs
  * format → call → parse → prediction assembly, and dispatches the
  * adapter / lm callback events along the way.
  *
  * This is the raw engine -- module-level lifecycle (the `withModule`
  * callback scope and trace/history recording) is added by `Module.apply`.
  * Two sibling `Module`s call this engine in their `forward`: the untyped
  * `DynamicPredict` (`Module[ProgramCall, DynamicPrediction]`) and the typed
  * `Predict[I, O]` (`Module[TypedCall[I], Prediction[O]]`, which encodes/decodes
  * around `execute`). Neither wraps the other, so a call emits exactly one
  * module event. */
private[dspy4s] final case class PredictEngine(
    layout: SignatureLayout,
    demos: Vector[Example],
    moduleName: String,
    runtime: ProgramRuntime,
    /** Optional pre-rendered JSON Schema for the output. Populated by the typed [[dspy4s.programs.Predict]]
      * path (which has a `Schema[O]` to render via `Shape.jsonSchemaString`); left `None` by
      * [[dspy4s.programs.DynamicPredict]]. Passed straight through to [[AdapterInvocation]]; adapters that
      * understand it inline the schema in their prompt instruction. */
    outputJsonSchema: Option[String] = None,
    /** Module-level LM option bag, the analogue of Python's `dspy.Predict(signature, **config)` `self.config`.
      * Merged *under* the per-call [[ProgramCall.config]] in [[buildInvocation]] (per-call keys win on
      * collision), so it supplies defaults a call may override. Empty by default, in which case the merged
      * options are exactly the per-call config. */
    config: DynamicValue.Record = DynamicValue.Record.empty,
    /** Optional per-module bound LM (Python's `set_lm`/`get_lm`). When set, it is used in preference to the
      * ambient `RuntimeContext` LM (`runtime.resolveModel`), so different predictors in one program can pin
      * different models. `None` (the default) falls back to ambient resolution. See PORT_GAPS G-3. */
    lm: Option[LanguageModel] = None,
    /** Tool schemas surfaced to the adapter via [[AdapterInvocation.tools]]. Only an adapter with native
      * function-calling enabled (and a `tool_calls` output field in the layout) acts on them; others ignore them.
      * Empty by default. See PORT_GAPS G-7b. */
    tools: Vector[ToolSpec] = Vector.empty
):

  def execute(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    ActivePredictContext.withActive(moduleName, layout) {
      for
        model <- lm.fold(runtime.resolveModel)(Right(_))
        adapter <- runtime.resolveAdapter
        invocation = buildInvocation(call, model)
        prompt <- CallbackDispatcher.withAdapter(
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
          val mergedOptions = FormattedPrompt.mergeOptions(prompt.requestOptions, invocation.request.options)
          model.call(invocation.request.copy(messages = prompt.messages, options = mergedOptions))
        }
        parsed <- parseOutputs(adapter, response.outputs)
        prediction <- buildPrediction(parsed, response, response.outputs.headOption.map(_.toolCalls).getOrElse(Vector.empty))
      yield prediction
    }

  private def buildInvocation(call: ProgramCall, model: LanguageModel): AdapterInvocation =
    val inputKeys = layout.inputFields.map(_.name).toSet
    warnOnExtraInputs(call, inputKeys)
    AdapterInvocation(
      layout = layout,
      demos = demos,
      inputs = Example(values = call.inputs, inputKeys = inputKeys),
      outputJsonSchema = outputJsonSchema,
      tools = tools,
      request = LmRequest(
        model = model.id,
        mode = model.mode,
        options = mergeConfig(config, call.config),
        rolloutId = call.rolloutId
      )
    )

  /** Merge the module-level `config` with the per-call config so that per-call keys win on collision: start
    * from the module config and upsert each per-call field by name (later wins, preserving insertion order via
    * the `updated` extension). Mirrors Python's `{**self.config, **call_kwargs}`. When module `config` is empty
    * the result is exactly `callConfig`, so behavior is unchanged for callers that don't set a module config.
    *
    * (Deferred: a per-module bound LM — Python's `set_lm`/`get_lm` — is intentionally not handled here; the LM
    * is resolved from the ambient `RuntimeContext` via `runtime.resolveModel`. See PORT_GAPS G-3.) */
  private def mergeConfig(moduleConfig: DynamicValue.Record, callConfig: DynamicValue.Record): DynamicValue.Record =
    callConfig.fields.iterator.foldLeft(moduleConfig)((acc, kv) => acc.updated(kv._1, kv._2))

  /** Mirror upstream dspy 3.2.1 `predict.py` `_forward_preprocess`: input keys that are not declared input
    * fields are tolerated (the extras are dropped downstream, since [[AdapterInvocation]] is built with
    * `inputKeys` restricted to the layout), but their presence is surfaced as a warning naming the
    * unexpected keys and the expected fields. The call still proceeds -- this is a diagnostic, not an error.
    *
    * dspy4s has no logging framework in `core`/`programs` (Python dspy uses `logger.warning`); the closest
    * non-invasive equivalent is `System.err` via `Console.err`, which keeps the warning observable without
    * introducing a new callback event type (that would require changing the shared `core` contracts).
    *
    * Design note (intentional v1, not a TODO): the [[dspy4s.core.contracts.CallbackEvent]] system is strictly
    * scope-based -- every event is a Start/End pair correlated by `callId`, and a one-off diagnostic is not a
    * scope, so routing this warning through callbacks would break that invariant. A dedicated diagnostics/log
    * seam on `RuntimeContext` is the "proper depth" fix, but it is new public API for a single current caller;
    * `Console.err` is observable (stderr) and sufficient until a second warning site justifies the seam. */
  private def warnOnExtraInputs(call: ProgramCall, inputKeys: Set[String]): Unit =
    val extra = DynamicValues.recordKeys(call.inputs).filterNot(inputKeys.contains)
    if extra.nonEmpty then
      val expected = layout.inputFields.map(_.name).mkString(", ")
      Console.err.println(
        s"WARN [dspy4s] Predict '${layout.name}': ignoring unexpected input field(s) " +
          s"[${extra.sorted.mkString(", ")}] not declared in the signature; expected input fields: [$expected]"
      )

  private def parseOutputs(adapter: Adapter, outputs: Vector[LmOutput])(using
      RuntimeContext
  ): Either[DspyError, Vector[ParsedOutput]] =
    outputs.zipWithIndex.foldLeft[Either[DspyError, Vector[ParsedOutput]]](Right(Vector.empty)) { (acc, pair) =>
      val (output, index) = pair
      for
        soFar <- acc
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
      response: LmResponse,
      toolCalls: Vector[ToolCall]
  ): Either[DspyError, DynamicPrediction] =
    for
      completions <- Completions.fromRows(parsedOutputs.map(_.values))
      first <- DynamicPrediction.fromCompletions(completions)
      withUsage = first.copy(lmUsage = response.usage.map(usageToMap))
      prediction = withUsage.withValue("tool_calls", toToolCallPayload(toolCalls))
    yield prediction

  private def usageToMap(usage: LmUsage): Map[String, Long] =
    usage.extras.map { case (category, value) => category.wireName -> value } ++ Map(
      "total_tokens" -> usage.totalTokens,
      "prompt_tokens" -> usage.promptTokens,
      "completion_tokens" -> usage.completionTokens
    )

  private def toToolCallPayload(toolCalls: Vector[ToolCall]): DynamicValue =
    DynamicValue.Sequence(Chunk.from(
      toolCalls.map { call =>
        DynamicValue.Record(Chunk(
          "name" -> DynamicValue.Primitive(PrimitiveValue.String(call.name)),
          "args" -> call.args
        ))
      }
    ))
