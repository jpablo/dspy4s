package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.CompletionData
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.ExampleData
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.PredictionData
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
import dspy4s.programs.runtime.BasePredictProgram
import dspy4s.programs.runtime.SettingsProgramRuntime

final case class DynamicPredict(
    layout: SignatureLayout,
    demos: Vector[Example] = Vector.empty,
    name: Option[String] = None,
    runtime: ProgramRuntime = new SettingsProgramRuntime {}
) extends BasePredictProgram(moduleName = name.getOrElse("predict")):

  override protected def execute(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
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
      inputs = ExampleData(values = call.inputs, inputKeys = inputKeys),
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
      completions <- CompletionData.fromRows(parsedOutputs.map(_.values))
      first <- PredictionData.fromCompletions(completions)
      withUsage = first.copy(lmUsage = response.usage.map(usageToMap))
      prediction = withUsage.withValue("tool_calls", toToolCallPayload(toolCalls))
    yield prediction

  private def usageToMap(usage: LmUsage): Map[String, Long] =
    usage.details ++ Map(
      "total_tokens" -> usage.totalTokens,
      "prompt_tokens" -> usage.promptTokens,
      "completion_tokens" -> usage.completionTokens
    )

  private def toToolCallPayload(toolCalls: Vector[ToolCall]): Vector[Map[String, Any]] =
    toolCalls.map(call => Map("name" -> call.name, "args" -> call.args))
