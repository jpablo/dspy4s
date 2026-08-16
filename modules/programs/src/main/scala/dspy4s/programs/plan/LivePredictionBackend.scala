package dspy4s.programs.plan

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, NativeFunctionCalling, ParsedOutput}
import dspy4s.core.contracts.{ClosableIterator, DspyError, LmUsage, RuntimeContext, RuntimeError}
import dspy4s.core.data.{Completions, Example, RawPrediction}
import dspy4s.lm.contracts.*
import dspy4s.lm.runtime.ToolCallAssembler
import zio.{IO, UIO, Unsafe, ZIO}

import scala.collection.mutable.ArrayBuffer

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
    blocking {
      given RuntimeContext = context
      execute(request)
    }

  override def generateStreaming(
      request: PredictionRequest,
      emit   : PredictionChunk => UIO[Unit]
  ): IO[DspyError, RawPrediction] =
    model match
      case streaming: StreamingLanguageModel => ZIO.runtime[Any].flatMap { runtime =>
          blocking {
            given RuntimeContext = context
            Unsafe.unsafe { implicit unsafe =>
              executeStreaming(
                request,
                streaming,
                chunk => runtime.unsafe.run(emit(chunk)).getOrThrowFiberFailure()
              )
            }
          }
        }
      case _ => generate(request)

  private def blocking(body: => Either[DspyError, RawPrediction]): IO[DspyError, RawPrediction] =
    ZIO
      .attemptBlocking(body)
      .mapError(error =>
        RuntimeError(
          "prediction_backend",
          Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
        )
      )
      .flatMap(ZIO.fromEither)

  private def execute(request: PredictionRequest)(using RuntimeContext): Either[DspyError, RawPrediction] =
    val invocation = buildInvocation(request)

    for
      prompt   <- adapter.format(invocation)
      response <- model.call(withPrompt(invocation, prompt))
      parsed   <- parseOutputs(request, response.outputs)
      raw      <- buildPrediction(parsed, response)
    yield raw

  private def executeStreaming(
      request  : PredictionRequest,
      streaming: StreamingLanguageModel,
      emit     : PredictionChunk => Unit
  )(using RuntimeContext): Either[DspyError, RawPrediction] =
    adapter.format(buildInvocation(request)).flatMap { prompt =>
      val invocation                  = buildInvocation(request)
      val lmRequest                   = withPrompt(invocation, prompt)
      val text                        = new StringBuilder
      val toolDeltas                  = ArrayBuffer.empty[LmToolCallDelta]
      val state                       = adapter.streamingState(request.layout)
      var pendingRaw: Option[String]  = None
      var usage: Option[LmUsage]      = None
      var streamError: Option[String] = None
      val chunks                      = streaming.stream(lmRequest)

      try
        chunks.foreach { chunk =>
          if chunk.finishReason.contains("error") then streamError = Some(errorMessage(chunk.raw))
          else
            text.append(chunk.text)
            chunk.usage.foreach(value => usage = Some(value))
            toolDeltas ++= chunk.toolCalls
            state match
              case Some(current) => if chunk.text.nonEmpty then
                  current.receive(chunk.text).foreach { field =>
                    emit(PredictionChunk(field.fieldName, field.text, field.isLast))
                  }
              case None => if chunk.text.nonEmpty then
                  pendingRaw.foreach(value => emit(PredictionChunk("", value)))
                  pendingRaw = Some(chunk.text)
        }

        streamError match
          case Some(message) => Left(RuntimeError("prediction_backend_stream", message))
          case None          =>
            state match
              case Some(current) => current.finish().foreach { field =>
                  emit(PredictionChunk(field.fieldName, field.text, field.isLast))
                }
              case None => pendingRaw.foreach(value => emit(PredictionChunk("", value, isLast = true)))

            val response = LmResponse(
              outputs = Vector(LmOutput(text.toString, toolCalls = ToolCallAssembler.assemble(toolDeltas))),
              usage = usage
            )
            for
              parsed <- parseOutputs(request, response.outputs)
              raw    <- buildPrediction(parsed, response)
            yield raw
      finally
        chunks match
          case closable: ClosableIterator[?] => closable.close()
          case _                             => ()
    }

  private def buildInvocation(request: PredictionRequest): AdapterInvocation =
    val inputKeys = request.layout.inputFields.iterator.map(_.name).toSet
    AdapterInvocation(
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

  private def withPrompt(invocation: AdapterInvocation, prompt: FormattedPrompt): LmRequest =
    invocation.request.copy(
      messages = prompt.messages,
      options = FormattedPrompt.mergeOptions(prompt.requestOptions, invocation.request.options)
    )

  private def errorMessage(raw: Option[Any]): String =
    raw match
      case Some(values: Map[?, ?]) =>
        values.collectFirst { case (key, value) if String.valueOf(key) == "error" => String.valueOf(value) }
          .getOrElse(values.toString)
      case Some(value) => value.toString
      case None        => "LM stream terminated with an error"

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
