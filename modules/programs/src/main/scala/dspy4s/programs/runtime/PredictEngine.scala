package dspy4s.programs.runtime

import dspy4s.adapters.DecisionAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.data.Completions
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.PredictorKey
import dspy4s.core.decisions.{DecisionEvidence, DecisionEvidenceLog, DecisionPlan, DecisionSettings}
import dspy4s.core.runtime.ActivePredictContext
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.runtime.StreamProgress
import dspy4s.lm.contracts.DecisionRequests
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.core.contracts.ToolCall
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ProgramRuntime
import zio.blocks.schema.DynamicValue

/** Shared execution body for the predict module: pushes the `ActivePredict` scope, resolves the model + adapter, runs
  * format → call → parse → prediction assembly, and dispatches the adapter / lm callback events along the way. A System
  * One model ([[LanguageModel.supportsDecisionRequests]]) gets a decision request with no adapter instead (see
  * `decide`).
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
    tools: Vector[ToolSpec] = Vector.empty,
    /** Experimental. The explicit settings of the decision outputs, by output name (upstream `Predict.fields`). The
      * engine builds the [[DecisionPlan]] of the layout with them. See [[resolveDecisions]].
      */
    decisions: Map[String, DecisionSettings] = Map.empty,
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

  def execute(call: ProgramCall[DynamicValue.Record])(using RuntimeContext): Either[DspyError, RawPrediction] =
    ActivePredictContext.withActive(moduleName, layout) {
      lm.fold(runtime.resolveModel)(Right(_)).flatMap { model =>
        if model.supportsDecisionRequests then decide(call, model)
        else
          for
            resolved   <- runtime.resolveAdapter
            adapter    <- resolveDecisions(resolved, model)
            answer     <- answerWithFallback(adapter, model, buildInvocation(call, model))
            _           = answer.parsed.foreach(parsed => recordEvidence(parsed.evidence))
            prediction <- buildPrediction(
                            answer.parsed.map(_.values),
                            answer.response.outputs.headOption.map(_.toolCalls).getOrElse(Vector.empty)
                          )
          yield prediction
      }
    }

  /** The call of a System One model (upstream `DecisionAdapter.__call__` with `system_one`, in
    * `dspy/adapters/decision.py`): no adapter, and no prompt. These errors fail the call before the request:
    *   - a layout that the System One plan rejects ([[DecisionPlan.forSystemOne]]): each output must be a decision
    *     output;
    *   - a call that streams to a listener ("Streaming decision evidence is not supported."): Jev has no stream;
    *   - a decision output with no question ([[DecisionPlan.question]]);
    *   - request options: the module config merged with the call config must be empty ("Unsupported TypeSafe generation
    *     settings: [...]").
    *
    * The request is [[DecisionRequests.request]] with the state of [[DecisionAdapter.systemOneState]] and the questions
    * of [[DecisionAdapter.systemOneQuestions]], and the `rolloutId` of the call (it changes only the cache key). The
    * call dispatches the LM callback events. The answers of the reply give the evidence
    * ([[DecisionAdapter.systemOneEvidence]]), which goes to the evidence log before the decode, as upstream. The decode
    * gives the output values. The usage of the reply goes to the usage scope of the call
    * ([[LanguageModel.callAndRecordUsage]]). Each nested decision output fails the plan first, so a System One call
    * gives no warning for it (upstream `resolve_adapter` makes the `DecisionState` before the warnings).
    */
  private def decide(call: ProgramCall[DynamicValue.Record], model: LanguageModel)(using
      RuntimeContext
  ): Either[DspyError, RawPrediction] =
    warnOnExtraInputs(call)
    val optionKeys  = DynamicValues.recordKeys(mergeConfig(config, call.config))
    def unsupported = ConfigurationError(DecisionRequests.unsupportedSettings(optionKeys))
    for
      plan      <- systemOnePlan
      _         <- Either.cond(!streams(model), (), ConfigurationError(PredictEngine.StreamingDecisionsError))
      questions <- DecisionAdapter.systemOneQuestions(plan)
      _         <- Either.cond(optionKeys.isEmpty, (), unsupported)
      request    = DecisionRequests
                  .request(model.id, DecisionAdapter.systemOneState(layout, demos, call.input), questions)
                  .copy(rolloutId = call.rolloutId)
      response <- CallbackDispatcher.withLm(
                    modelId = model.id,
                    request = DynamicValues.record("model" := model.id, "mode" := model.mode.toString)
                  ) {
                    LanguageModel.callAndRecordUsage(model, request)
                  }
      answers    <- DecisionRequests.answers(response)
      evidence   <- DecisionAdapter.systemOneEvidence(plan, answers)
      _           = recordEvidence(evidence)
      values     <- plan.decode(evidence.toMap)
      prediction <- buildPrediction(Vector(values), Vector.empty)
    yield prediction

  /** Add the decision evidence of one completion to the evidence log (upstream `DecisionAdapter._decode` with
    * `record_evidence`). The key is the key of the predictor that runs ([[RuntimeEnvironment.activePredictor]]), so
    * that the evidence of the `Predict` inside `ChainOfThought` belongs to the `ChainOfThought` leaf, as its trace
    * entry does. With no such predictor, the key of this engine.
    */
  private def recordEvidence(evidence: Vector[(String, DecisionEvidence)]): Unit =
    if evidence.nonEmpty then
      val key = RuntimeEnvironment.activePredictor.getOrElse(PredictorKey.from(moduleName, layout))
      evidence.foreach((field, value) => DecisionEvidenceLog.record(key, field, value))

  /** The adapter of a call with the decision outputs of the layout (upstream `resolve_adapter` in
    * `dspy/adapters/decision.py`): `adapter` itself when the layout has no decision output, else a [[DecisionAdapter]]
    * around `adapter`. Its fallback is the fallback of `adapter`, in a [[DecisionAdapter]] too. These errors fail the
    * call before the request:
    *   - settings that the plan rejects ([[DecisionPlan.of]]);
    *   - a call that streams to a listener (upstream `DecisionAdapter._prepare`: "Streaming decision evidence is not
    *     supported.");
    *   - a decision output with no question: no description and no instructions ([[DecisionPlan.question]]).
    *
    * The call warns about each output with a nested decision type, with the text of upstream `resolve_adapter`.
    */
  private def resolveDecisions(adapter: Adapter, model: LanguageModel): Either[DspyError, Adapter] =
    nestedDecisionFields.foreach(name =>
      RuntimeEnvironment.warn(s"Predict '${layout.name}'", PredictEngine.nestedDecisionWarning(name))
    )
    decisionPlan.flatMap { plan =>
      if plan.kinds.isEmpty then Right(adapter)
      else
        for
          _ <- Either.cond(!streams(model), (), ConfigurationError(PredictEngine.StreamingDecisionsError))
          _ <- DecisionAdapter.evidenceLayout(layout, plan)
        yield DecisionAdapter(adapter, plan)
    }

  /** `true` when `model` gives the stream output of this predictor to a listener ([[StreamingLanguageModel]]). */
  private def streams(model: LanguageModel): Boolean =
    model match
      case streaming: StreamingLanguageModel => streaming.streamsToListener(moduleName, layout.outputFields.map(_.name))
      case _                                 => false

  /** Format, call the model, and parse with `adapter`. After a [[ParseError]] of the parse step, do the three steps one
    * more time with [[Adapter.fallback]] (upstream `ChatAdapter.__call__`). A call that streamed output to a listener
    * gets no second call. An error of the format step or of the model gives no second call.
    */
  private def answerWithFallback(adapter: Adapter, model: LanguageModel, invocation: AdapterInvocation)(using
      RuntimeContext
  ): Either[DspyError, PredictEngine.Answer] =
    StreamProgress.withScope { progress =>
      callModel(adapter, model, invocation).flatMap { attempt =>
        attempt.parsed match
          case Left(error: ParseError) if !progress.emitted =>
            adapter.fallback match
              case Some(next) => callModel(next, model, invocation).flatMap(_.answer)
              case None       => Left(error)
          case _ => attempt.answer
      }
    }

  /** Format with `adapter`, call the model, and parse. The result keeps the result of the parse step apart, so the
    * caller can tell a parse error from an error of the other steps. The usage of the reply goes to the usage scope of
    * the call before the parse ([[LanguageModel.callAndRecordUsage]]), so an attempt whose reply fails to parse counts
    * too (upstream records the usage of each LM call, `dspy/clients/execution.py`, `finalize`).
    */
  private def callModel(adapter: Adapter, model: LanguageModel, invocation: AdapterInvocation)(using
      RuntimeContext
  ): Either[DspyError, PredictEngine.Attempt] =
    for
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
                    // request options, so explicit per-call/module config wins on key collision. The options that the
                    // adapter removes (`FormattedPrompt.removedOptions`) go first.
                    val mergedOptions = prompt.options(invocation.request.options)
                    LanguageModel.callAndRecordUsage(
                      model,
                      invocation.request.copy(messages = prompt.messages, options = mergedOptions)
                    )
                  }
    yield PredictEngine.Attempt(response, parseOutputs(adapter, invocation, response.outputs))

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

  /** Parse each output with `adapter` (`Adapter.parse` with `invocation`, the invocation of the request). */
  private def parseOutputs(adapter: Adapter, invocation: AdapterInvocation, outputs: Vector[LmOutput])(using
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
                    adapter.parse(invocation, output)
                  }
      yield soFar :+ parsed
    }

  /** The prediction of the output `rows` (one row for each completion). It has no usage: `Module.apply` gives the
    * result the total of the usage scope of the call, thus each model call of the call counts, also a call whose reply
    * failed to parse before the JSON fallback (decision D9 of UPGRADE-3.2.1-to-3.4.0).
    */
  private def buildPrediction(
      rows     : Vector[DynamicValue.Record],
      toolCalls: Vector[ToolCall]
  ): Either[DspyError, RawPrediction] =
    for
      completions <- Completions.fromRows(rows)
      first       <- RawPrediction.fromCompletions(completions)
      prediction   =
        if toolCalls.isEmpty then first
        else first.withValue(PredictEngine.ToolCallsKey, NativeFunctionCalling.encodeToolCalls(toolCalls))
    yield prediction

private[dspy4s] object PredictEngine:

  /** The response of the model and its parsed outputs. */
  private final case class Answer(response: LmResponse, parsed: Vector[ParsedOutput])

  /** The response of the model and the result of the parse step. */
  private final case class Attempt(response: LmResponse, parsed: Either[DspyError, Vector[ParsedOutput]]):
    def answer: Either[DspyError, Answer] = parsed.map(Answer(response, _))

  /** `decisions` with `settings` as the settings of the output `field` of `layout` (upstream `Predict.fields[field]`).
    * A `Left` comes when the plan of `layout` rejects the result ([[DecisionPlan.of]]). Upstream checks the settings at
    * the call only; the port checks them here too, so that an error comes early.
    */
  private[programs] def withDecision(
      layout   : SignatureLayout,
      decisions: Map[String, DecisionSettings],
      field    : String,
      settings : DecisionSettings
  ): Either[DspyError, Map[String, DecisionSettings]] =
    val updated = decisions.updated(field, settings)
    DecisionPlan.of(layout, updated).map(_ => updated)

  /** The effective criteria of the output `field` of `layout` (upstream `Predict.get_criteria`). A field with no
    * settings gets empty settings for the check, so a native output that can be a decision output has criteria too. A
    * `Left` comes when `field` cannot be a decision output, or when the plan rejects `decisions`.
    */
  private[programs] def criteria(
      layout   : SignatureLayout,
      decisions: Map[String, DecisionSettings],
      field    : String
  ): Either[DspyError, Option[DynamicValue]] =
    DecisionPlan.of(layout, decisions.updatedWith(field)(_.orElse(Some(DecisionSettings())))).map(_.criteria(field))

  /** `decisions` with `criteria` as the criteria of the output `field` of `layout` (upstream `Predict.set_criteria`).
    * The other settings of the field stay. A `Left` comes when the plan rejects the result.
    */
  private[programs] def withCriteria(
      layout   : SignatureLayout,
      decisions: Map[String, DecisionSettings],
      field    : String,
      criteria : DynamicValue
  ): Either[DspyError, Map[String, DecisionSettings]] =
    withDecision(
      layout,
      decisions,
      field,
      decisions.getOrElse(field, DecisionSettings()).copy(criteria = Some(criteria))
    )

  /** The error of a streamed call with decision outputs (upstream `DecisionAdapter._prepare`). */
  private val StreamingDecisionsError: String = "Streaming decision evidence is not supported."

  /** The warning for an output with a nested decision type (upstream `resolve_adapter`). */
  private def nestedDecisionWarning(name: String): String =
    s"Decision evidence decoding is not implemented for nested output '$name'. The LM generates values and " +
      "confidence directly; thresholds, cuts, and weights are not applied. Use top-level decision output fields " +
      "instead."

  /** Name of the synthetic prediction value the engine appends when the LM returned native tool calls (upstream parity:
    * a `tool_calls` value exists only on tool turns). Consumers that iterate a prediction's values positionally — e.g.
    * [[dspy4s.programs.Aggregation.majority]]'s "last output field" default — must still skip this key when present.
    */
  val ToolCallsKey: String = "tool_calls"
