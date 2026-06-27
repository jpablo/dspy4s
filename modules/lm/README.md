# dspy4s `lm`

The language-model boundary. `lm` defines provider-agnostic request/response contracts, the OpenAI-compatible
provider implementation, and a runtime layer that wraps any model with caching, retries, and usage tracking.
It depends only on [`core`](../core/README.md).

## The core idea

The module is three layers stacked so a new provider plugs in without touching callers:

- **Contracts** (`contracts/`) — the request/response shapes, token accounting, and the `LanguageModel` /
  `Embedder` interfaces. Provider-neutral.
- **Providers** (`providers/`) — the concrete bridge to OpenAI-compatible servers over HTTP/SSE, plus the
  typed wire DTOs that decode loose provider JSON leniently.
- **Runtime** (`runtime/`) — composable middleware that wraps a `LanguageModel` with a cache-then-retry stack,
  usage tracking, and request normalization/parsing for generic providers.

A request crosses the provider boundary as an untyped `DynamicValue.Record` (normalized from `LmRequest`), and
the response comes back as `DynamicValue` to be parsed — so any provider's wire shape can be adapted without
changing the domain types.

## Key types

### Contracts

| Type | Role |
|------|------|
| `LanguageModel` | The primary trait: `call(request): Either[DspyError, LmResponse]`. Advertises `supportsFunctionCalling` / `supportsResponseSchema`. |
| `StreamingLanguageModel` | Adds `stream(request): Iterator[LmChunk]` over server-sent events. |
| `LmRequest` | model + `LmMode` + `Vector[Message]` + provider `options` (untyped record) + optional `rolloutId`. |
| `LmResponse` | outputs + optional `LmUsage` + model name + cache-hit flag. |
| `Message` / `MessageRole` / `ContentPart` | A chat message (System/User/Assistant) with `text` or multimodal `parts`. |
| `LmOutput` | One response item: text, typed `Vector[ToolCall]`, metadata. |
| `LmMode` | `Chat` / `Text` / `Responses` — selects the wire shape. |
| `LmUsage` / `TokenCategory` | Token counters (prompt/completion/total) plus a typed map of extras (cached, reasoning, audio, …). |
| `Embedder` | `embed(texts): Either[DspyError, Vector[Vector[Float]]]`, with an `Embedder.cached` factory. |
| `LmChunk` / `LmToolCallDelta` | One streaming chunk (text, finish reason, usage) and incremental tool-call fragments. |
| `LmCache` / `RetryPolicy` | The memoization and retry interfaces the runtime layer composes. |

### Providers

| Type | Role |
|------|------|
| `OpenAiLanguageModel` | Concrete `StreamingLanguageModel` for OpenAI-compatible chat completions; factories `apply`, `fromEnv`, `local`. |
| `OpenAiEmbedder` | Concrete `Embedder` over the embeddings endpoint, batching by a configurable size. |
| `OpenAiClient` / `HttpTransport` | The HTTP client (chat + SSE streaming, context-window error detection) and the pluggable transport (`HttpTransport.jdk()`). |
| `ProviderLanguageModel` | A generic wrapper over an untyped `invoke: DynamicValue => Either[DspyError, DynamicValue]`, normalized by `ProviderRequestNormalizer` and parsed by `ProviderResponseParser`. |
| `OpenAiUsage` / `OpenAiStreamChunk` / `ToolCallAssembler` | Typed, leniently-decoded wire DTOs and the assembler that merges streaming tool-call deltas into complete `ToolCall`s. |

### Runtime

| Type | Role |
|------|------|
| `ManagedLanguageModel` | Wraps a `LanguageModel` with caching + retry + usage logging: cache hit returns; miss invokes with retries, then caches and tracks usage. |
| `RetryPolicies` | Pre-built policies: `never`, `maxRetries`, `exponentialBackoff` (with jitter). |
| `UsageTracker` / `UsageTracking` | Thread-local, nestable token accumulation by model name. |
| `InMemoryLmCache` / `DiskLmCache` / `CompositeLmCache` / `LmCacheRegistry` | A bounded LRU cache, a file-backed cache, a memory-then-disk stack, and the global cache singleton. |

## Design notes

- **The provider boundary is untyped JSON.** Requests serialize to a `DynamicValue.Record`; responses parse
  back from `DynamicValue`. This is the seam that keeps domain types provider-neutral.
- **Option bags are provider-bound; control values are typed.** `LmRequest.options` holds provider-specific
  knobs (`temperature`, `max_tokens`, `response_format`) and is spread verbatim into the payload. Framework
  control like `rolloutId` is a typed field that never reaches the provider — it folds into the cache key so
  repeated samples don't collide. (See the [provider-bag vs typed-control](../../README.md) decision.)
- **Caching is rollout-aware and layered.** The cache key (`RequestHash`, SHA-256) includes the rolloutId;
  `CompositeLmCache` promotes disk hits into memory.
- **Streaming closes cleanly.** `stream` returns a lazy `Iterator[LmChunk]`; a setup error is reified as a
  terminal error chunk rather than thrown, so consumers always see a closed stream. Tool-call deltas are
  reassembled by `ToolCallAssembler`, with a JSON-parse fallback that wraps an unparseable fragment.
- **No global LM registry.** Callers construct models explicitly (`OpenAiLanguageModel.fromEnv(...)`, then
  optionally `ManagedLanguageModel(...)`); the ambient `RuntimeContext` from `core` is how programs resolve the
  active model.

## Source layout

| File | Contents |
|------|----------|
| `contracts/LmContracts.scala` | `LmMode`, `Message`, `LmRequest`, `LmResponse`, `LmUsage`, `LmOutput`, `LanguageModel`, `LmCache`, `RetryPolicy` |
| `contracts/Embedder.scala`, `LmStreaming.scala`, `TokenCategory.scala` | embeddings, streaming chunk types, token categories |
| `providers/OpenAiLanguageModel.scala`, `OpenAiEmbedder.scala`, `OpenAiClient.scala` | the OpenAI-compatible model, embedder, and HTTP client |
| `providers/OpenAiUsage.scala`, `OpenAiStreamChunk.scala`, `DynamicJson.scala`, `WireKeys.scala` | typed wire DTOs, JSON helpers, wire-field constants |
| `providers/HttpTransport.scala`, `JdkHttpTransport.scala` | transport interface + JDK implementation |
| `runtime/ProviderLanguageModel.scala` | generic provider wrapper + request normalizer / response parser |
| `runtime/ManagedLanguageModel.scala` | cache+retry wrapper, `RetryPolicies`, usage tracking |
| `runtime/CacheRuntime.scala` | `RequestHash` and the in-memory / disk / composite caches + registry |
| `runtime/ToolCallAssembler.scala` | streaming tool-call delta merging |

## Relation to dspy

This is the dspy4s analogue of `dspy.clients` / the `LM` abstraction. Native function-calling support is
advertised here (the `supports*` flags) but is applied at the [adapter layer](../adapters/README.md), matching
dspy's split between the client and the adapter.
