# dspy4s Port Scope (Baseline: DSPy 3.1.3)

> The actionable **3.2.1** deltas have been ported on top of the 3.1.3 baseline
> (see [PORT_BACKLOG.md](PORT_BACKLOG.md#upstream-parity-target)).

See [`PORT_MAP.md`](PORT_MAP.md) for the full dspy4s ⇄ Python name mapping
and the running ledger of deliberate behavioral deltas.

## Goal
Deliver a Scala `3.8.1` implementation of DSPy with strong parity on the core authoring/runtime path used for building, running, and evaluating programs.

Reference source: `/Users/jpablo/GitHub/dspy` (version `3.1.3` from `pyproject.toml`).

## Parity Model
- `Tier 0 (must-match)`: core signatures, module runtime semantics, adapters, LM behavior, tracing/settings/callbacks, evaluation, and first optimizers.
- `Tier 1 (supported with explicit deltas)`: streaming (v1 minimal: raw token + status events; per-field `StreamListener` deferred — see `../STREAMING_POSTPONED.md`), advanced adapters/types, additional retrieval helpers.
- `Tier 2 (deferred)`: heavy/experimental optimizers, full fine-tuning providers, Python-specific interpreter features.

## In Scope (v1)
1. Core type system and signatures
- String signature DSL (`"q -> a"`) via `Signature.fromString` / `SignatureLayout.parse`
- Programmatic / class-like signature construction (`SignatureBuilder`, `SignatureLayout.create`)
- **Typed signatures layer** (`dspy4s.typed.Signature[I, O]` with case-class
  derivation, function-type macro, trait-spec macro, builder, and string
  DSL surfaces; see [TYPED_SIGNATURES_GUIDE.md](../TYPED_SIGNATURES_GUIDE.md))
- Input/Output field metadata (`description`, `prefix`, constraints, defaults)
- Signature mutation APIs (`append`, `prepend`, `insert`, `delete`,
  `withInstructions`, `withUpdatedField*`) — now `private[dspy4s]` so
  composite programs use them while user code stays on the typed surface

2. Core primitives and runtime
- `Example`, `DynamicPrediction` (erased) / `Prediction[O]` (typed),
  `Completions`
- `Module[I, O]` trait. (No nested parameter/predictor traversal — that's the
  open **G-1** gap; the current mechanism is the `PredictOps[P]` typeclass over a
  single predictor, and composite traversal is tracked as G-1 in
  [PORT_GAPS.md](PORT_GAPS.md).)
- Settings/context model (global + scoped overrides)
- Trace, history, usage tracking, callback hooks

3. LM and adapter path
- `LanguageModel` (was Python's `BaseLM` + `LM`)
- OpenAI-compatible chat path. (Responses-mode *parsing* is scaffolded, but the
  request path is identical to chat — there is no distinct `/responses` request
  wiring.)
- Caching (memory + disk), retries, rollout behavior, history updates
- `ChatAdapter`, `JSONAdapter`, `XMLAdapter` (TwoStepAdapter not yet ported)
- Tool-calling bridge (`ToolFunction`, `ToolCallRequest` / `ToolCallResult`)

4. Program modules
- `Predict[I, O]` (typed) and `DynamicPredict` (erased), backed by a
  shared `PredictEngine`
- `ChainOfThought` (typed)
- `ReAct`, `Parallel`, `BestOfN`, `Refine`, `Aggregation.majority`
- `MultiChainComparison`, `ProgramOfThought`, `CodeAct` (scaffolded — see
  PORT_MAP §2a for interpreter parity caveats)

5. Evaluation
- `Evaluate`, `EvaluationResult`
- Core metrics: `ExactMatch`, `ContainsMatch`, `F1Score`, `AnswerMatch`,
  `PassageMatch`, `FunctionMetric`
- Parallel evaluation executor semantics

6. Optimizers (first wave)
- `LabeledFewShot`
- `BootstrapFewShot`
- `BootstrapFewShotWithRandomSearch`
- `PredictOps[P]` typeclass — the dspy4s equivalent of Python's
  `Teleprompter` parameter introspection (see PORT_LANGUAGE_NOTES)

## Explicitly Deferred (post-v1)

> Post-v1 progress note: most of this list has since shipped — `Ensemble`,
> `KNNFewShot`, `GEPA`, `MIPROv2`, `Embedder` + the retrievers track, `InferRules`,
> `COPRO` are all ported (see PORT_GAPS G-10/G-11/G-12 and PORT_MAP). What genuinely
> remains is tracked as PORT_GAPS **G-13..G-22**:

- `SIMBA` (G-13), `AvatarOptimizer` (G-14), `BetterTogether` (G-15),
  `BootstrapFewShotWithOptuna` (G-17), `propose` remainder (G-18)
- `GRPO` / `BootstrapFinetune` and provider fine-tuning / local SFT stack (G-16)
- `ReActV2` — upstream experimental native-tool-calling ReAct (G-19)
- `RLM` and the sandboxed Deno + Pyodide interpreter behind
  `ProgramOfThought` / `CodeAct` (G-20; the contract trait `CodeInterpreter`
  exists; only a plain `python3 -c "..."` subprocess impl ships today)
- `datasets` module (G-21); LM providers beyond OpenAI — Anthropic/Ollama (G-22)
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
