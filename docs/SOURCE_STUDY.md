# DSPy Source Study (for dspy4s)

## Baseline
- Source root: `/Users/jpablo/GitHub/dspy`
- Target version: `3.1.3`
- Python files in `dspy/`: `138`
- Test files in `tests/`: `114`

## Package Footprint (approx LOC)
- `teleprompt`: 24 files, ~5966 LOC
- `adapters`: 19 files, ~3999 LOC
- `clients`: 10 files, ~2735 LOC
- `predict`: 19 files, ~2376 LOC
- `primitives`: 8 files, ~1786 LOC
- `utils`: 18 files, ~1789 LOC
- `signatures`: 4 files, ~945 LOC
- `streaming`: 4 files, ~893 LOC
- `evaluate`: 4 files, ~863 LOC
- Remaining (`datasets`, `retrievers`, `propose`, `dsp`, etc.) smaller but still relevant

## Public API Surface (from `dspy/__init__.py`)
Primary public clusters:
- Signatures: `Signature`, `InputField`, `OutputField`, helpers
- Primitives: `Module`, `Example`, `Prediction`
- Programs: `Predict`, `ChainOfThought`, `ReAct`, `ProgramOfThought`, `RLM`, etc.
- Adapters: `ChatAdapter`, `JSONAdapter`, `XMLAdapter`, `TwoStepAdapter`, custom types
- LM: `LM`, `BaseLM`, cache config, provider helpers
- Optimizers: `BootstrapFewShot`, `BootstrapRS`, `MIPROv2`, `GEPA`, etc.
- Utilities: settings (`configure/context`), logging, async/sync wrappers, streaming

## Core Execution Path Observed
Path to preserve first:
1. `Predict` preprocess (`signature`, `demos`, `config`, default inputs)
2. Resolve LM from call/module/global settings
3. Adapter call (`format` -> LM -> `parse`)
4. Postprocess to `Prediction.from_completions`
5. Trace/history/usage updates through settings context

Critical files:
- `dspy/predict/predict.py`
- `dspy/adapters/base.py`
- `dspy/adapters/chat_adapter.py`
- `dspy/adapters/json_adapter.py`
- `dspy/clients/base_lm.py`
- `dspy/clients/lm.py`
- `dspy/dsp/utils/settings.py`
- `dspy/utils/callback.py`

## Subsystem Findings
1. Signatures (`dspy/signatures/*`)
- Python uses metaclass + AST parsing + caller frame introspection for custom type discovery.
- Scala port should preserve DSL behavior, not Python introspection tricks.

2. Settings/Context (`dspy/dsp/utils/settings.py`)
- Hybrid global + context-local overrides with thread/async ownership rules.
- This is foundational for reproducible behavior in modules, parallel execution, and callbacks.

3. LM/Cache (`dspy/clients/*`)
- LM wrapper normalizes chat/text/responses modes and performs cache/retry/history.
- `rollout_id` + temperature semantics are important for optimizer behavior.

4. Adapters (`dspy/adapters/*`)
- Adapter is the core contract boundary.
- Structured output modes + fallback behavior are central to robustness.

5. Programs (`dspy/predict/*`)
- Some modules are thin composition wrappers (`ChainOfThought`), others are complex (`ReAct`, `RLM`).
- `Parallel` and module `batch()` rely on settings propagation and error thresholds.

6. Evaluation (`dspy/evaluate/*`)
- `Evaluate` is simple but relies heavily on parallel semantics and failure handling.

7. Optimizers (`dspy/teleprompt/*`)
- Large and diverse surface.
- `LabeledFewShot`, `BootstrapFewShot`, `BootstrapFewShotWithRandomSearch` are the best initial target set.

## Test Landscape Highlights
- Heavy coverage in: adapters, predict, teleprompt, utils, signatures.
- Core parity test sources to mirror first:
  - `tests/signatures/test_signature.py`
  - `tests/predict/test_predict.py`
  - `tests/adapters/test_chat_adapter.py`
  - `tests/adapters/test_json_adapter.py`
  - `tests/clients/test_lm.py`
  - `tests/utils/test_settings.py`
  - `tests/evaluate/test_evaluate.py`
  - `tests/teleprompt/test_bootstrap.py`
  - `tests/teleprompt/test_random_search.py`

## Porting Implications
1. Need a strict contract-first implementation around:
- signature model
- adapter interface
- LM interface
- settings context propagation

2. Prioritize behavior over API breadth:
- support fewer features first, but keep semantics consistent on supported features.

3. Avoid Python-specific coupling:
- do not replicate frame-inspection and metaclass internals if Scala-native design can preserve outcomes.

4. Treat interpreter-heavy modules (`ProgramOfThought`, `CodeAct`, `RLM`) as staged features.
