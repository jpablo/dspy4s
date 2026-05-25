# Phase 8 Progress — Streaming v1

> **Historical snapshot.** Captures what shipped in this phase. For the current
> API see [ARCHITECTURE.md](ARCHITECTURE.md) and [TYPED_SIGNATURES_GUIDE.md](TYPED_SIGNATURES_GUIDE.md);
> for the running per-feature ledger see [PORT_MAP.md](PORT_MAP.md) and
> [PORT_BACKLOG.md](PORT_BACKLOG.md). Symbols referenced below may have been
> renamed since (e.g. the original `Signature` is now `SignatureLayout` and the
> typed wrapper carries that name).


Phase 8 focuses on streaming runtime parity.

## Implemented in this step

1. LM streaming contracts
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/lm/src/main/scala/dspy4s/lm/contracts/LmStreaming.scala`
- Added `LmChunk` (text delta, finish reason, usage, raw payload)
- Added `StreamingLanguageModel` trait extending `LanguageModel` with `stream(): Iterator[LmChunk]`

2. Streaming queue
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/streaming/src/main/scala/dspy4s/streaming/StreamingQueue.scala`
- Added `StreamingQueue[A]` — bounded producer/consumer using `LinkedBlockingQueue[Option[A]]`
- Added `ClosableIterator[A]` (`Iterator` + `AutoCloseable`) for consumer-side cleanup
- Added `close()` idempotency and `isClosed` introspection

3. Status message streaming
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/streaming/src/main/scala/dspy4s/streaming/StatusMessageProvider.scala`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/streaming/src/main/scala/dspy4s/streaming/StatusStreamingCallback.scala`
- `StatusMessageProvider` provides customizable status text for module/lm/tool start/end events
- `StatusStreamingCallback` bridges `CallbackEvent` → `StatusEvent` written to the queue

4. Streaming LM wrapper
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/streaming/src/main/scala/dspy4s/streaming/StreamingLanguageModelWrapper.scala`
- Wraps a `StreamingLanguageModel` and pumps `LmChunk` → `TokenEvent` to the queue during `call()`/`stream()`
- Returns a synthesized `LmResponse` accumulated from chunks for callers that don't stream

5. Streamify entry point
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/streaming/src/main/scala/dspy4s/streaming/Streamify.scala`
- `Streamify.streamify(program, statusMessageProvider, ...)` returns `Map[String, Any] => ClosableIterator[StreamEvent]`
- Captures caller `RuntimeContext`, wraps streaming LM, registers status callback
- Runs the program in a daemon producer thread; emits final `PredictionEvent` or `ErrorEvent` and closes queue
- Multiple invocations produce independent streams

6. Streaming contract refresh
- Updated `/Users/jpablo/proyectos/experimentos/dspy4s/modules/streaming/src/main/scala/dspy4s/streaming/contracts/StreamingContracts.scala`
- `Streamifier` trait now uses `ProgramCall` and returns `ClosableIterator[StreamEvent]`
- `StreamingApi.contractsPhase = "phase-8-v1"`

7. Tests
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/streaming/src/test/scala/dspy4s/streaming/StreamingQueueSuite.scala` (6 tests)
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/streaming/src/test/scala/dspy4s/streaming/StatusStreamingCallbackSuite.scala` (5 tests)
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/streaming/src/test/scala/dspy4s/streaming/StreamifySuite.scala` (5 tests)
- Coverage includes: ordering, blocking, idempotent close, status event filtering, custom providers, streaming LM path, non-streaming LM fallback, error propagation, multi-stream independence

8. Build configuration
- Added `munit` test dependency for streaming module

## Validation

- Ran full test suite with `sbt test` on 2026-05-22.
- Result: all 125 tests pass (35 programs + 16 adapters + 16 lm + 16 streaming + 42 core).

## Remaining for Phase 8

See `STREAMING_POSTPONED.md` for the full deferred backlog:
- Per-field `StreamListener` with adapter-specific chunk state machines (Chat/JSON/XML)
- Real LM provider streaming client (OpenAI SSE etc.)
- Structured-concurrency streaming (fs2 / ZIO)
- `streaming_response` OpenAI-compatible SSE output
- Async program streaming path
