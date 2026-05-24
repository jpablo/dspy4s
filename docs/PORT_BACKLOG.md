# dspy4s Comprehensive Port Backlog

## Upstream parity target

- Upstream: [stanfordnlp/dspy](https://github.com/stanfordnlp/dspy)
- Pinned version: **3.1.3** (released 2026-02-05)
- Reference clone (local): `/Users/jpablo/GitHub/dspy`, branch `release-3.1.3`
  at tag `3.1.3` (HEAD is one commit ahead, `ccae927` "Update versions" — a
  routine post-release version bump in `pyproject.toml`/`uv.lock`, no source
  changes).

When upstream tags a new release, bump this section, refresh the clone
(`git fetch && git checkout release-X.Y.Z`), and audit the changelog for
features that affect contracts or behavior dspy4s already ports. Treat any
behavioral delta we *intend* to keep as a documented "delta from Python
parity" in the relevant `PHASE*_PROGRESS.md` / `STREAMING_POSTPONED.md` /
test comment rather than letting it drift silently.

## Phase 0: Contracts and Scaffolding
Goal: freeze boundaries before implementation.

Deliverables:
- Core ADTs and interfaces for Signature, Module, Adapter, LM, Settings.
- Error model and result model conventions.

Acceptance:
- Compile-only module contracts in all subprojects.

## Phase 1: Signatures + Primitives (`core`)
Goal: parity for data model and signature manipulation.

Implement:
- Signature DSL parser (`inputs -> outputs` with type annotations subset).
- Field metadata and defaults.
- Signature mutation operations.
- `Example`, `Prediction`, `Completions`.
- Module tree traversal (`named_parameters`, `named_sub_modules`) equivalent behavior.

Acceptance tests (ported):
- `tests/signatures/test_signature.py`
- `tests/primitives/test_example.py`
- `tests/primitives/test_module.py` (selected Tier 0 cases)

## Phase 2: Settings + Callbacks + Parallel Context (`core` + `programs`)
Goal: preserve execution context semantics.

Implement:
- Global settings + scoped overrides.
- Callback dispatch model (module/lm/adapter/tool/evaluate).
- Context propagation into parallel/async execution.

Acceptance tests:
- `tests/utils/test_settings.py`
- `tests/callback/test_callback.py`
- `tests/utils/test_parallelizer.py`

## Phase 3: LM + Cache (`lm`)
Goal: stable LM execution semantics.

**Status**: cache/retry/history parity shipped in Phase 3 baseline; OpenAI HTTP provider shipped as Phase 3 extension (see `PHASE3_PROGRESS.md`).

Implement:
- Base LM interface and request normalization.
- Retry policy and cache keying.
- History + usage tracking.
- OpenAI-compatible chat and responses mode.
- Real OpenAI HTTP provider with `StreamingLanguageModel` (SSE chat completions).

Acceptance tests:
- `tests/clients/test_cache.py`
- `tests/clients/test_lm.py` (Tier 0 subset)
- `tests/utils/test_usage_tracker.py`

Remaining OpenAI provider gaps (deferred):
- Anthropic / Ollama / LiteLLM providers.
- Live-API response-mode parity tests.

## Phase 4: Adapters (`adapters`)
Goal: robust formatting/parsing.

Implement:
- Adapter base pipeline.
- Chat/JSON/XML/TwoStep adapters.
- Parse error diagnostics and fallback behavior.
- Tool schema + `ToolCalls`.

Acceptance tests:
- `tests/adapters/test_chat_adapter.py`
- `tests/adapters/test_json_adapter.py`
- `tests/adapters/test_xml_adapter.py`
- `tests/adapters/test_tool.py`

## Phase 5: Programs (`programs`)
Goal: end-to-end runtime usability.

Implement:
- `Predict`, `ChainOfThought`, `ReAct`, `Parallel`, `BestOfN`, `Refine`.
- Trace capture and propagation.

Acceptance tests:
- `tests/predict/test_predict.py`
- `tests/predict/test_chain_of_thought.py`
- `tests/predict/test_react.py`
- `tests/predict/test_parallel.py`
- `tests/predict/test_best_of_n.py`
- `tests/predict/test_refine.py`

## Phase 6: Evaluation (`eval`)
Goal: metric + threaded evaluation parity.

**Status**: v1 shipped (see `PHASE6_PROGRESS.md`).

v1 covers:
- Text normalization utility (`NormalizeText`, parity with Python)
- Built-in metrics: `ExactMatch`, `ContainsMatch`, `F1Score`, `AnswerMatch`, `PassageMatch`, `FunctionMetric`
- `Evaluate(devset, metric, numThreads, maxErrors, failureScore, ...)(program)` runner
- Uses `ParallelExecutor` from programs (thread isolation, max-errors cancellation, timeout)
- Percentage aggregate score (0–100) and `metricName` carried on `EvaluationResult`
- `saveAsJson` / `saveAsCsv` with flat-row schema (example fields prefixed `example_`, collisions prefixed `pred_`)
- 30 tests ported across NormalizeText, BuiltinMetrics, Evaluate, Persistence suites

Deferred to Phase 6 v2:
- `display_table` / pandas-style rendering
- Straggler retry mechanism
- LLM-judged auto-evaluation metrics (`SemanticF1`, `CompleteAndGrounded`)
- `provideTraceback` and `callback_metadata` options

## Phase 7: First Optimizers (`optimize`)
Goal: practical compile loops.

**Status**: v1 shipped (see `PHASE7_PROGRESS.md`).

v1 covers:
- `PredictOps[P]` typeclass (rewrites demos/signature on `Predict` and `ChainOfThought`)
- `LabeledFewShot(k, sample, seed)` baseline
- `BootstrapFewShot(metric, maxBootstrappedDemos, maxLabeledDemos, maxRounds, maxErrors, seed)` teacher-driven trace collection
- `BootstrapFewShotWithRandomSearch(...)` random candidate generation via seeds ±3, ±2, ±1, and ≥ 0; evaluation via `dspy4s.eval.Evaluate`; `stopAtScore` early exit
- 13 tests (5 LabeledFewShot + 5 Bootstrap + 3 RandomSearch)
- `ExampleData` extended with `augmented: Boolean` flag for bootstrap parity

Deferred to Phase 7 v2:
- `Ensemble` (per-input voting/majority)
- `KNNFewShot` (k-NN retriever over `Embedder`)
- Composite multi-predictor program support in bootstrap
- LLM-judged metrics for bootstrap scoring

## Phase 8: Streaming (`streaming`)
Goal: minimal streaming parity.

**Status**: v1 shipped (see `PHASE8_PROGRESS.md` and `STREAMING_POSTPONED.md`).

v1 covers:
- `LmChunk` + `StreamingLanguageModel` trait
- `StreamingQueue` with `ClosableIterator` consumer surface
- `StatusMessageProvider` + `StatusStreamingCallback`
- `StreamingLanguageModelWrapper` (pumps chunks during `call`)
- `Streamify.streamify(program)(inputs) => Iterator[StreamEvent]`
- Status/prediction/error events for both streaming and non-streaming LMs
- 16 tests across `StreamingQueueSuite`, `StatusStreamingCallbackSuite`, `StreamifySuite`

v2 deferred (tracked in `STREAMING_POSTPONED.md`):
- Per-field `StreamListener` with Chat/JSON/XML chunk state machines
- Real LM provider streaming clients (OpenAI SSE, Anthropic, Ollama)
- Structured concurrency (fs2 / ZIO streams)
- `streaming_response` OpenAI-compatible SSE output
- Async program streaming path

## Phase 9: Deferred/Advanced Tracks
Goal: close major feature gaps after v1.

Tracks:
- Advanced optimizers (`GEPA`, `MIPROv2`, etc.)
- Interpreter-based modules (`ProgramOfThought`, `CodeAct`, `RLM`)
- Fine-tuning providers and local training stack
- Broader multimodal/reliability suites

## Cross-Phase Engineering Rules
1. Test-first parity for each component before expanding API surface.
2. Keep Python compatibility notes per feature in docs.
3. Prefer deterministic behavior over implicit reflection.
4. Do not block v1 on Tier 2 features.
