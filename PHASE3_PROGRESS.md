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

## Remaining for Phase 3

- Add provider-facing LM implementation(s) and request/response normalization for chat/responses modes.
- Expand usage/history parity to match DSPy tier-0 tests.
