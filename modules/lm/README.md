# dspy4s `lm`

The language-model boundary. `lm` defines provider-agnostic request/response contracts, the OpenAI-compatible
provider implementation, and a runtime layer that wraps any model with caching, retries, and usage tracking.
It depends only on [`core`](../core/README.md).

## The core idea

The module is three layers stacked so a new provider plugs in without touching callers:

- **Contracts** (`contracts/`) — the request/response shapes, token accounting, and the `LanguageModel` /
  `Embedder` interfaces. Provider-neutral.
- **Providers** (`providers/`) — the concrete bridge to OpenAI-compatible servers over HTTP/SSE, plus the
  wire DTOs that decode loose provider JSON leniently.
- **Runtime** (`runtime/`) — composable middleware that wraps a `LanguageModel` with a cache-then-retry stack,
  usage tracking, and request normalization/parsing for generic providers.

A request crosses the provider boundary as a schema-free `DynamicValue.Record` (normalized from `LmRequest`), and
the response comes back as `DynamicValue` to be parsed — so any provider's wire shape can be adapted without
changing the domain types.

## Key types

### Contracts

| Type | Role |
|------|------|
| `LanguageModel` | The primary trait: `call(request): Either[DspyError, LmResponse]`. Advertises `supportsFunctionCalling` / `supportsResponseSchema`. |
| `StreamingLanguageModel` | Adds `stream(request): Iterator[LmChunk]` over server-sent events. |
| `LmRequest` | model + `LmMode` + `Vector[Message]` + provider `options` (schema-free record) + optional `rolloutId`. |
| `LmResponse` | outputs + optional `LmUsage` + model name + cache-hit flag. |
| `Message` / `MessageRole` / `ContentPart` | A chat message (System/User/Assistant/Tool) with `text` or multimodal `parts`. An assistant message can carry tool calls, and a tool message names the call that it answers. |
| `LmOutput` | One response item: text, `Vector[ToolCall]`, metadata. |
| `LmMode` | `Chat` / `Text` / `Responses` — selects the wire shape. |
| `LmUsage` / `TokenCategory` | Core-defined token counters (prompt/completion/total) plus a `TokenCategory`-keyed map of extras (cached, reasoning, audio, …). |
| `Embedder` | `embed(texts): Either[DspyError, Vector[Vector[Float]]]`, with an `Embedder.cached` factory. |
| `LmChunk` / `LmToolCallDelta` | One streaming chunk (text, finish reason, usage, a typed error, or the response of a cache hit) and incremental tool-call fragments. |
| `LmCache` / `RetryPolicy` | The memoization and retry interfaces the runtime layer composes. |

### Providers

| Type | Role |
|------|------|
| `OpenAiLanguageModel` | Concrete `StreamingLanguageModel` for OpenAI-compatible chat completions; factories `apply`, `fromEnv`, `local`. |
| `OpenAiEmbedder` | Concrete `Embedder` over the embeddings endpoint, batching by a configurable size. |
| `OpenAiClient` / `HttpTransport` | The HTTP client (chat + SSE streaming, typed LM errors) and the pluggable transport (`HttpTransport.jdk()`). |
| `ProviderLanguageModel` | A generic wrapper over `invoke: DynamicValue => Either[DspyError, DynamicValue]`, normalized by `ProviderRequestNormalizer` and parsed by `ProviderResponseParser`. |
| `OpenAiUsage` / `OpenAiStreamChunk` / `ToolCallAssembler` | Leniently decoded wire DTOs and the assembler that merges streaming tool-call deltas into complete `ToolCall`s. |

### Runtime

| Type | Role |
|------|------|
| `ManagedLanguageModel` | Wraps a `LanguageModel` with caching + retry + history + usage logging, for a call and for a stream: cache hit returns; miss invokes with retries, then caches and tracks usage. |
| `RetryPolicies` | Pre-built policies: `never`, `maxRetries`, `maxRetriesOnCodes`, `exponentialBackoff` (with jitter). By default they retry transient LM errors only, and they obey the `Retry-After` hint. |
| `UsageTracker` / `UsageTracking` | Thread-local, nestable token accumulation by model name. |
| `InMemoryLmCache` / `DiskLmCache` / `CompositeLmCache` / `LmCacheRegistry` | A bounded LRU cache, a file-backed cache, a memory-then-disk stack, and the global cache singleton. |

## Design notes

- **The provider boundary is schema-free JSON.** Requests serialize to a `DynamicValue.Record`; responses parse
  back from `DynamicValue`. This is the seam that keeps domain types provider-neutral.
- **Option bags are provider-bound; control values are first-class fields.** `LmRequest.options` holds provider-specific
  knobs (`temperature`, `max_tokens`, `response_format`) and is spread verbatim into the payload. Framework
  control like `rolloutId` is a dedicated field that never reaches the provider — it folds into the cache key so
  repeated samples don't collide. (See the [provider-bag vs request-control](../../README.md) decision.)
- **Caching is rollout-aware and layered.** The cache key (`RequestHash`, SHA-256) includes the rolloutId;
  `CompositeLmCache` promotes disk hits into memory.
- **Streaming closes cleanly.** `stream` returns a lazy `Iterator[LmChunk]`; a setup error is reified as a
  terminal error chunk rather than thrown, so consumers always see a closed stream. Tool-call deltas are
  reassembled by `ToolCallAssembler`, with a JSON-parse fallback that wraps an unparseable fragment.
- **A stream must be complete.** A stream is complete when a chunk has a finish reason. On the OpenAI wire, a
  chunk with usage and the `[DONE]` line also end the stream. A stream that stops before its end, and a
  `data:` line that is not JSON, give an `LmStreamAssemblyError`. A mid-stream `{"error": ...}` event gives the
  typed error of its code, so a `ContextWindowExceededError` keeps its class (upstream `checked_stream` and
  `parse_stream_events`).
- **One call path for streamed calls.** `ManagedLanguageModel.stream` applies the rules of `call`. A cache hit
  gives one `LmChunk.cached` chunk, and the stream wrapper gives no token of it to a listener. The cache stores
  a complete stream only. A failure before the first chunk with text or a tool call gets the retry policy;
  after that chunk, there is no retry. Each streamed call has one history entry, and the usage of its chunks is
  recorded (upstream `execute`).
- **A tool call keeps its id.** The response parser and `ToolCallAssembler` copy the id of the provider into
  `ToolCall.id`: `call_id` when the entry has one, else `id`. `ProviderRequestNormalizer` sends the tool calls of
  an assistant message as `tool_calls`, and a tool message with `tool_call_id` and `name`, so a request can
  replay a tool turn. The prompt of `LmMode.Text` takes the text of each message only. The cache key covers the
  tool fields only when a message has them, and `DiskLmCache` reads the entries that an older version wrote.
- **Failures are typed.** A failure of a provider call is an `LmError` from `core` (`LmRateLimitError`,
  `LmAuthError`, `ContextWindowExceededError`, and others). `OpenAiErrors` gives each HTTP error response its
  class from the provider code and the status. The `details` of the error hold the status, the request id, and
  the `Retry-After` hint. The default retry predicate accepts the transient classes only (rate limit, timeout,
  server failure, transport failure), and the delay before the next attempt is the hint of the provider when
  one exists.
- **No global LM registry.** Callers construct models explicitly (`OpenAiLanguageModel.fromEnv(...)`, then
  optionally `ManagedLanguageModel(...)`); the ambient `RuntimeContext` from `core` is how programs resolve the
  active model.

## Source layout

| File | Contents |
|------|----------|
| `contracts/LmRequest.scala`, `LmResponse.scala` | message, request, output, and response data; usage types live in `core` |
| `contracts/LanguageModel.scala`, `LmCache.scala`, `RetryPolicy.scala` | model, cache, and retry interfaces |
| `contracts/Embedder.scala`, `LmStreaming.scala` | embeddings and streaming chunk types |
| `providers/OpenAiLanguageModel.scala`, `OpenAiEmbedder.scala`, `OpenAiClient.scala` | the OpenAI-compatible model, embedder, and HTTP client |
| `providers/OpenAiErrors.scala` | the map from an HTTP error response to an LM error class |
| `providers/OpenAiUsage.scala`, `OpenAiStreamChunk.scala`, `DynamicJson.scala`, `WireKeys.scala` | wire DTOs, JSON helpers, wire-field constants |
| `providers/HttpTransport.scala`, `JdkHttpTransport.scala` | transport interface + JDK implementation |
| `runtime/ProviderLanguageModel.scala` | generic provider wrapper + request normalizer / response parser |
| `runtime/ManagedLanguageModel.scala` | cache+retry wrapper, `RetryPolicies`, usage tracking |
| `runtime/RequestHash.scala` | canonical request encoding and cache-key hashing |
| `runtime/InMemoryLmCache.scala`, `DiskLmCache.scala`, `CompositeLmCache.scala` | memory, disk, composite, and no-op cache implementations |
| `runtime/LmCacheConfiguration.scala` | cache defaults, configuration, construction, and registry |
| `runtime/ToolCallAssembler.scala` | streaming tool-call delta merging |

## Relation to dspy

This is the dspy4s analogue of `dspy.clients` / the `LM` abstraction. Native function-calling support is
advertised here (the `supports*` flags) but is applied at the [adapter layer](../adapters/README.md), matching
dspy's split between the client and the adapter.
