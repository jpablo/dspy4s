package dspy4s.programs.runtime

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.Completions
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.ActivePredictContext
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.LmUsage
import dspy4s.lm.contracts.ToolCall
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
  * callback scope and trace/history recording) is added by
  * `BasePredictProgram.run`, which `DynamicPredict` extends. Callers
  * that want the full lifecycle should go through `DynamicPredict`;
  * the typed `Predict[I, O]` is one such caller. */
private[dspy4s] final case class PredictEngine(
    layout: SignatureLayout,
    demos: Vector[Example],
    moduleName: String,
    runtime: ProgramRuntime,
    /** Optional pre-rendered JSON Schema for the output. Populated by the typed [[dspy4s.programs.Predict]]
      * path (which has a `Schema[O]` to render via `Shape.jsonSchemaString`); left `None` by
      * [[dspy4s.programs.DynamicPredict]]. Passed straight through to [[AdapterInvocation]]; adapters that
      * understand it inline the schema in their prompt instruction. */
    outputJsonSchema: Option[String] = None
):

  def execute(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
    ActivePredictContext.withActive(moduleName, layout) {
      for
        model <- runtime.resolveModel
        adapter <- runtime.resolveAdapter
        invocation = buildInvocation(call, model)
        prompt <- CallbackDispatcher.withAdapter(
          adapterName = adapter.name,
          inputs = Map("phase" -> "format", "signature" -> layout.name)
        ) {
          adapter.format(invocation)
        }
        response <- CallbackDispatcher.withLm(
          modelId = model.id,
          request = Map("model" -> model.id, "mode" -> model.mode.toString)
        ) {
          model.call(invocation.request.copy(messages = prompt.messages))
        }
        parsed <- parseOutputs(adapter, response.outputs)
        prediction <- buildPrediction(parsed, response, response.outputs.headOption.map(_.toolCalls).getOrElse(Vector.empty))
      yield prediction
    }

  private def buildInvocation(call: ProgramCall, model: LanguageModel): AdapterInvocation =
    val inputKeys = layout.inputFields.map(_.name).toSet
    AdapterInvocation(
      layout = layout,
      demos = demos,
      inputs = Example(values = call.inputs, inputKeys = inputKeys),
      outputJsonSchema = outputJsonSchema,
      request = LmRequest(
        model = model.id,
        mode = model.mode,
        options = call.config
      )
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
          inputs = Map("phase" -> "parse", "index" -> index)
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
    usage.details ++ Map(
      "total_tokens" -> usage.totalTokens,
      "prompt_tokens" -> usage.promptTokens,
      "completion_tokens" -> usage.completionTokens
    )

  private def toToolCallPayload(toolCalls: Vector[ToolCall]): DynamicValue =
    DynamicValue.Sequence(Chunk.from(
      toolCalls.map { call =>
        DynamicValue.Record(Chunk(
          "name" -> DynamicValue.Primitive(PrimitiveValue.String(call.name)),
          "args" -> DynamicValues.fromAny(call.args)
        ))
      }
    ))
