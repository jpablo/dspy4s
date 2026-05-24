# Phase 3 Progress

Phase 3 focuses on LM runtime semantics (cache, retry, history, and usage tracking).

## Implemented in this step

1. LM runtime cache foundation
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/main/scala/dspy4s/lm/runtime/CacheRuntime.scala`
- Added deterministic request hashing (`RequestHash`) for `LmRequest`
- Added in-memory LRU cache implementation (`InMemoryLmCache`)
- Cache read path marks responses as `cacheHit = true` and strips usage payloads

2. Managed LM wrapper with retry/cache behavior
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/main/scala/dspy4s/lm/runtime/ManagedLanguageModel.scala`
- Added `ManagedLanguageModel` wrapper for:
  - cache lookup/store
  - retry loop driven by `RetryPolicy`
  - rollout-aware provider request normalization (`rollout_id` removed before delegate call)
- Added retry policy helpers (`RetryPolicies.never`, `RetryPolicies.maxRetries`)

3. Usage tracking utility
- Added `UsageTracker` and `UsageTracking` scoped tracker utilities
- Added usage aggregation per model (`totalUsage`)
- Wired usage recording from uncached `ManagedLanguageModel` responses

4. History integration
- `ManagedLanguageModel` appends LM history entries for success/failure calls
- Honors runtime setting `disable_history`

5. LM tests
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/test/scala/dspy4s/lm/LmRuntimeSuite.scala`
- Added coverage for:
  - deterministic request hashing
  - cache hit behavior
  - rollout-id cache partitioning + provider request normalization
  - retry attempt behavior
  - usage tracking on uncached calls only

6. Build/module metadata
- Enabled `munit` for `lm` tests in `/Users/jpablo/proyectos/experimentos/dspy4s/build.sbt`
- Updated `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/main/scala/dspy4s/lm/LmApi.scala` to `contractsPhase = "phase-3"`

7. Disk cache + cache configuration API
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/main/scala/dspy4s/lm/runtime/CacheRuntime.scala`
- Added `DiskLmCache` with filesystem persistence and bounded entry eviction
- Added `CompositeLmCache` for memory+disk layering
- Added cache configuration/runtime APIs:
  - `LmCacheConfig`
  - `LmCaches.build(...)`
  - `LmCacheRegistry` (`current`, `configure`, `resetDefault`)
- Added memory-only/noop configuration behavior for disabled cache modes

8. Retry policy expansion
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/main/scala/dspy4s/lm/contracts/LmContracts.scala`
  - `RetryPolicy.delayBeforeNextAttemptMillis(...)`
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/main/scala/dspy4s/lm/runtime/ManagedLanguageModel.scala`
  - delay-aware retry loop with injectable sleep function
  - new policies:
    - `RetryPolicies.maxRetriesOnCodes(...)`
    - `RetryPolicies.exponentialBackoff(...)`
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/test/scala/dspy4s/lm/LmRuntimeSuite.scala`
  - deterministic backoff-delay assertions
  - retry classification by error code

9. Provider-facing LM normalization and parsing layer
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/main/scala/dspy4s/lm/runtime/ProviderLanguageModel.scala`
- Added request normalization helpers:
  - `ProviderRequestNormalizer` (`chat`, `text`, `responses`)
- Added response parsing helpers:
  - `ProviderResponseParser` (choices/output blocks, tool calls, usage)
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/test/scala/dspy4s/lm/ProviderLanguageModelSuite.scala`
  - chat request normalization coverage
  - text prompt normalization coverage
  - chat/responses parsing coverage
  - empty-output parse error coverage

10. Usage/history runtime parity expansion
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/contracts/Runtime.scala`
  - added `SettingKeys.maxHistorySize`
  - added `RuntimeContext.withHistory(...)`
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/runtime/RuntimeEnvironment.scala`
  - `appendHistory` now honors:
    - `disable_history`
    - `max_history_size` truncation semantics
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/test/scala/dspy4s/core/RuntimeEnvironmentSuite.scala`
  - added history truncation and disable-history coverage
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/test/scala/dspy4s/lm/LmRuntimeSuite.scala`
  - added `track_usage=false` coverage for usage tracker suppression

## Remaining for Phase 3

- No open blockers for the Phase 3 target subset.
- Next focus should move to Phase 4 adapters parity (`ChatAdapter`, `JSONAdapter`, `XMLAdapter`, tool schema/tool calls).

## Phase 3 Extension — OpenAI HTTP provider (real LM client)

Added a real OpenAI chat-completions HTTP provider that implements both `LanguageModel` and `StreamingLanguageModel`.

### Implemented in this step

1. JSON codec
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/main/scala/dspy4s/lm/providers/JsonCodec.scala`
- `Map[String, Any]` ⇄ ujson `Value` round-trip handling nested maps, vectors, primitives, `None` stripping
- Parse error diagnostics for malformed JSON

2. HTTP transport abstraction + JDK client
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/main/scala/dspy4s/lm/providers/HttpTransport.scala`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/main/scala/dspy4s/lm/providers/JdkHttpTransport.scala`
- `HttpTransport` trait with `sendJson` (string body) and `streamSse` (line iterator) contracts
- `JdkHttpTransport` uses `java.net.http.HttpClient` (zero extra deps) with configurable timeout
- SSE iterator wraps `HttpResponse.BodyHandlers.ofLines()` and is `ClosableIterator` so connection closes when drained/closed
- HTTP/IO exception mapping to `RuntimeError(component = "openai_http|openai_timeout|...", message)`

3. OpenAI client
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/main/scala/dspy4s/lm/providers/OpenAiClient.scala`
- `invoke(payload: Map[String, Any]): Either[DspyError, Map[String, Any]]` for non-streaming
- `stream(payload): Either[DspyError, ClosableIterator[LmChunk]]` for SSE streaming
- Auto-injects `stream=true` + `stream_options.include_usage=true` for streaming calls
- Status-aware error mapping: 401/403 → `openai_auth`, 404 → `openai_not_found`, 429 → `openai_rate_limit`, 5xx → `openai_server`
- `OpenAiClient.fromEnv()` reads `OPENAI_API_KEY` (or custom env var) with structured error on miss

4. OpenAI language model
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/main/scala/dspy4s/lm/providers/OpenAiLanguageModel.scala`
- Extends `StreamingLanguageModel` — plugs directly into `Predict`, `ManagedLanguageModel`, and `Streamify.streamify`
- Routes through `ProviderRequestNormalizer` + `ProviderResponseParser` (already phase-3) for consistent response shape
- Supports `defaultOptions` (e.g. `temperature`), `model`, `mode`, and `client` injection
- `OpenAiLanguageModel.fromEnv(model)` constructor for env-based wiring

5. ClosableIterator relocation
- Moved `ClosableIterator[A]` trait to `dspy4s.core.contracts` (general-purpose `Iterator` + `AutoCloseable`)
- Removed cyclic dependency between `lm` and `streaming` modules
- Both modules now import from core; streaming module still re-uses it for `StreamingQueue.asIterator`

6. Build updates
- Added `ujson % 4.0.2` to `lm` module (already used by `adapters`)

7. Tests
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/test/scala/dspy4s/lm/providers/JsonCodecSuite.scala` (6 cases)
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/test/scala/dspy4s/lm/providers/OpenAiClientSuite.scala` (7 cases with scripted transport)
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/test/scala/dspy4s/lm/providers/OpenAiLanguageModelSuite.scala` (4 end-to-end cases)
- Coverage includes: auth header propagation, 429/500/401 error mapping with correct error components, SSE chunk parsing including `finish_reason` and usage, malformed-line skipping, defaultOptions override

### Validation

- Ran full test suite on 2026-05-22. All 142 tests pass (125 baseline + 17 OpenAI provider).

### Usage

```scala
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.runtime.RuntimeEnvironment

OpenAiLanguageModel.fromEnv("gpt-4o-mini").foreach { lm =>
  RuntimeEnvironment.withSetting(SettingKeys.languageModel, lm) {
    // program.run(...) or Streamify.streamify(program)(inputs)
  }
}
```

### Remaining gaps

- Anthropic/Ollama/LiteLLM providers not yet (OpenAI-compatible APIs like Azure still work via `baseUrl` override)
- ~~Tool-call delta accumulation in streaming~~ — shipped: `LmChunk.toolCalls` carries `LmToolCallDelta`s; `ToolCallAssembler` merges per-index deltas and populates `LmOutput.toolCalls` on the synthesized streaming response. See `modules/lm/src/main/scala/dspy4s/lm/runtime/ToolCallAssembler.scala` and `StreamingToolCallSuite`.
- Response mode endpoint not exercised end-to-end against a live API
