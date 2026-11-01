package dspy4s.programs.runtime

import dspy4s.adapters.DecisionAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.NativeFunctionCalling
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ParseError
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
import dspy4s.core.decisions.{DecisionPlan, DecisionSettings}
import dspy4s.core.runtime.ActivePredictContext
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.runtime.StreamProgress
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.core.contracts.ToolCall
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ProgramRuntime
import zio.blocks.schema.DynamicValue

/** Shared execution body for the predict module: pushes the `ActivePredict` scope, resolves the model + adapter, runs
  * format → call → parse → prediction assembly, and dispatches the adapter / lm callback events along the way. A System
  * One model ([[LanguageModel.supportsDecisionRequests]]) gets a decision request with no adapter instead (see
  * [[DecisionAdapter.decide]]).
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
      * *under* the per-call [[ProgramCall.config]] in [[buildInvocation]] (per-call keys win on collision), so it
      * supplies defaults a call may override. When it is empty, the merged options are exactly the per-call config.
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

  def execute(call: ProgramCall[DynamicValue.Record])(using RuntimeContext): Either[DspyError, RawPrediction] =
    ActivePredictContext.withActive(moduleName, layout) {
      lm.fold(runtime.resolveModel)(Right(_)).flatMap { model =>
        if model.supportsDecisionRequests then
          warnOnExtraInputs(call)
          for
            plan   <- systemOnePlan
            values <- DecisionAdapter.decide(
                        plan,
                        layout,
                        demos,
                        moduleName,
                        model,
                        call.input,
                        DynamicValues.mergeRecords(config, call.config),
                        call.rolloutId
                      )
            prediction <- buildPrediction(Vector(values), Vector.empty)
          yield prediction
        else
          for
            base       <- runtime.resolveAdapter
            adapter    <- DecisionAdapter.resolve(base, decisionPlan, layout, nestedDecisionFields, moduleName, model)
            answer     <- answerWithFallback(adapter, model, buildInvocation(call, model))
            _           = answer.parsed.map(_.evidence).foreach(DecisionAdapter.recordEvidence(moduleName, layout, _))
            prediction <- buildPrediction(
                            answer.parsed.map(_.values),
                            answer.response.outputs.headOption.map(_.toolCalls).getOrElse(Vector.empty)
                          )
          yield prediction
      }
    }

  /** Format, call the model, and parse with `adapter`. After a [[ParseError]] of the parse step, do the three steps one
    * more time with [[Adapter.fallback]] (upstream `ChatAdapter.__call__`). A call that streamed output to a listener
    * gets no second call. An error of the format step or of the model gives no second call.
    *
    * The steps run with `model` as the model of the context, also when `Predict.withLm` pins a model that is not the
    * model of the context. The adapters read the capabilities of the model from the context: the response schema of the
    * JSON adapter, and native function calling. The stream state of a streamed call
    * ([[dspy4s.adapters.contracts.Adapter.streamingState]]) gets the context of the model call, thus the same model.
    */
  private def answerWithFallback(adapter: Adapter, model: LanguageModel, invocation: AdapterInvocation)(using
      context: RuntimeContext
  ): Either[DspyError, PredictEngine.Answer] =
    given RuntimeContext = context.withServices(context.services.copy(lm = Some(model)))
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
      // G-7: merge the adapter-contributed request options (e.g. `response_format`) UNDER the existing request
      // options, so explicit per-call/module config wins on key collision. The options that the adapter removes
      // (`FormattedPrompt.removedOptions`) go first.
      response <- LanguageModel.callWithEvents(
                    model,
                    invocation.request.copy(
                      messages = prompt.messages,
                      options = prompt.options(invocation.request.options)
                    )
                  )
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
        options = DynamicValues.mergeRecords(config, call.config),
        rolloutId = call.rolloutId
      )
    )

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
    Traverse.either(outputs.zipWithIndex) { (output, index) =>
      CallbackDispatcher.withAdapter(
        adapterName = adapter.name,
        inputs = DynamicValues.record("phase" := "parse", "index" := index)
      ) {
        adapter.parse(invocation, output)
      }
    }

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

private[dspy4s] object PredictEngine:

  /** The response of the model and its parsed outputs. */
  private final case class Answer(response: LmResponse, parsed: Vector[ParsedOutput])

  /** The response of the model and the result of the parse step. */
  private final case class Attempt(response: LmResponse, parsed: Either[DspyError, Vector[ParsedOutput]]):
    def answer: Either[DspyError, Answer] = parsed.map(Answer(response, _))
