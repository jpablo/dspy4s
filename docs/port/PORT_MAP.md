# dspy4s ⇄ Python DSPy — Port Map

**Upstream:** stanfordnlp/dspy 3.1.3 (pinned — see [PORT_BACKLOG.md](PORT_BACKLOG.md#upstream-parity-target)).

This is the single source of truth for **how dspy4s names and behaviors map to Python DSPy** and where they diverge. Anything that doesn't appear here should be a 1:1 port.

Related docs:
- [`PORT_SIMILARITIES.md`](PORT_SIMILARITIES.md) — narrative-level "what stayed the same" for someone coming from Python DSPy.
- [`PORT_DIFFERENCES.md`](PORT_DIFFERENCES.md) — narrative-level "what changed shape and why" companion.
- [`PORT_LANGUAGE_NOTES.md`](PORT_LANGUAGE_NOTES.md) — Python→Scala idiom mechanics with code samples.

This doc is *what* changed at the symbol level; PORT_DIFFERENCES is *why* the architecture took a different shape; PORT_LANGUAGE_NOTES is *how* the Python construct gets expressed in Scala.

> **Maintenance rule:** the commit that introduces a rename, consolidation, or deliberate behavioral delta updates this doc in the same commit. Streaming-specific deltas may continue to live in [STREAMING_POSTPONED.md](../STREAMING_POSTPONED.md) (since they're tied to the streaming feature roadmap) but must also have a one-line pointer in §4 below.

---

## 1. Module renames

dspy4s consolidates and renames Python's top-level packages. The rationale column captures the reason — keep it terse.

| Python `dspy/` | dspy4s `modules/` | Rationale |
|---|---|---|
| `clients` | `lm` | Self-describing: it's the LM module. "clients" is a generic Python idiom and conflicts with the conceptual sense of an HTTP/user client. |
| `predict` | `programs` | The module holds composites (`ChainOfThought`, `ReAct`, `BestOfN`, `Refine`, `Parallel`, `MultiChainComparison`) and the `Aggregation.majority` utility, not just predict primitives. Also avoids the name collision with the `Predict` class itself. |
| `teleprompt` | `optimize` | `teleprompt` is jargon coined by DSPy; `optimize` is what the code actually does. |
| `evaluate` | `evaluate` | 1:1. (Was briefly `eval` during scaffolding; renamed back in commit [`f5d9e12`](#) to align with Python.) |
| `primitives` + `signatures` | `core` | Merged: the Python split is largely historical (signatures grew out of primitives). dspy4s collapses them into one home for shared ADTs. |
| `adapters` | `adapters` | 1:1. |
| `streaming` | `streaming` | 1:1. |
| `utils` | folded into the modules that use them | See [§3 Consolidations](#3-consolidations). dspy4s deliberately avoids a catch-all utility namespace. |
| `retrievers` | `lm` (`Embedder`) + `programs.retrievers` | ✅ ported (G-10): `Embedder` contract + `OpenAiEmbedder` in `lm`; `KNN` + `EmbeddingsRetriever` in `programs.retrievers`; `KNNFewShot` in `optimize`. Deliberately skipped: the legacy `dspy.Retrieve`/global-RM path and the vendor RMs (`weaviate_rm`, `databricks_rm`). |
| `datasets` | — | Not ported (PORT_GAPS G-21). |
| `propose` | folded into `optimize` | Partially ported: `GroundedProposer` (LM instruction proposer) lives in `modules/optimize/.../propose/`, backing COPRO/MIPROv2. Remaining pieces (program-source `DescribeProgram`, iterative dataset-summary refinement) deferred (PORT_GAPS G-18). |
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

## 2a. `programs/` per-file port status (vs Python `predict/`)

Python's `dspy/predict/` has 16 files. Current dspy4s coverage:

| Python file | dspy4s | Notes |
|---|---|---|
| `predict.py` | `Predict.scala` | ✅ ported |
| `chain_of_thought.py` | `ChainOfThought.scala` | ✅ ported |
| `react.py` | `ReAct.scala` | ✅ ported, incl. trajectory truncation (`_call_with_potential_trajectory_truncation`) on BOTH the react and extract steps: on `ContextWindowExceededError`, drop the oldest step and retry (3 attempts). React-step truncation is durable (later iterations build on the truncated trajectory, as upstream's in-place pops); a persistent react-step overflow breaks the loop and the extractor still runs (upstream's caught `ValueError`); a persistent extract-step overflow fails the call. Delta: the returned prediction's `trajectory` stays complete (upstream's pops shrink it). |
| `best_of_n.py` | `BestOfN.scala` | ✅ ported |
| `refine.py` | `Refine.scala` | ✅ ported. Full `OfferFeedback` advice/feedback loop (v1, commit `ddecaf2`) **with per-module advice** (G-5 v2, commit `24e89b2`): `OfferFeedback` emits a JSON advice dict keyed by named predictor (`Predictors.readNamed`), and `HintInjectingAdapter` routes each predictor's OWN advice to its `hint_` (matched by `SignatureLayout` — the stand-in for Python's `signature2name` object-identity routing). See [PORT_GAPS.md](PORT_GAPS.md#g-5--refine-is-a-thin-best-of-n-alias-no-offerfeedback-loop) (G-5 Resolved). |
| `parallel.py` | `Parallel.scala` | ✅ ported |
| `aggregation.py` | `Aggregation.scala` | ✅ ported. `Aggregation.majority` mirrors Python; default normalizer is a minimal trim-and-blank-check (Python's default uses the heavier `normalize_text` from `dspy.evaluate`). Pass a custom normalizer for full parity. |
| `multi_chain_comparison.py` | `MultiChainComparison.scala` | ✅ ported, typed `MultiChainComparison[I, O]`. Python's `__call__(attempts, **inputs)` dual input is a bespoke `MultiChainCall[I]` (base input + candidate completions); `compare(input, attempts)` is the convenience entry. Output is `WithField[O, "rationale", String]`. |
| `parameter.py` | — | **Not ported.** Python's `Parameter` / `named_parameters` introspection enables optimizers to walk a program's mutable state generically. dspy4s optimizers use the `Predictors[P]` / `Predictor[P]` typeclass pair instead (Scala 3 Mirror derivation enumerates and immutably replaces the contained predictors; reads `demos` / `layout` / `config` / instructions directly), so the generic reflective walk has no consumer. An earlier `Parameter` + `BaseModule` + `ModuleGraphWalker` port shipped in Phase 1 and was removed; the interim `PredictOps` typeclass was itself superseded and removed in G-1 P6 (commit `1657f9c`), leaving `Predictors` as the sole introspection typeclass. |
| `retry.py` | — | **Skipped.** The Python file is entirely commented-out dead code (no `Retry` class is exported from `dspy.predict`). Not a port gap. |
| `knn.py` | `programs/retrievers/KNN.scala` | ✅ ported (G-10, commit `086e937`) — input-fields-only serialization, eager trainset embedding, raw-dot-product top-k. |
| `code_act.py` | `CodeAct.scala` | ✅ ported, full parity pass: `tools` field (rendered into the instructions as upstream's numbered `Tool.__str__` list AND bridged into the sandbox via `sandboxTools` — same vector both sides); upstream `_parse_code` checks (cut at `---`/triple-newline, single-line-multiple-`=` rejection, trailing-assignment echo); parse failures `continue` and ignore `finished` (upstream semantics); extractor retries with oldest-iteration truncation on `ContextWindowExceededError` (3 attempts). Deltas (documented): fence regex also accepts ```py/untagged (upstream leaves backticks in — a wart); per-call `max_iters` is `.copy(maxIterations = n)` (no magic config key); caller-owned interpreter lifecycle (upstream shuts it down every `forward`); tools bridge via RPC, not source-injection (so Scala-implemented tools work). |
| `program_of_thought.py` | `ProgramOfThought.scala` | ✅ ported. Three `ChainOfThought` passes: `generate` → `regenerate` (on execution error, up to `maxIterations`) → `answer`. Caller-owned interpreter lifecycle. The print-your-JSON convention works on every interpreter; on the SUBMIT-capable `DenoPyodideInterpreter` a structured `SUBMIT(...)` early-exit is preferred over stdout (G-20 part 1 — full Python parity). |
| `rlm.py` | `RLM.scala` | ✅ ported (G-20) — typed `RLM[I, O]` over the `ReplCodeInterpreter` surface (default: a fresh `DenoPyodideInterpreter` per forward, closed afterwards). Verbatim action template, REPL variable metadata/history rendering, `llm_query`(+batched) with a shared `maxLlmCalls` counter, SUBMIT validation with continue-on-error, extract fallback. Deltas: sequential `llm_query_batched`; Schema-decode output typing; no `SandboxSerializable`/async (`verbose` ported: per-step stderr logging). Upstream marks it `@experimental`. |
| `react_v2.py` | — | Deferred (PORT_GAPS G-19) — upstream's `@experimental` native-tool-calling ReAct; wait for it to stabilize, then port over the G-7b adapter seams. The existing `ReAct` deliberately stays text-protocol. |
| `avatar/` | — | **Won't fix (by design)** (PORT_GAPS G-14, together with `AvatarOptimizer`) — `Avatar` is not in upstream's public API (deep import only), has no docs page, and sees only mechanical maintenance; the tool-agent space is covered by `ReAct`/`CodeAct`. Reopen if upstream re-exports/documents it. |

---

## 2b. Other ported symbols (notes)

### Optimizers (`optimize`)

- **`LabeledFewShot` / `BootstrapFewShot` / `BootstrapFewShotWithRandomSearch`.** ✅ ported (Phase 7 v1).
- **`Ensemble`.** ✅ ported — `modules/optimize/.../Ensemble.scala` (majority-vote default; per-input voting).
- **`COPRO`.** ✅ ported (v1, commit `f2c24f7`) — instruction optimizer (coordinate-ascent prompt optimization).
- **`MIPROv2`.** ✅ ported (v1, commit `9f51db8`) — instruction+demo joint optimizer composing
  `BootstrapFewShot` + `GroundedProposer` + random search. Delta vs upstream (notably random-search-vs-Optuna)
  documented in `MIPROv2.scala`.
- **`GroundedProposer` (Python `propose/`).** ✅ ported (v1, commit `c27760c`) — LM instruction proposer grounded
  in a dataset summary + demos; backs COPRO/MIPROv2.
- **`GEPA`.** ✅ ported (G-12) — full Genetic-Pareto reflective prompt evolution in `modules/gepa`
  (`dspy4s-gepa`): reflective mutation + instance-Pareto selection, round-robin/all component selection,
  epoch-shuffled minibatch, merge crossover, eval cache, run-dir resume, opt-in perfect-score early stop;
  live-model validated. Multi-objective frontiers deliberately not ported (dspy's metric is scalar).
- **`InferRules`.** ✅ ported (G-11, commit `81ffec1`) — induces natural-language rules from the trainset and
  appends them to each predictor's instructions; composes `BootstrapFewShot`.
- **`KNNFewShot`.** ✅ ported (G-10, commit `20a829f`) — per-call dynamic few-shot via `KNN` retrieval +
  `BootstrapFewShot`. Delta: returns a `KNNFewShotProgram` wrapper module (Python monkey-patches `forward`;
  dspy4s programs are immutable), so it is not a `Teleprompter`.
- All of the above build on the **G-1** enablers (`Predictors`/`Predictor` introspection — relocated to
  `programs` for G-5 v2 — `Runnable` typed spine, instruction editing) + `Evaluate`. **Still deferred:**
  `SIMBA` (G-13), `BetterTogether` (G-15), `GRPO`/`BootstrapFinetune` (G-16),
  `BootstrapFewShotWithOptuna` (G-17), and the remaining `propose` pieces (G-18).
  `AvatarOptimizer` is **Won't fix (by design)** — see G-14.

### Metrics (`evaluate`)

- **`SemanticF1` / `CompleteAndGrounded`.** ✅ ported (commit `95adeb5`) — LLM-judged auto-evaluation metrics in
  `modules/evaluate/.../metrics/AutoEvaluation.scala`, unblocked by the `Metric.score` → `RuntimeContext` change
  (G-6). Deltas: the judge runs over a runtime `SignatureLayout`/`DynamicPredict`, field names are configurable
  string keys, and groundedness reads `pred.context` by key (no retriever populates it). See PORT_GAPS G-6.

### Adapters

- **`JSONAdapter` `response_format`.** ✅ ported (v1, commit `ed2c69f`) — emits
  `response_format: {type:"json_schema", …}` when the ambient LM `supportsResponseSchema` and an
  `outputJsonSchema` is present (prose schema kept as fallback), via the new `FormattedPrompt.requestOptions`
  request-influence seam. **Native function-calling (G-7b):** ✅ ported — `tools`/`tool_choice` injected via
  the same seam (shared `NativeFunctionCalling` helper), native `tool_calls` parsed into a `tool_calls` output
  field; gated on `supportsFunctionCalling`. ReAct stays on the text protocol (upstream parity). See PORT_GAPS G-7.
- **Field constraints (`PYDANTIC_CONSTRAINT_MAP`).** ✅ ported (v1, commit `d8c80de`) — `FieldSpec.constraints`
  + `FieldConstraints` build the upstream constraint strings (`gt`/`ge`/`lt`/`le`/`minLength`/`maxLength`/
  `multipleOf`); `ChatAdapter` renders `Constraints: <joined>`, and they round-trip through `SignatureLayout`
  state. **v1 follow-ups:** constraints are settable programmatically only (deriving from the typed `Schema`
  needs an annotation mechanism that doesn't exist yet); `XMLAdapter`/`JSONAdapter` don't yet embed them.
  See PORT_GAPS G-9.

### Persistence & per-module bindings

- **Program `save`/`load` + `dumpState`/`loadState`.** ✅ ported (commit `9c5a6db`) via `ProgramPersistence`
  (`modules/optimize/.../ProgramPersistence.scala`), built on `Predictors`. JSON state of
  `{ "predictors": [<DynamicPredict state>..] }`; demos/config/instructions round-trip (typed field *structure*
  intentionally not written back). See PORT_GAPS G-4.
- **Per-module `config` + bound LM.** ✅ ported — `DynamicPredict` / `Predict[I, O]` carry an immutable
  module-level `config` (commit `b85fe27`, merged under per-call config) and an optional bound LM
  (`Predict.withLm` / `Predict.boundLm`, commit `b2d0096`; a bound LM wins over the ambient `RuntimeContext` LM).
  See PORT_GAPS G-3.

### LM & runtime

- **`ContextWindowExceededError`.** Added to the `DspyError` hierarchy
  (`modules/core/.../contracts/Errors.scala`) and now **produced by**
  `OpenAiClient.statusError` (HTTP 400 + context-window body marker).
- **LM capability flags.** `supportsFunctionCalling` / `supportsResponseSchema` / `supportsReasoning` on
  `LanguageModel` (default false; OpenAI overrides true).
- **`inspect_history`.** `RuntimeEnvironment.inspectHistory(n)` + `HistoryRenderer`.

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
| `dspy/primitives/python_interpreter.py` + `dspy/primitives/code_interpreter.py` | `core/contracts/CodeInterpreter.scala` (trait) + `core/runtime/SubprocessPythonInterpreter.scala` (default impl) | dspy4s splits the Python `CodeInterpreter` protocol from its Deno+Pyodide-backed default impl. The default in dspy4s today is a plain `python3 -c "..."` subprocess (NOT sandboxed) — useful for trusted code and prototyping. The sandboxed Deno+Pyodide impl that matches Python DSPy 1:1 (`runner.js` + JSON-RPC + tool-callback bridge) is deferred to a focused future session. User code errors (`NameError`, `SyntaxError`) surface as `CodeResult` with non-zero `exitCode` and traceback in `stderr`; the `Either` Left path is reserved for interpreter-itself failures (process crash, timeout). |

---

## 4. Behavioral deltas

Deliberate behavioral differences. Bugs are not deltas — bugs get fixed, not documented here.

| Area | Python behavior | dspy4s behavior | Why | Reference |
|---|---|---|---|---|
| **ChatAdapter stream completion-chunk emission** | Python's `StreamListener` emits a synthetic final chunk when it sees `[[ ## completed ## ]]`. Tests therefore index with `[-2]` to find the last *content* chunk. | dspy4s closes the active field on the marker without emitting a sentinel chunk. The last content chunk IS `tokens.last`. | Avoids a synthetic chunk that consumers immediately have to skip; the `isLast = true` flag on the prior chunk already conveys "this field is done." | `ChatStreamingState.scala`, `StreamingLiveSuite` test comments |
| **Per-token chunk granularity (all adapters)** | Python's `StreamListener` emits one chunk per LM-delivered token using a queue + holdback discipline; `_could_form_end_identifier` decides per-token release safety. | dspy4s emits fewer, larger chunks: ChatAdapter holds back `markerMaxLen-1` chars and flushes the safe prefix on each receive; JSON emits one chunk per field at the value boundary; XML emits one chunk per field at the close-tag boundary. Concatenated content matches Python, but per-chunk shape does not. `couldFormEndIdentifier` helpers exist on each `*StreamingState` companion as pure functions ported for unit-test parity, but the state machines don't yet use them in the runtime. | The chunk-shape parity tests (Python's `test_stream_listener_returns_correct_chunk_*`) are deferred — closing them needs a state-machine refactor around the queue-and-holdback discipline. The current granularity is good enough for downstream consumers that concatenate or buffer chunks anyway. | `ChatStreamingState.scala`, `JsonStreamingState.scala`, `XmlStreamingState.scala`, [STREAMING_POSTPONED.md per-token refactor](../STREAMING_POSTPONED.md) |
| **Streamify default async-ness** | `streamify` returns an `AsyncIterator` by default; `async_streaming=False` produces a sync iterator. | `Streamify.streamify` is sync-only (producer thread + `ClosableIterator`). No async variant yet. | Tracking under STREAMING_POSTPONED ("Async program streaming"). | `Streamify.scala`, [STREAMING_POSTPONED.md](../STREAMING_POSTPONED.md) |
| **StreamListener `allow_reuse` default** | `allow_reuse = False` (listener fires only on the first LM call). | `allowReuse = True` (default). Set `allowReuse = false` to opt into Python's first-call-only semantics. | Default-on matches what most consumers want; opt-out preserves parity. | `StreamListener` case class, `StreamingLanguageModelWrapper.firedListeners` |
| **`predict_name` resolution** | Python introspects parent module field names (`self.predict1` → `"predict1"`) via reflection. | dspy4s requires explicit `Predict(signature, name = Some("predict1"))` to give a Predict a non-default name; defaults to `"predict"`. | Scala has no equivalent of Python's runtime attribute introspection — explicit naming is the closest faithful port. | `Predict.scala`, [Commit `009ee0f`](#) |
| **Tool-call args fallback** | When `arguments` string is not a JSON object, Python keeps the raw string under `arguments`. | Wraps as `Map("input" -> raw)` (also matches `ProviderResponseParser.parseArgs` for non-streaming). | Uniformity with the non-streaming path. | `ToolCallAssembler.scala`, `../progress/PHASE3_PROGRESS.md` |

---

## 5. Maintaining this doc

When you commit a change that does any of the following, **edit this file in the same commit**:

- Rename a module, class, or public symbol relative to Python DSPy.
- Consolidate or split a Python module into a different dspy4s layout.
- Introduce a deliberate behavioral delta (something a Python parity test would have to be adjusted to accommodate).
- Resolve a previously-documented delta (delete its row or move it under a "Resolved" subsection).

If you're not sure whether something belongs here, it probably does — easier to remove a row later than to track down a forgotten delta during an upstream version bump.
