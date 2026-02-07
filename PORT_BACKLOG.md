# dspy4s Comprehensive Port Backlog

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

Implement:
- Base LM interface and request normalization.
- Retry policy and cache keying.
- History + usage tracking.
- OpenAI-compatible chat and responses mode.

Acceptance tests:
- `tests/clients/test_cache.py`
- `tests/clients/test_lm.py` (Tier 0 subset)
- `tests/utils/test_usage_tracker.py`

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

Implement:
- `Evaluate`, `EvaluationResult`, baseline metrics.
- Save to json/csv and basic display hooks.

Acceptance tests:
- `tests/evaluate/test_evaluate.py`
- `tests/evaluate/test_metrics.py`

## Phase 7: First Optimizers (`optimize`)
Goal: practical compile loops.

Implement:
- `Teleprompter`, `LabeledFewShot`, `BootstrapFewShot`, `BootstrapFewShotWithRandomSearch`, `Ensemble`, `KNNFewShot`.

Acceptance tests:
- `tests/teleprompt/test_teleprompt.py`
- `tests/teleprompt/test_bootstrap.py`
- `tests/teleprompt/test_random_search.py`
- `tests/teleprompt/test_ensemble.py`
- `tests/teleprompt/test_knn_fewshot.py`

## Phase 8: Streaming (`streaming`)
Goal: minimal streaming parity.

Implement:
- `streamify`, status messages, stream listeners (Tier 1 subset).

Acceptance tests:
- `tests/streaming/test_streaming.py` (Tier 1 subset)

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
