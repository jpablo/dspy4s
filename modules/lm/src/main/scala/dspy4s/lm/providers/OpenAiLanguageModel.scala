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
import zio.blocks.schema.DynamicValue

/** The real OpenAI-compatible HTTP provider: a [[dspy4s.lm.contracts.StreamingLanguageModel]] that talks to a
  * chat-completions endpoint through an [[OpenAiClient]]. Both `call` and `stream` follow the same pipeline --
  * normalize the request into a provider payload, hand it to the client over HTTP, then parse the result back
  * into dspy4s's `LmResponse` / `LmChunk` contracts.
  *
  * "OpenAI-compatible" rather than "OpenAI": because the base URL and transport are owned by the injected
  * [[OpenAiClient]], the same type drives any server that speaks the `/chat/completions` shape (Azure OpenAI,
  * Ollama, vLLM, LM Studio, OpenRouter, ...). Use the [[OpenAiLanguageModel.apply(model:String,apiKey:String,baseUrl:String,transport:dspy4s\.lm\.providers\.HttpTransport)* baseUrl overload]]
  * to point it elsewhere.
  *
  * @param model          the provider model id (e.g. `"gpt-4o-mini"`); also surfaced as [[id]] for history/tracing.
  * @param mode           the request/response shape. `call` parses responses against THIS field (not
  *                       `request.mode`), so a provider configured for `Chat` always parses as chat. Defaults to
  *                       [[dspy4s.lm.contracts.LmMode.Chat]].
  * @param client         the HTTP client carrying the API key, base URL, transport, and endpoint path.
  * @param defaultOptions provider parameters merged into every request (e.g. `temperature`, `max_tokens`).
  *                       Per-request `LmRequest.options` take precedence -- see [[ProviderRequestNormalizer]].
  */
final case class OpenAiLanguageModel(
    model: String,
    mode: LmMode = LmMode.Chat,
    client: OpenAiClient,
    defaultOptions: DynamicValue.Record = DynamicValue.Record.empty
) extends StreamingLanguageModel:

  override val id: String = model

  // OpenAI-compatible chat completions support the tool/function-calling protocol and structured
  // `response_format` (JSON schema). Reasoning output is model-specific and not assumed here, so it is
  // left at the trait default of `false`.
  override val supportsFunctionCalling: Boolean = true
  override val supportsResponseSchema: Boolean  = true

  /** Normalize, POST to the chat-completions endpoint, and parse the JSON response into an `LmResponse`.
    * HTTP and decode failures arrive as a `Left[DspyError]` from the [[OpenAiClient]] (never an exception). */
  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    val normalized = ProviderRequestNormalizer.normalize(request, defaultOptions)
    client.invoke(normalized).flatMap(ProviderResponseParser.parse(_, mode))

  /** Open an SSE stream against the same endpoint and yield decoded [[dspy4s.lm.contracts.LmChunk]]s.
    *
    * Errors are reified into the stream rather than thrown: if the client can't open the connection, the result
    * is a single terminal chunk with `finishReason = "error"` and the message under `raw`, so consumers always
    * see a finalized stream instead of an `Iterator` that throws on first read. */
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

  /** Construct a provider against the default OpenAI base URL (`https://api.openai.com/v1`) and JDK transport,
    * given an explicit API key. */
  def apply(model: String, apiKey: String): OpenAiLanguageModel =
    OpenAiLanguageModel(model = model, client = OpenAiClient(apiKey = apiKey))

  /** Construct a provider against a custom base URL and transport -- the entry point for OpenAI-compatible
    * servers (Azure, Ollama, vLLM, ...) or for injecting a stub `HttpTransport` in tests. */
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

  /** Construct a provider by reading the API key from the environment. Returns a `Left(RuntimeError("openai_config", ...))`
    * when `envVar` is unset or empty, so callers fail fast with a clear message instead of a 401 at first call.
    *
    * `baseUrl` points the provider at any OpenAI-compatible server (Azure, Ollama, vLLM, LM Studio, OpenRouter,
    * …); the default is the real OpenAI API. For local servers that don't check credentials the env var still
    * must be set to SOMETHING non-empty (e.g. `OPENAI_API_KEY=local`) — or skip the environment entirely with
    * [[local]]. */
  def fromEnv(
      model: String,
      baseUrl: String = OpenAiClient.defaultBaseUrl,
      envVar: String = "OPENAI_API_KEY"
  ): Either[DspyError, OpenAiLanguageModel] =
    OpenAiClient.fromEnv(base = baseUrl, envVar = envVar).map(client => OpenAiLanguageModel(model = model, client = client))

  /** Construct a provider for a LOCAL OpenAI-compatible server that does not check credentials — the
    * no-environment, no-key route. Common base URLs: Ollama `http://localhost:11434/v1`, vLLM
    * `http://localhost:8000/v1`, LM Studio `http://localhost:1234/v1`. A placeholder bearer token is still sent
    * (the wire format requires the header); servers that DO check credentials need [[apply]] / [[fromEnv]] with
    * a real key. */
  def local(model: String, baseUrl: String, apiKey: String = "local"): OpenAiLanguageModel =
    OpenAiLanguageModel(model = model, client = OpenAiClient(apiKey = apiKey, baseUrl = baseUrl))
