# dspy4s Port Scope (Baseline: DSPy 3.1.3)

## Goal
Deliver a Scala `3.8.1` implementation of DSPy with strong parity on the core authoring/runtime path used for building, running, and evaluating programs.

Reference source: `/Users/jpablo/GitHub/dspy` (version `3.1.3` from `pyproject.toml`).

## Parity Model
- `Tier 0 (must-match)`: core signatures, module runtime semantics, adapters, LM behavior, tracing/settings/callbacks, evaluation, and first optimizers.
- `Tier 1 (supported with explicit deltas)`: streaming, advanced adapters/types, additional retrieval helpers.
- `Tier 2 (deferred)`: heavy/experimental optimizers, full fine-tuning providers, Python-specific interpreter features.

## In Scope (v1)
1. Core type system and signatures
- String signature DSL (`"q -> a"`)
- Programmatic/class-like signature construction
- Input/Output field metadata (`desc`, `prefix`, constraints, defaults)
- Signature mutation APIs (`append`, `prepend`, `insert`, `delete`, `with_instructions`, `with_updated_fields`)

2. Core primitives and runtime
- `Example`, `Prediction`, `Completions`
- `BaseModule`/`Module` behavior (including nested parameter traversal)
- Settings/context model (global + scoped overrides)
- Trace, history, usage tracking, callback hooks

3. LM and adapter path
- `BaseLM` + `LM` abstraction
- OpenAI-compatible chat + responses model path
- Caching (memory + disk), retries, rollout behavior, history updates
- `ChatAdapter`, `JSONAdapter`, `XMLAdapter`, `TwoStepAdapter`
- Tool-calling bridge (`Tool`, `ToolCalls`) for native function-calling path

4. Program modules
- `Predict`, `ChainOfThought`, `ReAct`, `Parallel`
- `BestOfN`, `Refine`
- `Retrieve` abstraction and retrieval integration contract

5. Evaluation
- `Evaluate`, `EvaluationResult`
- Core metrics in `evaluate/metrics.py`
- Parallel evaluation executor semantics

6. Optimizers (first wave)
- `Teleprompter`
- `LabeledFewShot`
- `BootstrapFewShot`
- `BootstrapFewShotWithRandomSearch`
- `Ensemble` and `KNNFewShot`

## Explicitly Deferred (post-v1)
- `GEPA`, `MIPROv2`, `SIMBA`, `GRPO`, `AvatarOptimizer`, full optimizer surface
- Full `RLM` and full `ProgramOfThought`/`CodeAct` interpreter stack parity
- Provider-specific fine-tuning implementations (`databricks`, local SFT stack)
- Full multimodal reliability matrix and all generated reliability suites
- Binary compatibility with Python `cloudpickle` artifacts

## Non-Goals
- Reproducing Python metaclass/frame-introspection internals exactly.
- Preserving Python-specific mutation semantics when Scala-safe alternatives are cleaner.

## v1 Exit Criteria
1. API
- Documented dspy4s API equivalents for all Tier 0 features.
- Explicit compatibility notes for each known semantic difference.

2. Behavior
- Green parity tests for core runtime scenarios:
  - signature parse/mutation/defaults
  - `Predict` end-to-end via Chat/JSON/XML adapters
  - cache keying and rollout semantics
  - callback start/end dispatch behavior
  - module trace/history propagation
  - `Evaluate` + threaded execution
  - `BootstrapFewShot`/`BootstrapFewShotWithRandomSearch` compile loop

3. Quality gates
- Multi-module `sbt compile` + `sbt test` passing.
- No unresolved Tier 0 blocking deltas.
