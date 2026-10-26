# dspy4s `lm`

`lm` is the interface to the language models. It contains the request and response types, a provider for
OpenAI-compatible servers, a wrapper that adds a cache and retries to a model, and the usage trackers. It uses only the
[`core`](../core/README.md) module.

## Main concepts

- **One wire for each mode.** The mode of an `OpenAiLanguageModel` selects its wire: `ChatWire` makes the body of
  the chat mode and of the text mode from an `LmRequest`, and `ResponsesWire` makes the body of the Responses mode.
  Each wire also reads the reply into the outputs of an `LmResponse`. The bodies are `DynamicValue.Record` values. A
  request has no mode.
- **Provider options and control values.** `LmRequest.options` holds the provider options, for example `temperature`
  and `max_tokens`. The provider gets these options with no change. A framework value such as `rolloutId` is a
  separate field. It changes the cache key, and it does not go to the provider.
- **Capabilities.** A model tells if it supports native tool calls, a response schema, and `reasoning` output. The
  adapters read these flags. `OpenAiLanguageModel` declares the OpenAI capabilities only for `api.openai.com`. For
  another server, declare them in the `capabilities` field, for example with `copy(capabilities = Some(...))`.

## Main types

### Contracts

| Type | Description |
|------|-------------|
| `LanguageModel` / `StreamingLanguageModel` | The interface of a model: `call(request): Either[DspyError, LmResponse]`, the capability flags, and `close`. `StreamingLanguageModel` adds `stream(request): Iterator[LmChunk]`. |
| `LmRequest` / `LmMode` | The model name, the messages, the provider `options`, and an optional `rolloutId`. `LmMode` (`Chat`, `Text`, or `Responses`) is the mode of a model, not of a request. |
| `LmResponse` / `LmOutput` | The outputs, the usage, the model name, and the cache-hit flag. An output has a text, the tool calls, the reasoning, the citations, and the metadata. |
| `Message` / `MessageRole` | A chat message with a text or multimodal `parts`. An assistant message can have tool calls, and a `Tool` message names the call that it answers. |
| `LmCapabilities` | What a model can do: native tool calls, a response schema, and `reasoning` output. |
| `LmPrice` / `LmCost` | The price of the tokens of a model, and the cost estimate of one call. You declare the price, because the module has no price list. |
| `LmChunk` / `LmToolCallDelta` | One chunk of a stream (text, a finish reason, usage, a typed error, or a cache hit), and one part of a tool call. |
| `Embedder` | `embed(texts)` gives one vector for each text. `Embedder.cached` keeps the vector of each text. |

### Providers

| Type | Description |
|------|-------------|
| `OpenAiLanguageModel` | A `StreamingLanguageModel` for OpenAI-compatible servers. With `LmMode.Responses`, it uses the Responses API. Make one with `apply`, `fromEnv`, or `local`. |
| `OpenAiEmbedder` | An `Embedder` for the embeddings endpoint. It sends the texts in batches through an `OpenAiClient`. |
| `OpenAiClient` / `HttpTransport` | The HTTP client (calls, SSE streams, and typed LM errors) and the transport interface. `HttpTransport.jdk()` gives the JDK transport. |
| `ToolCallAssembler` | Joins the parts of the streamed tool calls into complete `ToolCall`s. |

### Runtime

| Type | Description |
|------|-------------|
| `ManagedLanguageModel` | Wraps a model with a cache, a `RetryPolicy`, the history, and a cost estimate. By default it has no cache and no retry. It records no usage. |
| `RetryPolicies` | `never`, `maxRetries`, `maxRetriesOnCodes`, and `exponentialBackoff` (with an optional jitter). |
| `UsageTracker` / `UsageTracking` | Records the token usage of each model name in a block of code. The record point is `LanguageModel.callAndRecordUsage`, which the programs use for each model call. A direct `call` or `stream` of a model counts in no tracker. |
| `InMemoryLmCache` / `DiskLmCache` / `CompositeLmCache` | A memory cache with a size limit, and a cache with one file for each entry. `CompositeLmCache` reads the memory cache first, then the disk cache. Give a cache to `ManagedLanguageModel` as `cache = Some(...)`. `NoopLmCache` (in `contracts`) stores nothing. |

## Design notes

- **The cache key.** `ManagedLanguageModel` computes the key of each call one time, and the caches get only that
  `CacheKey`. The key is a SHA-256 hash of the request (`RequestHash`), with the default options, the mode, and the
  cache identity of the model. The history entry also has the mode of the model (`model_type`). The key includes the `rolloutId`, so two samples with different `rolloutId` values get
  different entries. A `cacheKey` function changes the data of the key. A cache hit bills no tokens.
- **The disk record.** `DiskLmCache` writes one `<key>.json` file for each entry.
- **Streams.** A failure comes as the last chunk of the stream, with a typed error. A stream is complete when a chunk
  has a finish reason. Else the stream gives an `LmStreamAssemblyError`. `ManagedLanguageModel.stream` applies the
  rules of `call`, and the cache stores only a complete stream. The retry policy applies only before the first chunk
  with text or a tool call.
- **Tool call ids.** The reply parsers of the wires and `ToolCallAssembler` keep the provider id of each tool call in
  `ToolCall.id`. The wires send the ids back, so a request can replay a tool turn.
- **Typed failures.** A failed provider call gives an `LmError` from `core`, for example `LmRateLimitError` or
  `ContextWindowExceededError`. By default, a retry policy retries only a transient error: a rate limit, a timeout, a
  server failure, or a transport failure. When the provider sends a `Retry-After` hint, the delay is that hint.
- **No LM registry.** Make the model yourself, for example with `OpenAiLanguageModel.fromEnv(...)`. Programs get the
  active model from the `RuntimeContext` of `core`.

## Source layout

| Package | Contents |
|---------|----------|
| `contracts/` | the model, cache, retry, and embedder interfaces, the request, response, message, and stream types, the capabilities, and the price |
| `providers/` | the OpenAI-compatible model, embedder, and HTTP client, the HTTP transports, the HTTP error classes, the wires of the provider APIs (`ChatWire`, `ResponsesWire`), and `ToolCallAssembler` |
| `runtime/` | `ManagedLanguageModel`, the retry policies, the usage trackers, the caches, the cache key, the history entry, and the stream accumulator |

## Relation to dspy

This module has the role of `dspy.clients` and the `LM` class. The model gives the capability flags (`supports*`),
and the [adapters](../adapters/README.md) use them for native tool calls, as in dspy.
