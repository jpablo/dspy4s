# dspy4s Streaming — Postponed Features (v1 → v2)

This document tracks streaming features deferred from the v1 implementation to later phases.

## Shipped in v1 (Phase 8 — minimal slice)

- `LmChunk` + `StreamingLanguageModel` trait (in `lm.contracts`)
- `StreamingQueue[A]` — bounded blocking queue + `ClosableIterator[A]`
- `StatusMessageProvider` trait with default behavior (tool start/end messages)
- `StatusStreamingCallback` — bridges `CallbackHandler` events to stream
- `StreamingLanguageModelWrapper` — pumps LM chunks to queue during `call()`
- `Streamify.streamify(...)` — producer-thread + iterator-based streaming
- `TokenEvent` emitted for raw (unparsed) text chunks when LM is streaming
- `StatusEvent`, `PredictionEvent`, `ErrorEvent` emitted from the stream
- 16 tests across `StreamingQueueSuite`, `StatusStreamingCallbackSuite`, `StreamifySuite`

## Shipped in v1.2 — Per-field `StreamListener` (Chat adapter, Slice A)

- `AdapterStreamingState` trait + `FieldChunk` data class added to
  `adapters.contracts`. Adapters implement `Adapter.streamingState(signature)`
  to opt in.
- `ChatStreamingState` implements line-based field detection for
  `ChatAdapter`'s `prefix: value` framing. Buffers held-back partial lines so
  a label that arrives across multiple tokens (e.g. `"ans"` then `"wer:"`)
  is never leaked into the previous field's stream. `finish()` flushes
  remaining buffer and marks the last chunk `isLast = true`.
- `StreamListener` redefined from a placeholder trait to a configuration
  case class with `signatureFieldName` and optional `predictName`.
- `StreamingLanguageModelWrapper` builds a fresh state per `call()`,
  drives it with each chunk's text, and emits one `TokenEvent` per
  `FieldChunk`. When no state is supplied it preserves the previous
  raw-token behavior.
- `Streamify` resolves the active `Adapter` from settings and extracts
  the signature from the program (only `Predict` for now; compound programs
  fall back to raw-token emission until Slice D).
- 14 tests across `ChatStreamingStateSuite` (9) and `StreamListenerSuite` (5).

### Notes / behavioral deltas from Python parity

- dspy4s `ChatAdapter` uses `prefix: value` line framing, not Python's
  `[[ ## field ## ]]`. The state machine is correspondingly simpler. The
  consequence: if the model emits text whose lines start with an unknown
  label (e.g. `extra:`), the state machine treats it as continuation of
  the most recently entered field, since it has no way to distinguish
  "stray text" from "value with a colon in it".
- Multi-predictor programs (`ChainOfThought`, `ReAct`) still fall through
  to raw-token mode — Slice D will route per-predict.

## Shipped in v1.1 — Tool-call delta accumulation

- `LmToolCallDelta(index, id, name, argumentsFragment)` added to `lm.contracts`
- `LmChunk.toolCalls: Vector[LmToolCallDelta]` carries per-chunk fragments
- `OpenAiClient.chunkFromPayload` parses `delta.tool_calls` from each SSE frame
- `ToolCallAssembler.assemble(...)` merges fragments by index, JSON-decodes the
  concatenated `arguments` string, and falls back to `Map("input" -> raw)` on
  non-object arguments — matching `ProviderResponseParser.parseArgs`
- `StreamingLanguageModelWrapper.call` accumulates deltas across the chunk
  stream and populates `LmOutput.toolCalls` on the synthesized `LmResponse`,
  so ReAct / native-tool flows behave identically over a streaming LM
- 10 tests across `ToolCallAssemblerSuite`, `OpenAiClientSuite`,
  `StreamingToolCallSuite`

## Postponed — Per-field `StreamListener`: JSON / XML / multi-predictor (Slices B, C, D)

Slice A (ChatAdapter) shipped — see the v1.2 section above. Remaining:

- **JSONAdapter streaming state (Slice B)**: partial JSON parsing to detect
  the next key in the stream as the field boundary. No `jiter` JVM artifact
  off the shelf; the planned approach is a small incremental object parser
  that tracks brace depth and string state, emitting per-key value fragments.
- **XMLAdapter streaming state (Slice C)**: `<field_name>` / `</field_name>`
  boundary detection with the same buffer-and-flush discipline as
  `ChatStreamingState`.
- **Multi-predictor routing + `allow_reuse` + `finalize` edge cases (Slice D)**:
  - Add `predict_id` to `LmOutput` / `LmResponse` so the streamify wrapper can
    route chunks to the correct listener when a program contains more than one
    predictor (`ChainOfThought`, `ReAct`).
  - Auto-resolution of listener-to-predictor mapping via signature traversal
    (equivalent of Python's `find_predictor_for_stream_listeners`).
  - `allow_reuse` semantics for streaming the same predictor multiple times in
    one program call.

### Python parity tests to port

- `test_stream_listener_chat_adapter`
- `test_stream_listener_json_adapter`
- `test_stream_listener_returns_correct_chunk_chat_adapter` (token-by-token assertion matrix)
- `test_stream_listener_returns_correct_chunk_json_adapter`
- `test_stream_listener_returns_correct_chunk_chat_adapter_untokenized_stream` (multi-token chunks, e.g. Gemini)
- `test_stream_listener_returns_correct_chunk_json_adapter_untokenized_stream`
- `test_stream_listener_returns_correct_chunk_xml_adapter`
- `test_stream_listener_missing_completion_marker_chat_adapter`
- `test_stream_listener_allow_reuse`
- `test_streaming_handles_space_correctly`

## Postponed — Real LM provider streaming client

dspy4s has no real LM provider implementation at all. Streaming requires one that produces chunked responses. Candidates:

- OpenAI HTTP SSE client (chat completions `stream=True`)
- Anthropic Messages streaming
- Ollama streaming
- LiteLLM bridge (if a JVM LiteLLM wrapper emerges)

The `StreamingLanguageModel` trait is designed to be provider-agnostic; each provider adapter will implement `stream()` and yield `LmChunk` instances.

### Acceptance criteria

- Real OpenAI chat completion stream produces a sequence of `TokenEvent`s followed by `PredictionEvent`
- Cache hit on streaming request yields `PredictionEvent` without token events (matches Python semantics)
- `LmUsage` is still reported in the final prediction

## Postponed — Effect-system streaming (fs2 / ZIO)

The v1 design uses direct-style Scala + a producer thread + `LinkedBlockingQueue`. This is sufficient for inference but has limits:

- No structured concurrency — cancelling a stream doesn't stop the LM request
- Back-pressure is only bounded by the queue capacity
- No `Stream[IO, *]` composition with other effectful pipelines

A v2 could expose:

- `def streamifyFs2(program): Map[String, Any] => fs2.Stream[IO, StreamEvent]`
- `def streamifyZio(program): Map[String, Any] => ZStream[Any, Throwable, StreamEvent]`

Requires adding `cats-effect` and/or ZIO as dependencies. Decision deferred until a real LM provider lands.

## Postponed — `streaming_response` OpenAI-compatible SSE output

Python DSPy exposes `streaming_response(program_stream)` that converts a DSPy stream into Server-Sent Events for HTTP APIs:

```python
data: {"prediction": {...}}\n\n
data: [DONE]\n\n
```

dspy4s has no HTTP server layer; this is a user application concern. Deferred until we decide to ship an HTTP integration helper (likely a small module wrapping a web framework like `tapir` or `http4s`).

## Postponed — Async program streaming (`is_async_program=True`)

Python `streamify` can wrap async programs and route them through `acall`. dspy4s has `arun(...: ExecutionContext)` as the async path, but v1 streaming only wires the sync `run(...)`. Wiring async programs requires:

- Deciding whether the producer is a `Future` chain instead of a thread
- Propagating the `ExecutionContext` correctly through `ContextPropagation.wrapExecutionContext`

Should land alongside the effect-system streaming work above.

## Postponed — Concurrent/concurrent-safe status message providers

Python test `test_concurrent_status_message_providers` verifies that two concurrent `streamify` invocations with different providers don't interfere. The dspy4s `RuntimeEnvironment` ThreadLocal model already gives per-thread isolation, but:

- If both run on the same thread (via async task interleaving), the current `withCallbacks` mechanism handles it by nesting
- A dedicated concurrency parity test should be added to `StreamifySuite`

## Postponed — Blocking tool call status messages

Python `test_status_message_non_blocking` verifies that status messages continue to be emitted even when a tool sleep-blocks. This works because the Python status callback dispatches via `sync_send_to_stream` which uses a thread pool when inside an event loop. The dspy4s producer thread will similarly emit while the tool runs — but no explicit test for this yet.

## Postponed — `apply_sync_streaming` / sync bridge

Python's `apply_sync_streaming` converts an async generator to a sync generator using a `Queue` + producer thread. dspy4s v1 is natively sync (producer thread + blocking iterator), so the async→sync bridge is unnecessary. The `ClosableIterator[A]` is the canonical surface.

If async program streaming lands later, a `Streamify.toSyncIterator(asyncIterator)` helper may be added for parity.

## Maintenance rules when implementing postponed items

1. Port the full Python parity test matrix for the feature first
2. Add a new section here documenting any intentional behavioral deltas from Python
3. Update `PORT_SCOPE.md` Tier 1 parity notes when a postponed item ships
4. Update `PHASE8_PROGRESS.md` (to be created when streaming reaches completion milestone)
