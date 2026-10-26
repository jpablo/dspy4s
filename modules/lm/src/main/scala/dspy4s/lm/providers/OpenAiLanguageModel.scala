package dspy4s.lm.providers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.LmCapabilities
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import zio.blocks.schema.DynamicValue

/** The OpenAI-compatible HTTP provider: a [[dspy4s.lm.contracts.StreamingLanguageModel]] that sends each request
  * through an [[OpenAiClient]]. [[mode]] selects the endpoint: the chat-completions endpoint in the chat mode, the
  * completions endpoint in the text mode, and the Responses endpoint in the Responses mode. `call` and `stream` follow
  * the same steps: they build the body of the request on the wire of [[mode]] ([[ChatWire]] or [[ResponsesWire]]), send
  * it with the client, and read the reply into the `LmResponse` and `LmChunk` contracts.
  *
  * The [[OpenAiClient]] holds the base URL and the transport. Thus this type can call each server that has the shape of
  * the OpenAI API, for example Azure OpenAI, Ollama, vLLM, LM Studio, and OpenRouter. Use the
  * [[OpenAiLanguageModel.apply(model:String,apiKey:String,baseUrl:String,transport:dspy4s\.lm\.providers\.HttpTransport)* baseUrl overload]]
  * for another server.
  *
  * @param model
  *   the model id of the provider, for example `"gpt-4o-mini"`. [[id]] has the same value, for the history and the
  *   trace.
  * @param mode
  *   the wire of the model. This field selects the endpoint, the body, and the parse. The mode of a request does not: a
  *   model in the chat mode sends the messages of each request, a model in the text mode sends a prompt, and a model in
  *   the Responses mode calls the Responses API. The default is [[dspy4s.lm.contracts.LmMode.Chat]].
  * @param client
  *   the HTTP client with the API key, the base URL, the transport, and the endpoint paths.
  * @param defaultOptions
  *   the provider parameters that go into each request, for example `temperature` and `max_tokens`. An option of a
  *   request replaces the default of the same name ([[dspy4s.lm.contracts.LmRequest.effectiveOptions]]).
  * @param capabilities
  *   what the model can do. `None` gives the capabilities that the port declares for the base URL of the client
  *   ([[dspy4s.lm.contracts.LmCapabilities.forBaseUrl]]): the OpenAI capabilities for the official address, and no
  *   capability for an other server (decision L5). Set it with `copy(capabilities = Some(...))`.
  */
final case class OpenAiLanguageModel(
    model                      : String,
    mode                       : LmMode                 = LmMode.Chat,
    client                     : OpenAiClient,
    override val defaultOptions: DynamicValue.Record    = DynamicValue.Record.empty,
    capabilities               : Option[LmCapabilities] = None
) extends StreamingLanguageModel:

  override val id: String = model

  /** The capabilities of this model: [[capabilities]], else the capabilities of the base URL of the client. */
  private val effectiveCapabilities: LmCapabilities = capabilities.getOrElse(LmCapabilities.forBaseUrl(client.baseUrl))

  override def supportsFunctionCalling: Boolean = effectiveCapabilities.functionCalling
  override def supportsResponseSchema: Boolean  = effectiveCapabilities.responseSchema
  override def supportsReasoning: Boolean       = effectiveCapabilities.reasoning

  /** Build the body, POST it to the endpoint of [[mode]], and read the JSON reply into an `LmResponse`. A failure of
    * the HTTP call or of the decode is a `Left[DspyError]` from the [[OpenAiClient]], never an exception.
    *
    * With [[dspy4s.lm.contracts.LmMode.Responses]], the request goes to the Responses API, and the reply gives one
    * output (`ResponsesWire.outputs`, upstream `responses_outputs`).
    */
  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    client.invoke(mode, body(request)).flatMap(response(_, request.model))

  /** Open an SSE stream to the endpoint of [[mode]], and give the decoded [[dspy4s.lm.contracts.LmChunk]]s.
    *
    * In the Responses mode, the stream goes to the Responses endpoint, and the client reads the events of the Responses
    * stream (see [[OpenAiClient.stream]]).
    *
    * A failure is a chunk of the stream, not an exception. When the client cannot open the connection, the result is
    * one last chunk with `finishReason = "error"`, the message under `raw`, and the typed failure in `error` (see
    * [[dspy4s.lm.contracts.LmChunk.failure]]). Thus a consumer always gets a complete stream, and the first read does
    * not throw.
    */
  override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
    client.stream(mode, body(request)) match
      case Right(iterator) => iterator
      case Left(error)     => Iterator.single(LmChunk.failure(error))

  /** The body of `request` on the wire of [[mode]]: the chat body, the prompt body of the text mode, or the Responses
    * body. Each body has the option bag of the request over [[defaultOptions]] and the model. The framework fields of
    * the request (the mode, `rolloutId`, and the cache identity) do not go into the body.
    */
  private def body(request: LmRequest): DynamicValue.Record =
    mode match
      case LmMode.Chat      => ChatWire.request(request, defaultOptions)
      case LmMode.Text      => ChatWire.textRequest(request, defaultOptions)
      case LmMode.Responses => ResponsesWire.request(request, defaultOptions)

  /** The response of the reply body `raw` on the wire of [[mode]]. A reply with an `error` object is the typed error of
    * its code, also with a success status ([[OpenAiErrors.fromReplyError]]). A reply with no output (no text and no
    * tool call) is an [[dspy4s.core.contracts.LmProviderResponseError]] ([[OpenAiErrors.noOutputs]]), with the usage of
    * the reply in its details: the provider can bill the prompt of such a reply, for example when a content filter
    * stops it. The error is not transient, so a retry policy does not retry it. The usage and the model name come from
    * the `usage` and `model` keys of the reply.
    *
    * @param requestModel
    *   the model of the request, for the details of an error
    */
  private def response(raw: DynamicValue, requestModel: String): Either[DspyError, LmResponse] =
    val outputs: Either[DspyError, Vector[LmOutput]] = mode match
      case LmMode.Chat      => ChatWire.outputs(raw, Some(requestModel))
      case LmMode.Text      => ChatWire.textOutputs(raw, Some(requestModel))
      case LmMode.Responses => ResponsesWire.outputs(raw, Some(requestModel))
    outputs.flatMap { entries =>
      val usage = DynamicValues.recordGet(raw, WireKeys.usage).flatMap(DynamicValues.asRecord)
        .map(OpenAiUsage.fromDynamic(_).toLmUsage)
      if entries.isEmpty then Left(OpenAiErrors.noOutputs(Some(requestModel), usage))
      else
        Right(LmResponse(
          outputs = entries,
          usage = usage,
          modelName = DynamicValues.recordGet(raw, WireKeys.model).flatMap(DynamicValues.asString),
          cacheHit = false
        ))
    }

  /** Close the client (see [[dspy4s.lm.contracts.LanguageModel.close]]). */
  override def close(): Unit = client.close()

object OpenAiLanguageModel:

  /** A provider for the default OpenAI base URL (`https://api.openai.com/v1`) with the JDK transport and the API key
    * `apiKey`.
    */
  def apply(model: String, apiKey: String): OpenAiLanguageModel =
    OpenAiLanguageModel(model = model, client = OpenAiClient(apiKey = apiKey))

  /** A provider for the base URL `baseUrl` with the transport `transport`. Use it for an OpenAI-compatible server (for
    * example Azure, Ollama, or vLLM), or for a stub `HttpTransport` in a test.
    */
  def apply(
      model    : String,
      apiKey   : String,
      baseUrl  : String,
      transport: HttpTransport
  ): OpenAiLanguageModel =
    OpenAiLanguageModel(
      model = model,
      client = OpenAiClient(apiKey = apiKey, baseUrl = baseUrl, transport = transport)
    )

  /** A provider with the API key from the environment. The result is a `Left(LmNotConfiguredError(...))` when `envVar`
    * is not set or is empty. Thus the caller gets a clear message at once, and not an HTTP 401 at the first call.
    *
    * `baseUrl` selects the OpenAI-compatible server, for example Azure, Ollama, vLLM, LM Studio, or OpenRouter. The
    * default is the OpenAI API. A local server that does not check credentials still needs a value in `envVar` that is
    * not empty, for example `OPENAI_API_KEY=local`. To use no environment variable, use [[local]].
    */
  def fromEnv(
      model  : String,
      baseUrl: String = OpenAiClient.defaultBaseUrl,
      envVar : String = "OPENAI_API_KEY"
  ): Either[DspyError, OpenAiLanguageModel] =
    OpenAiClient.fromEnv(base = baseUrl, envVar = envVar).map(client =>
      OpenAiLanguageModel(model = model, client = client)
    )

  /** A provider for a local OpenAI-compatible server that does not check credentials. It needs no environment variable
    * and no key. Usual base URLs: Ollama `http://localhost:11434/v1`, vLLM `http://localhost:8000/v1`, LM Studio
    * `http://localhost:1234/v1`. The request still has a bearer token with the value `apiKey`, because the wire format
    * needs the header. A server that checks credentials needs [[apply]] or [[fromEnv]] with a real key.
    */
  def local(model: String, baseUrl: String, apiKey: String = "local"): OpenAiLanguageModel =
    OpenAiLanguageModel(model = model, client = OpenAiClient(apiKey = apiKey, baseUrl = baseUrl))
