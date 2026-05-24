# dspy4s ⇄ Python DSPy — Port Map

**Upstream:** stanfordnlp/dspy 3.1.3 (pinned — see [PORT_BACKLOG.md](PORT_BACKLOG.md#upstream-parity-target)).

This is the single source of truth for **how dspy4s names and behaviors map to Python DSPy** and where they diverge. Anything that doesn't appear here should be a 1:1 port.

For the lower-level **language-construct mapping** (async, error handling, dynamic kwargs, decorators, mocking, etc.) see [`PORT_LANGUAGE_NOTES.md`](PORT_LANGUAGE_NOTES.md). This doc is *what* changed; that doc is *how* it's done in Scala.

> **Maintenance rule:** the commit that introduces a rename, consolidation, or deliberate behavioral delta updates this doc in the same commit. Streaming-specific deltas may continue to live in [STREAMING_POSTPONED.md](STREAMING_POSTPONED.md) (since they're tied to the streaming feature roadmap) but must also have a one-line pointer in §4 below.

---

## 1. Module renames

dspy4s consolidates and renames Python's top-level packages. The rationale column captures the reason — keep it terse.

| Python `dspy/` | dspy4s `modules/` | Rationale |
|---|---|---|
| `clients` | `lm` | Self-describing: it's the LM module. "clients" is a generic Python idiom and conflicts with the conceptual sense of an HTTP/user client. |
| `predict` | `programs` | The module holds composites (`ChainOfThought`, `ReAct`, `BestOfN`, `Refine`, `Parallel`), not just predict primitives. Also avoids the name collision with the `Predict` class itself. |
| `teleprompt` | `optimize` | `teleprompt` is jargon coined by DSPy; `optimize` is what the code actually does. |
| `evaluate` | `evaluate` | 1:1. (Was briefly `eval` during scaffolding; renamed back in commit [`f5d9e12`](#) to align with Python.) |
| `primitives` + `signatures` | `core` | Merged: the Python split is largely historical (signatures grew out of primitives). dspy4s collapses them into one home for shared ADTs. |
| `adapters` | `adapters` | 1:1. |
| `streaming` | `streaming` | 1:1. |
| `utils` | folded into the modules that use them | See [§3 Consolidations](#3-consolidations). dspy4s deliberately avoids a catch-all utility namespace. |
| `retrievers` | — | Not ported yet (Tier 1+). |
| `datasets` | — | Not ported yet (Tier 1+). |
| `propose` | — | Not ported yet — gates on `optimize` advanced optimizers (MIPRO/COPRO). |
| `dsp` | — | Intentional skip. Legacy + ColBERTv2; upstream is deprecating. |
| `experimental` | — | Empty in upstream; nothing to port. |

---

## 2. Class / symbol renames

Only non-obvious renames are listed. A class with the same name in both projects (e.g. `Predict`, `Example`, `ChainOfThought`, `Evaluate`) is **not** listed.

| Python symbol | dspy4s symbol | Notes |
|---|---|---|
| `dspy.LM` | `dspy4s.lm.contracts.LanguageModel` | Avoids two-letter abbreviation for the public trait; concrete providers (e.g. `OpenAiLanguageModel`) keep the `LanguageModel` suffix for symmetry. |
| `dspy.streamify` (free function) | `dspy4s.streaming.Streamify.streamify` (object method) | Scala convention — companion object holds the entry point. |
| `dspy.streaming.StreamResponse` | `dspy4s.streaming.contracts.TokenEvent` | dspy4s's `StreamEvent` ADT splits Python's `StreamResponse` into typed `TokenEvent` / `StatusEvent` / `PredictionEvent` / `ErrorEvent` sealed cases. |
| `dspy.streaming.StatusMessage` | `dspy4s.streaming.contracts.StatusEvent` | Same ADT split as above. |
| `EvalApi` (early scaffolding) | `EvaluateApi` | Module rename follow-through ([`f5d9e12`](#)). |

---

## 3. Consolidations

dspy4s moves several Python `utils/*` modules next to the code that depends on them. The "no catch-all utility namespace" convention.

| Python location | dspy4s location | What |
|---|---|---|
| `dspy/utils/callback.py` | `modules/core/src/main/scala/dspy4s/core/contracts/Callbacks.scala` | `CallbackHandler` is a contract every module depends on, so it lives in `core`. |
| `dspy/utils/caching.py` | `modules/lm/src/main/scala/dspy4s/lm/runtime/CacheRuntime.scala` | LM-specific (request hashing, in-memory + disk caches). |
| `dspy/utils/parallelizer.py` | `modules/programs/src/main/scala/dspy4s/programs/runtime/ParallelExecutor.scala` | Lives with the programs that use it (`Parallel`, `BestOfN`, `Evaluate`). |
| `dspy/utils/usage_tracker.py` | Inside `modules/lm/src/main/scala/dspy4s/lm/runtime/ManagedLanguageModel.scala` | Threaded as a per-call accumulator on the managed LM wrapper. |
| `dspy/primitives/*` + `dspy/signatures/*` | `modules/core/src/main/scala/dspy4s/core/{contracts,signatures}/*` | Merged into one module; the `contracts` / `signatures` split inside `core` mirrors Python's two-package split for searchability without keeping it as a build-level boundary. |

---

## 4. Behavioral deltas

Deliberate behavioral differences. Bugs are not deltas — bugs get fixed, not documented here.

| Area | Python behavior | dspy4s behavior | Why | Reference |
|---|---|---|---|---|
| **ChatAdapter stream completion-chunk emission** | Python's `StreamListener` emits a synthetic final chunk when it sees `[[ ## completed ## ]]`. Tests therefore index with `[-2]` to find the last *content* chunk. | dspy4s closes the active field on the marker without emitting a sentinel chunk. The last content chunk IS `tokens.last`. | Avoids a synthetic chunk that consumers immediately have to skip; the `isLast = true` flag on the prior chunk already conveys "this field is done." | `ChatStreamingState.scala`, `StreamingLiveSuite` test comments |
| **JSON streaming chunk granularity** | `JsonStreamingState` emits per-token slices; first chunk has `is_last_chunk = false`. | One chunk per field, emitted at the value-boundary (or at `finish()` for truncated input). First chunk has `isLastChunk = true`. | Value-boundary emission was the cleanest implementation given Scala ujson; per-token slicing can be retrofitted later if a real consumer demands it. | `JsonStreamingState.scala`, [STREAMING_POSTPONED.md v1.3 notes](STREAMING_POSTPONED.md) |
| **Streamify default async-ness** | `streamify` returns an `AsyncIterator` by default; `async_streaming=False` produces a sync iterator. | `Streamify.streamify` is sync-only (producer thread + `ClosableIterator`). No async variant yet. | Tracking under STREAMING_POSTPONED ("Async program streaming"). | `Streamify.scala`, [STREAMING_POSTPONED.md](STREAMING_POSTPONED.md) |
| **StreamListener `allow_reuse` default** | `allow_reuse = False` (listener fires only on the first LM call). | Always fires on every matching LM call; no opt-out flag yet. | Tracked as the only remaining piece of `StreamListener` parity; deferred until a concrete use case asks for it. | [STREAMING_POSTPONED.md "Postponed — opt-in allowReuse=false parity"](STREAMING_POSTPONED.md) |
| **`predict_name` resolution** | Python introspects parent module field names (`self.predict1` → `"predict1"`) via reflection. | dspy4s requires explicit `Predict(signature, name = Some("predict1"))` to give a Predict a non-default name; defaults to `"predict"`. | Scala has no equivalent of Python's runtime attribute introspection — explicit naming is the closest faithful port. | `Predict.scala`, [Commit `009ee0f`](#) |
| **Tool-call args fallback** | When `arguments` string is not a JSON object, Python keeps the raw string under `arguments`. | Wraps as `Map("input" -> raw)` (also matches `ProviderResponseParser.parseArgs` for non-streaming). | Uniformity with the non-streaming path. | `ToolCallAssembler.scala`, `PHASE3_PROGRESS.md` |

---

## 5. Maintaining this doc

When you commit a change that does any of the following, **edit this file in the same commit**:

- Rename a module, class, or public symbol relative to Python DSPy.
- Consolidate or split a Python module into a different dspy4s layout.
- Introduce a deliberate behavioral delta (something a Python parity test would have to be adjusted to accommodate).
- Resolve a previously-documented delta (delete its row or move it under a "Resolved" subsection).

If you're not sure whether something belongs here, it probably does — easier to remove a row later than to track down a forgotten delta during an upstream version bump.
