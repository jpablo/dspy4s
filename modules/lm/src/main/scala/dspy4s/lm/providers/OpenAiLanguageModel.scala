package dspy4s.lm.providers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.lm.runtime.ProviderRequestNormalizer
import dspy4s.lm.runtime.ProviderResponseParser

final case class OpenAiLanguageModel(
    model: String,
    mode: LmMode = LmMode.Chat,
    client: OpenAiClient,
    defaultOptions: Map[String, Any] = Map.empty
) extends StreamingLanguageModel:

  override val id: String = model

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    val normalized = ProviderRequestNormalizer.normalize(request, defaultOptions)
    client.invoke(normalized).flatMap(ProviderResponseParser.parse(_, mode))

  override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
    val normalized = ProviderRequestNormalizer.normalize(request, defaultOptions)
    client.stream(normalized) match
      case Right(iterator) => iterator
      case Left(error)     =>
        Iterator.single(
          LmChunk(
            text = "",
            finishReason = Some("error"),
            raw = Some(Map("error" -> error.message))
          )
        )

object OpenAiLanguageModel:
  def apply(model: String, apiKey: String): OpenAiLanguageModel =
    OpenAiLanguageModel(model = model, client = OpenAiClient(apiKey = apiKey))

  def apply(
      model: String,
      apiKey: String,
      baseUrl: String,
      transport: HttpTransport
  ): OpenAiLanguageModel =
    OpenAiLanguageModel(
      model = model,
      client = OpenAiClient(apiKey = apiKey, baseUrl = baseUrl, transport = transport)
    )

  def fromEnv(model: String, envVar: String = "OPENAI_API_KEY"): Either[DspyError, OpenAiLanguageModel] =
    OpenAiClient.fromEnv(envVar = envVar).map(client => OpenAiLanguageModel(model = model, client = client))
