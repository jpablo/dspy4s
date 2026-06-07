# dspy4s Streaming — Postponed Features (v1 → v2)

This document tracks streaming features deferred from the v1 implementation to later phases.

## Shipped (correction 2026-06-06)

Two features documented below as "Postponed" have in fact **shipped**. The
"Postponed — Real LM provider streaming client" and any claim that per-field
`StreamListener` is unimplemented are **superseded** by this section:

- **Real LM provider streaming client.** The earlier claim that "dspy4s has no
  real LM provider implementation at all" is false. `OpenAiLanguageModel.stream`
  + `OpenAiClient.stream` perform real SSE `data:` parsing; `JdkHttpTransport.streamSse`
  drives the transport and `OpenAiStreamChunk` models the frames.
- **Per-field `StreamListener` with Chat/JSON/XML chunk state machines.** Shipped:
  `StreamListener` is a case class in `StreamingContracts.scala`; `Json`/`Xml`/`Chat`
  `StreamingState` are wired via `Adapter.streamingState`. (See the v1.2–v1.4
  "Shipped" slices below.)

Note: the test inventory in this doc is partly stale — the "Python parity tests
to port" list under v1.7 overlaps the tests already shipped in v1.8 (e.g.
`test_streaming_handles_space_correctly`,
`test_stream_listener_missing_completion_marker_chat_adapter`, and the
`*_returns_correct_chunk_*` matrix). Treat the v1.8 / per-token-refactor sections
as authoritative for current status.

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

## Shipped in v1.5 — Compound-program routing (Slice D)

- `Streamify.signatureFor` now pattern-matches `ChainOfThought` and `ReAct`
  in addition to `Predict`. The resolver returns the **outer** program's
  `moduleName` as the listener `predictName`, so listeners filter against
  the user-visible program (`"chain_of_thought"`, `"react"`, `"predict"`)
  rather than the inner Predict's name.
- `ChainOfThought` exposes its augmented signature (`reasoning` field
  prepended to the base outputs) via `.signature.toOption`, so listeners
  on the reasoning field receive its tokens.
- `ReAct` recursively delegates to its inner `module`'s signature. Every
  iteration's LM call builds a fresh adapter state, so listeners fire on
  each iteration's matched chunks (equivalent to Python DSPy's
  `allow_reuse=True`).
- 3 new tests in `StreamListenerSuite`: ChainOfThought + listener,
  ChainOfThought + predictName filter, ReAct routing through inner
  Predict.

### Notes / behavioral deltas from Python parity

- dspy4s defaults to `allow_reuse = true` semantics (every iteration's
  matched chunks fire). Python defaults to `allow_reuse = false` (only
  the first invocation fires). An opt-in `allowReuse: Boolean = true`
  field on `StreamListener` would close the gap; deferred until a
  concrete use case appears.
- Multi-predictor programs with *different* signatures per inner predict
  aren't in dspy4s today (none of `Predict` / `ChainOfThought` / `ReAct`
  compose distinct predictors with distinct signatures). When such a
  program lands, `signatureFor` will need to switch to per-LM-call
  signature routing — see the matching note that used to live in this
  doc and is now resolved for the current program set.

## Shipped in v1.4 — XML adapter streaming state (Slice C)

- `XmlStreamingState` parses streamed XML output of the form
  `<outputs><field1>v1</field1><field2>v2</field2></outputs>` character by
  character. Skips any preamble before the first `<` (fenced ```xml blocks
  work); walks through non-output tags (including the `<outputs>` wrapper)
  without emission; tolerates open-tag attributes and whitespace before `>`.
- Decodes named entities (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`)
  and numeric character references (`&#NNN;`, `&#xHHHH;`) inline.
- Robust to receive() boundaries that split a tag name, an entity, or
  content. `finish()` flushes any in-progress field with `isLast = true`.
- `XMLAdapter.streamingState` wires it up.
- 13 new tests: `XmlStreamingStateSuite` (12) + end-to-end XML listener
  test in `StreamListenerSuite`.

## Shipped in v1.3 — JSON adapter streaming state (Slice B)

- `JsonStreamingState` parses a streamed top-level JSON object character by
  character. Skips any preamble before the first `{` so fenced ```json blocks
  work; decodes string-value escapes (`\n`, `\t`, `\"`, `\\`, `\/`, `\uXXXX`)
  inline; emits non-string scalars and nested object/array values as their
  raw JSON text (trimmed of surrounding whitespace at value end).
- Robust to mid-token receive boundaries: half-buffered keys, partial
  `\uXXXX` sequences, mid-value pauses, and unterminated strings all resume
  cleanly on the next `receive(...)`. `finish()` emits any in-progress field
  with `isLast = true`, including the truncated case.
- `JSONAdapter.streamingState` wires it up.
- Design note: this slice emits one chunk per field (at the value boundary
  or via `finish()`), not character-by-character. Intra-value streaming
  could be re-added later if a long-value use case demands it.
- 13 new tests across `JsonStreamingStateSuite` (12) and one end-to-end JSON
  listener test in `StreamListenerSuite`.

## Shipped in v1.2 — Per-field `StreamListener` (Chat adapter, Slice A)

- `AdapterStreamingState` trait + `FieldChunk` data class added to
  `adapters.contracts`. Adapters implement `Adapter.streamingState(signature)`
  to opt in.
- `ChatStreamingState` detects `[[ ## field_name ## ]]` markers in the
  streamed text and routes content between adjacent markers to per-field
  chunks. Holds back the trailing window that could still grow into a
  marker so partial markers across `receive()` boundaries are never leaked.
  `finish()` flushes the active field and marks the last chunk
  `isLast = true`. The `[[ ## completed ## ]]` sentinel closes the active
  field without emitting a sentinel chunk.
- `StreamListener` redefined from a placeholder trait to a configuration
  case class with `signatureFieldName` and optional `predictName`.
- `StreamingLanguageModelWrapper` builds a fresh state per `call()`,
  drives it with each chunk's text, and emits one `TokenEvent` per
  `FieldChunk`. When no state is supplied it preserves raw-token behavior.
- `Streamify` resolves the active `Adapter` from settings; per-LM-call
  signature routing happens inside the wrapper via
  `ActivePredictContext` (see v1.6).

### Framing parity update (was a delta in earlier drafts)

The original Slice A used `prefix: value` line framing and was documented
as a deliberate behavioral delta. That delta was closed when we rewrote
`ChatAdapter` + `ChatStreamingState` to use the full `[[ ## field ## ]]`
+ `[[ ## completed ## ]]` framing — see [`port/PORT_MAP.md`](port/PORT_MAP.md) §4
for the small residual chunk-emission delta that remains
(no sentinel chunk on `completed`).

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

## Shipped in v1.6 — `allowReuse` opt-out and listener field-name validation

- `StreamListener` gains `allowReuse: Boolean = true`. When set `false`,
  the listener emits chunks for one complete field cycle (up to and
  including `isLastChunk = true`) and then goes silent for the rest of
  the `streamify` invocation. Matches Python's `allow_reuse = False`
  default behaviour as an opt-in for users that want it.
  Implementation: `StreamingLanguageModelWrapper.firedListeners` mutable
  set tracks which listener indices have closed a field cycle.
- `Streamify.streamify` now accepts a `warningSink: String => Unit`
  (defaults to `System.err.println`) and walks the program tree
  (`Predict` / `ChainOfThought` / `ReAct`) to warn at streamify-time
  when a listener's `signatureFieldName` doesn't appear in any known
  Predict signature, or when its `predictName` doesn't match any
  Predict's name. dspy4s's equivalent of Python's
  `find_predictor_for_stream_listeners`. Opaque user composites skip
  validation silently.
- 5 new tests in `StreamListenerSuite` covering both directions of
  `allowReuse`, unknown-field warning, unknown-`predictName` warning,
  and opaque-composite skip.

## Shipped in v1.8 — Mock-driven parity test ports (10 tests)

Direct dspy4s ports of Python tests from
`tests/streaming/test_streaming.py` that don't require live LMs,
async program execution, Pydantic-equivalent typing, or per-token
chunk emission:

- `test_streaming_handles_space_correctly`
- `test_stream_listener_missing_completion_marker_chat_adapter`
- `test_stream_listener_empty_last_chunk_chat_adapter`
- `test_stream_listener_empty_last_chunk_json_adapter`
- `test_json_adapter_bracket_balance_detection`
- `test_json_adapter_multiple_fields_detection`
- `test_sync_status_streaming` (adapted: uses a tool-only program
  since dspy4s has no published `DummyLM` yet)
- `test_stream_listener_could_form_end_identifier_chat_adapter`
- `test_stream_listener_could_form_end_identifier_json_adapter`
- `test_stream_listener_could_form_end_identifier_xml_adapter`

The three `could_form_end_identifier` tests required adding pure
companion-object helpers to `ChatStreamingState`, `JsonStreamingState`,
and `XmlStreamingState`. The helpers are not yet used by the state
machines themselves — they exist as the foundation for a future
per-token state-machine refactor.

Test bug fix as part of this batch: `ChatStreamingState.stripFramingNewlines`
previously stripped at most one boundary newline. It now strips all
leading/trailing newlines from emitted content so that real LM output
patterns like `value\n\n[[ ## ... ## ]]` round-trip without trailing
`\n` artifacts.

## Postponed — Per-token chunk-emission refactor

Five Python tests assert per-token chunk shape and remain unported:

- `test_stream_listener_returns_correct_chunk_chat_adapter`
- `test_stream_listener_returns_correct_chunk_json_adapter`
- `test_stream_listener_returns_correct_chunk_xml_adapter`
- `test_stream_listener_returns_correct_chunk_chat_adapter_untokenized_stream`
- `test_stream_listener_returns_correct_chunk_json_adapter_untokenized_stream`

To port these we'd rewrite all three `*StreamingState`s around a
queue-of-received-fragments + holdback discipline that emits each LM
token as its own `FieldChunk`. The `couldFormEndIdentifier` helpers
shipped in v1.8 are the per-adapter holdback predicates this refactor
would consume.

The work is bounded but substantial (~hundreds of lines across the
three states + companions, plus rewriting the existing
`*StreamingStateSuite` granularity expectations). Deferred until a
consumer actually needs per-token UX (e.g. live chat-style typing
animations).

## Shipped in v1.7 — Concurrent-provider + blocking-tool parity tests

- Ported Python's `test_concurrent_status_message_providers`: two
  `streamify` invocations run on independent threads with distinct
  `StatusMessageProvider`s and the assertion is that neither stream
  sees the other provider's messages. Validates ThreadLocal isolation
  + per-streamify queue independence already in v1.
- Ported Python's `test_status_message_non_blocking`: a tool that
  sleeps for 200 ms must still produce tool-start and tool-end status
  events whose timestamps differ by ≥ 200 ms. Validates that status
  callbacks emit to the queue from the producer thread without
  blocking the consumer's ability to drain earlier events.
- `StatusStreamingParitySuite` — 2 new tests, neither requires a live
  LM (uses tool execution + scripted programs).

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

> **SUPERSEDED (see "Shipped (correction 2026-06-06)" at the top).** The OpenAI
> SSE client has shipped (`OpenAiLanguageModel.stream` / `OpenAiClient.stream` /
> `JdkHttpTransport.streamSse` / `OpenAiStreamChunk`). Only the Anthropic / Ollama
> / LiteLLM clients below remain postponed.

~~dspy4s has no real LM provider implementation at all.~~ Streaming requires a provider that produces chunked responses. Remaining candidates:

- ~~OpenAI HTTP SSE client (chat completions `stream=True`)~~ — **shipped**
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

## Postponed — `apply_sync_streaming` / sync bridge

Python's `apply_sync_streaming` converts an async generator to a sync generator using a `Queue` + producer thread. dspy4s v1 is natively sync (producer thread + blocking iterator), so the async→sync bridge is unnecessary. The `ClosableIterator[A]` is the canonical surface.

If async program streaming lands later, a `Streamify.toSyncIterator(asyncIterator)` helper may be added for parity.

## Maintenance rules when implementing postponed items

1. Port the full Python parity test matrix for the feature first
2. Add a new section here documenting any intentional behavioral deltas from Python
3. Update `port/PORT_SCOPE.md` Tier 1 parity notes when a postponed item ships
4. Update `progress/PHASE8_PROGRESS.md` (to be created when streaming reaches completion milestone)
