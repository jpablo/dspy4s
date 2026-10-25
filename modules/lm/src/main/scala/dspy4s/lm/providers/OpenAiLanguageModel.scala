package dspy4s.lm.providers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.LmCapabilities
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import zio.blocks.schema.DynamicValue

/** The real OpenAI-compatible HTTP provider: a [[dspy4s.lm.contracts.StreamingLanguageModel]] that talks to a
  * chat-completions endpoint through an [[OpenAiClient]]. `call` and `stream` follow the same steps: they build the
  * body of the request on the wire of [[mode]] ([[ChatWire]] or [[ResponsesWire]]), send it with the client, and read
  * the reply into the `LmResponse` and `LmChunk` contracts.
  *
  * "OpenAI-compatible" rather than "OpenAI": because the base URL and transport are owned by the injected
  * [[OpenAiClient]], the same type drives any server that speaks the `/chat/completions` shape (Azure OpenAI, Ollama,
  * vLLM, LM Studio, OpenRouter, ...). Use the
  * [[OpenAiLanguageModel.apply(model:String,apiKey:String,baseUrl:String,transport:dspy4s\.lm\.providers\.HttpTransport)* baseUrl overload]]
  * to point it elsewhere.
  *
  * @param model
  *   the provider model id (e.g. `"gpt-4o-mini"`); also surfaced as [[id]] for history/tracing.
  * @param mode
  *   the wire of the model. This field selects the endpoint, the body, and the parse. The mode of a request does not: a
  *   model in the chat mode sends the messages of each request, a model in the text mode sends a prompt, and a model in
  *   the Responses mode calls the Responses API. The default is [[dspy4s.lm.contracts.LmMode.Chat]].
  * @param client
  *   the HTTP client carrying the API key, base URL, transport, and endpoint path.
  * @param defaultOptions
  *   provider parameters merged into every request (e.g. `temperature`, `max_tokens`). The options of a request win
  *   over them ([[dspy4s.lm.contracts.LmRequest.effectiveOptions]]).
  * @param capabilities
  *   what the model can do. `None` gives the capabilities that the port declares for the base URL of the client
  *   ([[dspy4s.lm.contracts.LmCapabilities.forBaseUrl]]): the OpenAI capabilities for the official address, and no
  *   capability for an other server (decision L5). Set it with [[withCapabilities]].
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
  def effectiveCapabilities: LmCapabilities = capabilities.getOrElse(LmCapabilities.forBaseUrl(client.baseUrl))

  override def supportsFunctionCalling: Boolean = effectiveCapabilities.functionCalling
  override def supportsResponseSchema: Boolean  = effectiveCapabilities.responseSchema
  override def supportsReasoning: Boolean       = effectiveCapabilities.reasoning

  /** The same model with the declared `capabilities`. */
  def withCapabilities(declared: LmCapabilities): OpenAiLanguageModel = copy(capabilities = Some(declared))

  /** Build the body, POST it to the endpoint of [[mode]], and read the JSON reply into an `LmResponse`. HTTP and decode
    * failures arrive as a `Left[DspyError]` from the [[OpenAiClient]] (never an exception).
    *
    * With [[dspy4s.lm.contracts.LmMode.Responses]], the request goes to the Responses API, and the reply gives one
    * output (`ResponsesWire.outputs`, upstream `responses_outputs`).
    */
  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    client.invoke(mode, body(request)).flatMap(response)

  /** Open an SSE stream against the same endpoint and yield decoded [[dspy4s.lm.contracts.LmChunk]]s.
    *
    * In the Responses mode, the stream goes to the Responses endpoint, and the client reads the events of the Responses
    * stream (see [[OpenAiClient.stream]]).
    *
    * Errors are reified into the stream rather than thrown: if the client can't open the connection, the result is a
    * single terminal chunk with `finishReason = "error"`, the message under `raw`, and the typed failure in `error`
    * (see [[dspy4s.lm.contracts.LmChunk.failure]]), so consumers always see a finalized stream instead of an `Iterator`
    * that throws on first read.
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

  /** The response of the reply body `raw` on the wire of [[mode]]. A reply with no output (no text and no tool call) is
    * a `ParseError`. The usage and the model name come from the `usage` and `model` keys of the reply.
    */
  private def response(raw: DynamicValue): Either[DspyError, LmResponse] =
    val outputs: Either[DspyError, Vector[LmOutput]] = mode match
      case LmMode.Chat      => ChatWire.outputs(raw)
      case LmMode.Text      => ChatWire.textOutputs(raw)
      case LmMode.Responses => ResponsesWire.outputs(raw)
    outputs.flatMap { entries =>
      if entries.isEmpty then Left(ParseError("lm", "Provider response does not contain any outputs"))
      else
        Right(LmResponse(
          outputs = entries,
          usage = DynamicJson.field(raw, WireKeys.usage).flatMap(DynamicJson.asRecord)
            .map(OpenAiUsage.fromDynamic(_).toLmUsage),
          modelName = DynamicJson.field(raw, WireKeys.model).flatMap(DynamicJson.asString),
          cacheHit = false
        ))
    }

  /** Close the client (see [[dspy4s.lm.contracts.LanguageModel.close]]). */
  override def close(): Unit = client.close()

object OpenAiLanguageModel:

  /** Construct a provider against the default OpenAI base URL (`https://api.openai.com/v1`) and JDK transport, given an
    * explicit API key.
    */
  def apply(model: String, apiKey: String): OpenAiLanguageModel =
    OpenAiLanguageModel(model = model, client = OpenAiClient(apiKey = apiKey))

  /** Construct a provider against a custom base URL and transport -- the entry point for OpenAI-compatible servers
    * (Azure, Ollama, vLLM, ...) or for injecting a stub `HttpTransport` in tests.
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

  /** Construct a provider by reading the API key from the environment. Returns a `Left(LmNotConfiguredError(...))` when
    * `envVar` is unset or empty, so callers fail fast with a clear message instead of a 401 at first call.
    *
    * `baseUrl` points the provider at any OpenAI-compatible server (Azure, Ollama, vLLM, LM Studio, OpenRouter, …); the
    * default is the real OpenAI API. For local servers that don't check credentials the env var still must be set to
    * SOMETHING non-empty (e.g. `OPENAI_API_KEY=local`) — or skip the environment entirely with [[local]].
    */
  def fromEnv(
      model  : String,
      baseUrl: String = OpenAiClient.defaultBaseUrl,
      envVar : String = "OPENAI_API_KEY"
  ): Either[DspyError, OpenAiLanguageModel] =
    OpenAiClient.fromEnv(base = baseUrl, envVar = envVar).map(client =>
      OpenAiLanguageModel(model = model, client = client)
    )

  /** Construct a provider for a LOCAL OpenAI-compatible server that does not check credentials — the no-environment,
    * no-key route. Common base URLs: Ollama `http://localhost:11434/v1`, vLLM `http://localhost:8000/v1`, LM Studio
    * `http://localhost:1234/v1`. A placeholder bearer token is still sent (the wire format requires the header);
    * servers that DO check credentials need [[apply]] / [[fromEnv]] with a real key.
    */
  def local(model: String, baseUrl: String, apiKey: String = "local"): OpenAiLanguageModel =
    OpenAiLanguageModel(model = model, client = OpenAiClient(apiKey = apiKey, baseUrl = baseUrl))
