package dspy4s.programs.plan

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, NativeFunctionCalling, ParsedOutput}
import dspy4s.core.contracts.{DspyError, RuntimeContext, RuntimeError}
import dspy4s.core.data.{Completions, Example, RawPrediction}
import dspy4s.lm.contracts.{LanguageModel, LmOutput, LmRequest, LmResponse}
import zio.{IO, ZIO}

/** Effect adapter for the current blocking LM and adapter contracts.
  *
  * This is the only bridge from the new program interpreter to the old synchronous service APIs. Dependencies and the
  * compatibility context are explicit values. No ambient runtime lookup or thread-local configuration is used here.
  */
final class LivePredictionBackend(
    model  : LanguageModel,
    adapter: Adapter,
    context: RuntimeContext
) extends PredictionBackend:

  def generate(request: PredictionRequest): IO[DspyError, RawPrediction] =
    ZIO
      .attemptBlocking {
        given RuntimeContext = context
        execute(request)
      }
      .mapError(error => RuntimeError(
        "prediction_backend",
        Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
      ))
      .flatMap(ZIO.fromEither)

  private def execute(request: PredictionRequest)(using RuntimeContext): Either[DspyError, RawPrediction] =
    val inputKeys = request.layout.inputFields.iterator.map(_.name).toSet
    val invocation = AdapterInvocation(
      layout = request.layout,
      demos = request.demos,
      inputs = Example(request.inputs, inputKeys),
      request = LmRequest(
        model = model.id,
        mode = model.mode,
        options = request.config,
        rolloutId = request.rolloutId
      ),
      outputJsonSchema = request.outputJsonSchema,
      tools = request.tools
    )

    for
      prompt   <- adapter.format(invocation)
      response <- model.call(invocation.request.copy(
                    messages = prompt.messages,
                    options = FormattedPrompt.mergeOptions(prompt.requestOptions, invocation.request.options)
                  ))
      parsed   <- parseOutputs(request, response.outputs)
      raw      <- buildPrediction(parsed, response)
    yield raw

  private def parseOutputs(
      request: PredictionRequest,
      outputs: Vector[LmOutput]
  )(using RuntimeContext): Either[DspyError, Vector[ParsedOutput]] =
    outputs.foldLeft[Either[DspyError, Vector[ParsedOutput]]](Right(Vector.empty)) { (acc, output) =>
      for
        parsedSoFar <- acc
        parsed      <- adapter.parse(request.layout, output)
      yield parsedSoFar :+ parsed
    }

  private def buildPrediction(
      parsed  : Vector[ParsedOutput],
      response: LmResponse
  ): Either[DspyError, RawPrediction] =
    for
      completions <- Completions.fromRows(parsed.map(_.values))
      primary     <- RawPrediction.fromCompletions(completions)
    yield
      val withUsage = response.usage.fold(primary)(primary.withUsage)
      val toolCalls = response.outputs.headOption.fold(Vector.empty)(_.toolCalls)
      if toolCalls.isEmpty then withUsage
      else withUsage.withValue("tool_calls", NativeFunctionCalling.encodeToolCalls(toolCalls))
