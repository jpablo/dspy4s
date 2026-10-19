# dspy4s ⇄ Python DSPy — Known Gaps & Issues

> **Companion docs:** [PORT_DIFFERENCES.md](PORT_DIFFERENCES.md) is the
> high-level "what changed shape and why" narrative;
> [PORT_MAP.md](PORT_MAP.md) is the per-symbol mapping + behavioral-delta
> ledger; [PORT_BACKLOG.md](PORT_BACKLOG.md) is the phase plan.
>
> **This doc is an actionable ledger of gaps and issues we know about and
> intend to revisit** — places where dspy4s is missing a capability Python
> DSPy has, or where a port decision left a rough edge. Unlike
> `PORT_DIFFERENCES.md` (which explains *intentional* shape changes), entries
> here are things we likely want to *close*.

## How to use

- Each gap has a stable `G-N` id, a one-line summary, status, and a body
  with: the Python reference, dspy4s's current state, why it matters, and a
  proposed direction.
- Add new gaps at the end with the next id; don't renumber existing ones.
- When a gap is closed, mark it `Resolved` with the commit/PR, and keep the
  entry (don't delete) so the history stays legible.

**Status legend:** `Open` · `In progress` · `Resolved` · `Won't fix (by design)`

---

## G-1 — No predictor-introspection abstraction (Python's `BaseModule.named_predictors`)

**Status:** Resolved (P1–P6, commits 30420f3 / 25d7e8d / 9bcf99f / dd466be / c459d07 / 1657f9c)

**Resolution.** Closed by an `OptimizableStructure[P]` / `OptimizableLeaf[P]` typeclass pair
with Scala 3 Mirror derivation (`modules/programs/.../optimization/`). `inspect`
enumerates non-executable `OptimizableView`s (read-only metadata + optimizable parameters),
`read` projects their `OptimizableParameters` values, and `replace` rebuilds the program immutably.
Each `OptimizableLeaf` leaf satisfies Get-Put, Put-Get, Put-Put, and the metadata frame exactly.
Composite structure satisfies exact no-op replacement, read-after-write, and stable metadata/order;
override-backed composites satisfy last-write-wins observationally through `read`. User composites derive it for free; framework composites
(`ReAct`, `CodeAct`, `MultiChainComparison`) get
hand-written instances and had their sub-predicts **hoisted to stable fields**
(closing the "Related" sub-gap below). Optimizers moved off the single-`DynamicPredict`
`PredictOps` assumption onto `OptimizableStructure` (the `LabeledSampleProgram` glue was deleted),
and a `ProgramRunner[P]` capability (`programs/ProgramRunner.scala`) dropped the `P <: DynamicModule` bound
so domain-valued programs and user composites are now optimizable end-to-end. The legacy
`PredictOps` typeclass and its bridge were removed in P6 — `OptimizableStructure` is the sole
introspection typeclass. Writable state is uniformly instructions + demos + module config;
`MultiChainComparison` has no `ProgramRunner` because its `MultiChainInput` includes candidate
predictions in addition to the base record input; arbitrary user composites supply their own `ProgramRunner`.

**Summary (original).** dspy4s had no way to enumerate (and transform) the `Predict`s
inside an arbitrary composite program. Python's optimizers rely on exactly
this; ours could only reach a single `DynamicPredict` via the `PredictOps`
typeclass.

### Python reference

In Python, the class hierarchy is `Predict / ChainOfThought / ReAct … →
Module → BaseModule`, and `Predict` also extends the empty marker class
`Parameter`. [`BaseModule`](../../../dspy/dspy/primitives/base_module.py)'s
job is reflection-based introspection and **in-place mutation** of a tree of
learnable `Parameter`s:

- `named_parameters()` / `named_sub_modules()` walk `self.__dict__`
  recursively and collect everything that is `isinstance(x, Parameter)` /
  a sub-module.
- `set_lm` / `get_lm`, `dump_state` / `load_state`, `save` / `load`,
  `deepcopy` / `reset_copy`.

Optimizers (teleprompters: `BootstrapFewShot`, MIPRO, …) use
`named_predictors()` to find every `Predict` in an arbitrarily-composed
user `Module`, attach demos (`predictor.demos = …`), set LMs, and clone
candidate programs — all with **zero glue from the user**, because reflection
finds the predictors no matter how they were nested.

### dspy4s state before resolution

We deliberately did **not** port `BaseModule` (see the rationale below — most
of it is un-idiomatic in immutable Scala, where these shapes are enforced at compile time). Instead:

- [`Module`](../../modules/programs/src/main/scala/dspy4s/programs/contracts/Module.scala)
  is just the callable program base (`moduleName` / `apply` / `applyAsync` / `forward`).
- Optimizers use a [`PredictOps[P]`](../../modules/optimize/src/main/scala/dspy4s/optimize/PredictOps.scala)
  typeclass (`name` / `layout` / `demos` / `withDemos`) — immutable
  (`withDemos` returns a `.copy`), type-directed rather than reflective.
- The LM lives in `RuntimeContext` (ambient `using`), so there is no
  per-module `set_lm`/`get_lm`. Immutable case classes make `deepcopy` a
  non-issue.

**This collapse is the right call** — porting `BaseModule` literally would
import runtime reflection over instance fields plus an empty `Parameter`
marker used for `isinstance`, both of which fight Scala's grain.

### The gap

`PredictOps` only has an instance for `DynamicPredict`. There is **no
operation to enumerate the predictors inside an arbitrary composite program**
(a user's custom `Module` composing several `Predict`s, a `ReAct`, etc.). So
an optimizer cannot generically tune a multi-predict program the way Python's
`named_predictors()` allows.

### Why it matters

- Optimizers are currently limited to single-`DynamicPredict`-shaped targets
  (plus the bespoke composites the optimizer code builds itself, e.g.
  `LabeledSampleProgram`). Tuning a user-defined composite needs hand-written
  glue per program type.
- It blocks faithfully porting the parts of DSPy that walk `named_predictors`
  (state save/load of a whole program, `set_lm` semantics, optimizer
  generality).

### Proposed direction

A typeclass-based equivalent of `named_predictors` — *not* a reflective
`BaseModule`. Candidate shapes:

- a capability method on the program contract, e.g.
  `def predictors: Vector[DynamicPredict]` (and a way to rebuild with updated
  ones), or
- a derivable typeclass `OptimizableStructure[P]` that yields/updates the contained
  predictors,

so optimizers can read and immutably replace the learnable parts of any
composite. Decide the shape before extending optimizer coverage.

### Related

- **ReAct builds its sub-predicts in `execute`, not as fields.** Python's
  `ReAct.__init__` creates `self.react` / `self.extract` once
  ([react.py](../../../dspy/dspy/predict/react.py) L88–89); dspy4s rebuilds
  the `DynamicPredict` instances (and re-runs `ChainOfThought.augmentLayout`)
  on every `execute`
  ([ReAct.scala](../../modules/programs/src/main/scala/dspy4s/programs/strategies/ReAct.scala)) in `forward`.
  `CodeAct` has the same pattern. This is currently harmless *because* there
  is no predictor-introspection layer that would need those predicts to be
  stable, addressable fields — i.e. it is a **symptom of G-1**. When G-1 is
  closed, ReAct/CodeAct should hoist their sub-predicts to fields (built once,
  like `__init__`) so they are discoverable and tunable.

---

## G-2 — Module-lifecycle wrapping was opt-in by base class

**Status:** Resolved (commit 6896df3; `apply` stayed `final` through the 42671c2 re-genericization)

**Summary.** The callback / trace / history wrapping lived on `BasePredictProgram` — an abstract *class* a program
opted into by extending it. Programs that extended the program *type* directly (`Refine`, `BestOfN`,
`MultiChainComparison`) overrode the caller entry and silently **bypassed** the wrapping — unlike Python, where
`Module.__call__` is universal and non-bypassable.

### Resolution

Merged the generic `Module[-In,+Out]` and `BasePredictProgram` into a single concrete `Module`
([programs/contracts/Module.scala](../../modules/programs/src/main/scala/dspy4s/programs/contracts/Module.scala))
whose `apply` is `final` (the wrapping) delegating to an abstract `forward`. With no bare layer left to extend, the
lifecycle wrapping is **universal and non-bypassable** — every program emits identical module events / trace.
`Refine` / `BestOfN` / `MultiChainComparison` (and the streaming test composites) moved `apply` → `forward` and now
get the wrapping; `BestOfN` accordingly records its own trace entry. See
[PORT_MODULE_HIERARCHY.md](PORT_MODULE_HIERARCHY.md).

**Evolution.** `Module` later became generic again and now uses semantic parameters throughout:
`Module[I, O]#forward` returns `Prediction[O]`. The record-valued program type is
`DynamicModule = Module[DynamicValue.Record, DynamicValue.Record]`; its `RawPrediction` is lifted into the
uniform result by `Prediction.dynamic`. This removes the former domain/record result asymmetry while `apply` remains
`final` on the one common base, so the wrapping is still universal and non-bypassable: G-2 stays resolved.

---

## G-3 — No per-module `config` / `set_lm` (only per-call config)

**Status:** Resolved — module `config` (commit b85fe27) + per-module bound LM `withLm`/`boundLm` (commit b2d0096)

**Resolution (config).** `DynamicPredict`, `Predict[I, O]`, and `ChainOfThought[I, O]` now carry an immutable
module-level `config: DynamicValue.Record`, threaded into `PredictEngine` and merged
*under* the per-call config (per-call keys win: `{**module, **call}`); empty module
config is unchanged behavior.

**Resolution (bound LM).** `DynamicPredict` and `Predict[I, O]` now also carry an optional
`lm: Option[LanguageModel]`, threaded into `PredictEngine`, which resolves the model as
`lm.fold(runtime.resolveModel)(Right(_))` — a bound LM wins over the ambient `RuntimeContext`
LM, so a program can pin different models to different predictors. `Predict.withLm(model)`
(immutable `set_lm`) and `Predict.boundLm` (`get_lm`) are the accessors. The bound LM is
an execution binding, not part of `OptimizableParameters` (like `runtime`), so `ProgramPersistence` excludes it.

**Summary.** Python's `Predict` carries module-level `config` and a `set_lm`/`get_lm`
binding. dspy4s only supported per-*call* config; there was no place to attach a
module-scoped config or a module-bound LM.

### Python reference

`dspy.Predict(signature, **config)` stores `self.config` and `self.lm`
(`set_lm`/`get_lm`), so a predictor can carry its own generation params and a
pinned LM independent of the ambient settings.

### dspy4s state before resolution

Config is supplied per call; the LM lives in `RuntimeContext` (ambient `using`),
so there is no per-module `config` field and no `set_lm`/`get_lm`.

### Why it matters

Optimizers and user code that pin different LMs / sampling params to different
predictors in one program can't express that today.

### Proposed direction

Add an immutable module-level `config` (and optional bound LM) to `DynamicPredict`,
merged under the per-call override. Tier 0.

---

## G-4 — No program `save`/`load` + `dumpState`/`loadState`

**Status:** Resolved

**Resolution.** Closed by wiring the `OptimizableStructure[P]` evidence into `ProgramPersistence`,
so a single domain-valued or record-valued predictor and an arbitrary composite share one code path.
New primitives:

- `Example.dumpState` / `Example.fromState` (`modules/core/.../data/Example.scala`) —
  `{ "values": <record>, "inputKeys": [..], "augmented": <bool> }`.
- `OptimizableParameters.dumpState` / `OptimizableParameters.fromState`
  (`modules/programs/.../OptimizableParameters.scala`) —
  `{ "instructions": <string|null>, "demos": [<Example state>..], "config": <record> }`.
  This is the complete writable state for `DynamicPredict`, `Predict`, and `ChainOfThought`.
- `ProgramPersistence` (`modules/optimize/.../ProgramPersistence.scala`) —
  `dumpState` / `loadState` / `dumpJson` / `loadJson` / `save` / `load`, all
  `OptimizableStructure`-based: `{ "optimizableParameters": { "optimizable-0": <OptimizableParameters>, ... } }`.
  Ordinal IDs are validated on load. JSON via
  `Schema.dynamic.jsonCodec` (same codec as `SignatureLayout.dumpJson`); file IO wraps exceptions into
  `RuntimeError`.

**Round-trip scope.** Instructions, demos, and module config round-trip for every supported leaf.
Signature field structure and module name are read-only `OptimizableMetadata`; runtimes, output schemas,
bound LMs, tools, callbacks, and history are execution resources. Loading state into a fresh program
preserves all of them. The target must have the same optimizable structure and leaf order: `optimizable-N` IDs reject
missing/unknown ordinals and ignore JSON object order, but cannot detect a same-cardinality reorder.
The former layout-bearing entry format (`signature` + `demos` + `config`) is unsupported; regenerate artifacts.

**Summary (original).** There is no JSON state save/load for a program. The serialization
primitives exist on `SignatureLayout`, but nothing wires them up to a program,
and the demos are never persisted.

### Python reference

`BaseModule.dump_state`/`load_state` traverse named parameters. With
`save_program=False`, `save`/`load` persist that state as JSON or pickle; a
current `Predict` state contains signature, demos, traces, training data, and
bound LM. `save_program=True` is the separate whole-architecture cloudpickle
mode.

### dspy4s state before resolution

`SignatureLayout` has the layout (de)serialization primitives, but no program
exposes `save`/`load`/`dumpState`/`loadState`; demos and config are never written
out. The demos in the example programs are never serialized.

### Why it matters

Compiled programs can't be persisted and reloaded — the standard
"optimize once, deploy the artifact" workflow is unavailable.

### Proposed direction

Wire the `SignatureLayout` primitives into a program-level `dumpState`/`loadState`
(JSON) and `save`/`load`. Tier 0. A single `Predict` is doable now; composite
programs need the optimizable-structure layer (depends on **G-1**).

---

## G-5 — `Refine` is a thin best-of-n alias (no `OfferFeedback` loop)

**Status:** Resolved (v1, commit ddecaf2; **v2 per-module advice** — see below)

**Resolution.** `Refine` implements the iterative-feedback loop (`BestOfN` untouched):
on each sub-threshold non-final attempt it generates LM advice via an `OfferFeedback`
sub-program grounded in that attempt's runtime trace (trajectory) + program I/O + reward +
threshold, and injects it as a `hint_` input into the next attempt via a
`HintInjectingAdapter`.

**v2 — per-module advice (parity).** `Refine[P, I, O]` now retains the inner program type
`P` (an upper-bounded module, so `I`/`O` still infer at call sites — `BestOfN`-style
ergonomics, no call-site type annotations) and requires `OptimizableStructure[P]`. `OfferFeedback`
now takes a `module_names` input and emits `advice` as a JSON object `{component: advice}`
keyed by the inner program's named predictor views (`OptimizableStructure.inspectNamed`, G-12 P-c). Each
predictor's call is matched to its advice by its `SignatureLayout` — the dspy4s stand-in
for Python's `signature2name[signature]` object-identity routing — and only that predictor's
`hint_` is injected (absent/`N/A` → no hint). A bare (non-JSON) advice string degrades to
uniform advice across all components, preserving the v1 single-predictor behavior. This
required relocating `OptimizableStructure`/`OptimizableLeaf` from `optimize` to `programs` (they describe
program structure, not optimization) to avoid a `programs`→`optimize` cycle. **Remaining
delta:** grounding is the trace + I/O, not program/reward source code (dspy4s has no source
introspection); routing is by layout value-equality, not object identity (structurally
identical predictors collapse to one advice entry — they'd get identical advice anyway).

**Summary.** dspy4s's `Refine` was just a best-of-n wrapper. Python's `Refine`
runs an `OfferFeedback` advice/feedback loop between attempts; that loop was
missing here.

### Python reference

`dspy.Refine` uses an `OfferFeedback` signature to generate advice from a failed
attempt and feed it into the next attempt, not merely pick the best of N
independent samples.

### dspy4s current state

`Refine.scala` is a best-of-n alias only; there is no feedback/advice loop.
(Note: `PORT_MAP.md` overstates `Refine` as "✅ ported" — see the downgrade
to "⚠️ partial" there.)

### Why it matters

Without the feedback loop, `Refine` is not behaviorally equivalent to Python's;
it can't iteratively improve a single sample.

### Proposed direction

Port the `OfferFeedback` advice/feedback loop. Depends on **G-1** (needs to
introspect/rebuild the wrapped predictor across attempts).

---

## G-6 — `Metric.score` has no `RuntimeContext` (blocks LLM-judged metrics)

**Status:** Resolved

**Resolution.** `Metric.score` now takes `(using RuntimeContext)`
(`modules/evaluate/.../contracts/EvaluateContracts.scala`), so a metric can resolve
the ambient LM/adapter and run a judge sub-program during scoring. The six builtin
string-comparison metrics ignore the context (behavior unchanged); `FunctionMetric`'s
wrapped `fn` stays pure. Call sites were threaded: `Evaluate` hands each metric
`RuntimeEnvironment.current` from inside the parallel-executor worker (the captured
context is restored into that thread's local); `BootstrapFewShot.compileInternal`
already had `using ctx` in scope; the offline examples supply `RuntimeContext()`.

On top of the contract change, the LLM-judged auto-evaluation metrics from
`dspy/evaluate/auto_evaluation.py` are ported in
`modules/evaluate/.../metrics/AutoEvaluation.scala`: an `f1Score(precision, recall)`
helper (clamp `[0,1]`, harmonic mean, `0` if either is `0`), `SemanticF1`
(`question, ground_truth, system_response -> recall, precision` judged via a
`ChainOfThought`-augmented `DynamicPredict`, plus a `decompositional` variant with
key-idea fields), and `CompleteAndGrounded` (completeness × groundedness). Deltas:
the judge runs over a runtime `SignatureLayout`/`DynamicPredict` (no `Module` with statically known I/O), field names
are configurable string keys (defaults follow upstream's
`example.question`/`example.response`/`pred.response`), the `threshold` field is
retained for parity but scoring always returns the raw `Double`, and groundedness
reads `pred.context` by key (dspy4s has no retriever to populate it).

**Summary.** A metric's scoring function cannot call an LM, because
`Metric.score` is given no `RuntimeContext`. This blocks porting LLM-judged
metrics like `SemanticF1` / `CompleteAndGrounded`.

### Python reference

Python metrics are plain callables that can freely invoke an LM, so judged
metrics (`SemanticF1`, `CompleteAndGrounded`) run an LM during scoring.

### dspy4s current state

`Metric.score` has no `RuntimeContext` in scope, so it cannot reach an LM.

### Why it matters

LLM-judged auto-evaluation metrics are impossible to port until scoring can
access the runtime/LM.

### Proposed direction

Thread `RuntimeContext` (or an LM handle) into the metric scoring path. Tier 1.

---

## G-7 — No native function-calling preprocessing / JSONAdapter `response_format`

**Status:** Resolved — `response_format` (v1, commit ed2c69f) and native function-calling (G-7b) both done

**Resolution (response_format / structured outputs).** Added the request-influence seam
`FormattedPrompt.requestOptions` (+ `FormattedPrompt.mergeOptions`); `PredictEngine` and
`Adapter.execute` merge it under the per-call/module options. `JSONAdapter`, when the
ambient LM `supportsResponseSchema` and an `outputJsonSchema` is present, emits
`response_format: {type:"json_schema", json_schema:{name, schema, strict:false}}` (prose
schema kept as fallback; malformed schema → prose only).

**Resolution (G-7b, native function-calling).** Ported faithfully from dspy `adapters/base.py`
(verified against upstream v3.3.0b1): native calling is an **adapter-level** feature, NOT a
ReAct change — dspy keeps ReAct on the text protocol in every version, so dspy4s ReAct is
unchanged. `ChatAdapter`/`JSONAdapter` gained `useNativeFunctionCalling` (off by default,
+ `parallelToolCalls`); the shared `NativeFunctionCalling` helper injects `tools` (built by
`ToolSchemaBridge.toOpenAiToolsDynamic`) into `requestOptions` and fills a `TypeRef.toolCalls`
output field from the provider's `tool_calls`, gated on `supportsFunctionCalling`. Tools reach
the adapter via `AdapterInvocation.tools` (pure `ToolSpec` data); `DynamicPredict`/`Predict`/`PredictEngine`
thread them through (both the dynamic and paths). The `tool_choice` and `parallel_tool_calls`
knobs are supported. Also fixed a foundational bug: `ProviderLanguageModel` dropped a tool-only
output (`content:null` + `tool_calls`). Commits 671a37f, 1f50818, 09fa2b5, 221b404, 22c36de,
7838fb0, 460accf. All G-7b follow-ups closed; remaining tool-call refinements (forcing a specific
function via the object `tool_choice` form, a `ToolCalls` output shape) are net-new features,
not gaps. (JSON-Schema constraint embedding is tracked under G-9, not here.)

**Summary.** Adapters did not use native provider function-calling preprocessing,
and `JSONAdapter` did not emit `response_format` structured outputs. The
capability flags that would gate these now exist, but the adapters didn't consume
them yet.

### Python reference

Python adapters branch on LM capabilities to use native function-calling and
`response_format` (structured outputs) when available.

### dspy4s current state

`LanguageModel` now carries `supportsFunctionCalling` / `supportsResponseSchema`
/ `supportsReasoning` (the enabler, shipped b01c627), but adapters do not yet
branch on them: no native function-calling preprocessing, no `response_format`
on `JSONAdapter`.

### Why it matters

Structured-output and native tool-calling paths are more robust than text
parsing and match upstream behavior on capable providers.

### Proposed direction

Have the adapters branch on the capability flags. Tier 1.

---

## G-8 — `TwoStepAdapter` not ported

**Status:** Resolved

**Resolution.** Ported as `modules/adapters/.../TwoStepAdapter.scala`. It fits the single-call `Adapter` shape:
`format` builds a plain natural-language prompt for the MAIN LM (resolved from the ambient context), and `parse`
— which receives the `RuntimeContext` — runs the second extraction call itself, using a `ChatAdapter` over an
on-the-fly `text -> <output fields>` signature against the injected `extractionModel`. Carries upstream's
documented limitation: the extractor signature is built fresh with no demos, so the extraction step can't be
optimized. Test-first (format shape, extraction round-trip, extraction-failure propagation).

**Summary.** Python's `TwoStepAdapter` is not ported.

### Python reference

`dspy.TwoStepAdapter` runs a generation step with a smaller/cheaper model then a
structured extraction step.

### dspy4s current state

Only `ChatAdapter` / `JSONAdapter` / `XMLAdapter` are ported.

### Why it matters

Two-step extraction is a supported adapter strategy upstream.

### Proposed direction

Port `TwoStepAdapter`. Tier 1.

---

## G-9 — No field-constraint rendering (`PYDANTIC_CONSTRAINT_MAP`)

**Status:** Resolved (v1, commit d8c80de)

**Resolution.** `FieldSpec` gained `constraints: Vector[String]`, a `FieldConstraints`
helper builds the exact upstream strings (`gt`/`ge`/`lt`/`le`/`minLength`/`maxLength`/
`multipleOf`, matching `PYDANTIC_CONSTRAINT_MAP`), `ChatAdapter` renders
`Constraints: <joined>` after the field description (the `get_field_description_string`
analog; unconstrained fields render byte-identically), and constraints round-trip through
`SignatureLayout.dumpState`/`fromState`. **v1 follow-ups:** constraints are settable
programmatically only (deriving them from the `zio-blocks Schema` surface needs a
constraint-annotation mechanism that doesn't exist yet). **Follow-ups now done:** constraints are a
structured `Constraint` ADT (`.render` for prose + `.schemaKeyword`/`.schemaValue` for JSON Schema);
`XMLAdapter`/`JSONAdapter` render the prose block (shared `AdapterConstraints`), and `JSONAdapter`
also embeds them *structurally* into the emitted `response_format` JSON Schema (`exclusiveMinimum`,
`maxLength`, …) — advisory under `strict:false`, but real structured signal.

**Summary.** dspy4s had no constraint vocabulary in `FieldSpec`, so Python's
constraint rendering (`gt`/`ge`/`lt`/`le`/`min_length`/…) had no equivalent.

### Python reference

Python renders field constraints into the prompt via `PYDANTIC_CONSTRAINT_MAP`
(`gt`, `ge`, `lt`, `le`, `min_length`, `max_length`, …).

### dspy4s current state

`FieldSpec` carries no constraint vocabulary; constraints can't be expressed or
rendered.

### Why it matters

Constraint hints in the prompt improve adherence and match upstream output.

### Proposed direction

Add a constraint vocabulary to `FieldSpec` and render it. Tier 1.

---

## G-10 — No `Embedder` + retrievers track (gates `KNN`/`KNNFewShot`)

**Status:** Resolved — the track is ported across three layers:

- **`Embedder`** (`lm/contracts`): one batched contract (texts in → one embedding row per text), the
  vectorization counterpart of `LanguageModel`. `Embedder.fromFunction` lifts a custom batch function
  (upstream's callable-model path); `Embedder.cached` memoizes per text. **`OpenAiEmbedder`**
  (`lm/providers`): `POST /embeddings` over the existing `HttpTransport`/`DynamicJson` stack, with
  `batchSize` splitting (upstream default 200), index-ordered rows, and the context-window 400 mapped to
  `ContextWindowExceededError`.
- **`KNN`** + **`EmbeddingsRetriever`** (`programs.retrievers`): KNN serializes INPUT fields as
  `"key: value | …"`, embeds the trainset eagerly, scores queries by raw dot product (upstream's plain
  `np.dot`). EmbeddingsRetriever is the in-memory corpus retriever (cosine by default). Documented deltas:
  brute force only (no JVM FAISS; upstream brute-forces under its 20k threshold anyway) and synchronous
  search (upstream's `Unbatchify` is a concurrency optimization).
- **`KNNFewShot`** (`optimize`): per-call dynamic few-shot — retrieve the query's k nearest trainset
  examples, `BootstrapFewShot` them onto the student as demos, run the result. Delta: upstream
  monkey-patches `forward` on a student copy; dspy4s programs are immutable, so `compile` returns a
  `KNNFewShotProgram` wrapper module (not a `Teleprompter`).

Deliberately NOT ported: `dspy.retrievers.retrieve` (the legacy `dspy.Retrieve`/global-RM path) and the
vendor RMs (`weaviate_rm`, `databricks_rm`) — vendor integrations out of scope.

**Summary.** There is no `Embedder` or retriever abstraction, which gates `KNN`
and `KNNFewShot`.

### Python reference

`dspy.Embedder` + the retrievers track back `KNN` / `KNNFewShot`.

### dspy4s current state

Neither embedders nor retrievers are ported.

### Why it matters

k-NN demo selection and retrieval-augmented programs are unavailable.

### Proposed direction

Port `Embedder` and the retrievers track. Tier 2.

---

## G-11 — `InferRules` optimizer is undocumented and unported

**Status:** Resolved — ported as `InferRules` in `modules/optimize`
(`dspy4s.optimize.InferRules` + `InferRulesConfig`). Induces natural-language rules
from the trainset (a rule-induction `DynamicPredict` run at `initTemperature` with a
per-candidate `rolloutId`) and appends them to each predictor's original instruction;
generates `numCandidates` rule-augmented programs, scores each on the valset (50/50
trainset split when none given), and returns the best. Composes `BootstrapFewShot`
for demos first. Deltas (documented in the class): composes rather than inherits
`BootstrapFewShot`; rule induction is a `Predict`, not `ChainOfThought` (the reasoning
field is omitted, as in COPRO); the context-window fallback narrows specifically on
`ContextWindowExceededError`. Tested offline (rule-augmented program wins; 50/50 split).

**Summary.** The `InferRules` optimizer is currently invisible in all docs and is
not ported.

### Python reference

`dspy.teleprompt.InferRules` infers natural-language rules from the trainset to
augment instructions.

### dspy4s current state

Not ported; not mentioned in any port doc until this entry.

### Why it matters

It's part of the upstream optimizer surface and was being silently dropped.

### Proposed direction

Track and port `InferRules`. Tier 2.

---

## G-12 — `GEPA` reflective prompt optimizer not ported

**Status:** Resolved — GEPA is algorithmically complete for dspy parity in `modules/gepa` (`dspy4s-gepa`):
reflective mutation + instance-Pareto selection, round-robin/all component selection, epoch-shuffled minibatch,
merge crossover, eval cache, run-dir resume, opt-in perfect-score early stop; live-model validated. Multi-objective
frontiers are deliberately not ported (dspy's scalar metric never uses them — see below). History:
prerequisites **P-d** (`FeedbackMetric`/`ScoreWithFeedback`), **P-a** (`captureFailureTraces` → failure
`TraceEntry`), **P-b** (`ParseError.raw`); and the engine — `Candidate` mapping, `GepaAdapter`
(`evaluate`/`makeReflectiveDataset`), `InstructionProposer` (reflective mutation), `GepaState` + Pareto
candidate selection, `GepaEngine` (reflective-mutation loop), and the `Gepa` facade. A deterministic
instruction-sensitive test shows GEPA discovering a better instruction (score 0 → 1.0 within budget).
**P-c done:** `OptimizableStructure.inspectNamed` surfaces the latent Mirror field labels (`"self"` for a standalone leaf,
field labels for a composite), while `readIdentified` assigns `OptimizableId`s from the canonical leaf order.
GEPA keys candidates and trace-index association by ID; labels remain display/prompt metadata and may reflect an
anonymous composition tree's current association. The `inspectNamed` capability is also what Refine per-module advice
(G-5 follow-up) needs — now **done** (G-5 v2;
`OptimizableStructure` relocated to `programs` to break the cycle). **Multi-predictor GEPA validated end-to-end:** a two-stage pipeline (hinter → answerer) where BOTH
predictors must improve — GEPA evolves both (per-component reflective datasets + name→trace association), score
0 → 1.0. **Round-robin component selection done** (gepa's default; `ComponentSelector.RoundRobin`/`All`).
**Live-model validation done:** `gepaSmokeMain` (gpt-4o-mini) evolved the vague baseline "Answer the question."
into a precise HAS_NUM/NO_NUM classifier, lifting the held-out validation score to 1.0 — the whole tower
(`FeedbackMetric` → failure-aware traces → reflective dataset → reflection LM → Pareto loop) confirmed against a
real LM. **Opt-in perfect-score early stop added** (`GepaConfig.stopOnPerfectScore`, default OFF to match
upstream gepa's run-to-budget behavior; on, it halts once the best candidate is perfect on validation — the smoke
harness enables it to avoid the post-convergence `skipPerfectScore` budget burn). **Epoch-shuffled minibatch
sampler done** (gepa's default `EpochShuffledBatchSampler` — sample-without-replacement per epoch with
least-frequent padding; `GepaConfig.batchSampler`, `RandomDraw` retained as the v0 option). **Merge crossover
done** (`MergeProposer` — the "genetic" half: combine two Pareto descendants of a common ancestor, taking each
component from whichever descendant improved it; multi-parent lineage + `ancestors`, dominator selection,
desirable-predictor triplet filter, stratified subsample acceptance gate, merge scheduling. `GepaConfig.useMerge`
default ON, matching dspy's wrapper; a no-op for single-component programs). **Eval cache done**
(`GepaEvalCache` — memoize scores-only `(candidate, example)` evals; only actual evals charge the budget; shared by
full-eval and the merge subsample). **Run-dir resume done** (`GepaStatePersistence` — JSON checkpoint of the search
state after each iteration; `optimize(..., runDir)` loads-or-seeds, so a resumed run keeps every discovered
candidate and re-spends no budget). **Multi-objective frontiers: deliberately NOT ported** — dspy's GEPA metric is
a scalar `ScoreWithFeedback` (`score: float`) and its adapter never passes objective vectors, so multi-objective is
a gepa-library-only path unused by the dspy surface dspy4s mirrors. (Update 2026-10-11: upstream now passes objective scores from the metric; see G-45. Update 2026-10-12: the objective frontiers are ported.) With that, GEPA is algorithmically complete for
dspy parity; the remaining gap items are G-10/G-11 (unrelated tracks).

**Summary.** `dspy.GEPA` (Genetic-Pareto reflective prompt evolution) is unported. The standout architectural
fact: **in Python, GEPA is NOT native code — it's a thin wrapper around an external library** (`gepa[dspy]==0.1.1`).
`dspy/teleprompt/gepa/` only implements a `DspyAdapter`; the optimization algorithm lives in the standalone `gepa`
package. So porting GEPA = (1) port the engine algorithm + (2) write a dspy4s adapter. Critically, **the engine is
pure Python stdlib (`random`/`json`/`hashlib`/`dataclasses`) — no numpy/scipy/Optuna** — so there are **no external
dependencies to replace** (unlike SIMBA/GRPO/Optuna). The "dependency" is just that the engine itself must be ported.

### Python reference

- Engine (generic, task-agnostic): `gepa/core/{engine,state,adapter,result}.py`, `gepa/strategies/*`,
  `gepa/proposer/{reflective_mutation,merge}.py`. Algorithm: select a parent from the **Pareto frontier** →
  sample a train minibatch → evaluate-with-traces → pick a component (round-robin) → build a reflective dataset
  `[{Inputs, Generated Outputs, Feedback}]` → reflection-LM proposes new instruction text (extracted from ``` blocks)
  → accept iff `sum(new) > sum(old)` on the minibatch → full-eval on valset → update the Pareto frontier. Optional
  `merge` crossover of two frontier descendants via a common ancestor. Budget via `max_metric_calls` / `auto`.
- Bridge: `dspy/teleprompt/gepa/{gepa,gepa_utils,instruction_proposal}.py`. The `DspyAdapter` implements three
  methods the engine calls: `evaluate(batch, candidate, capture_traces)`, `make_reflective_dataset(candidate,
  eval_batch, components)`, optional `propose_new_texts(...)`. A candidate is `dict[componentName -> instruction]`.
- Feedback metric (`GEPAFeedbackMetric`): `(gold, pred, trace, predName, predTrace) -> ScoreWithFeedback` — a score
  **plus textual feedback**, at both program- and predictor-level. The feedback text is what the reflection LM learns
  from; it is the conceptual heart of GEPA.

### dspy4s current state — what we have, and the prerequisites the spike found

Already shipped (enablers): `OptimizableStructure[P]`/`OptimizableLeaf.set` (G-1 — the candidate↔program mapping, dspy's
`named_predictors()` + `with_instructions()`), `ProgramRunner[P]` + `Evaluate` (batch scoring), per-module trace
(`TraceEntry`), bound/reflection LM (G-3). A **single-predictor** program already traces cleanly.

A verification spike (trace + failure capture) surfaced four bounded prerequisites:

- **P-a — failure-trace capture.** `Module.apply` records a `TraceEntry` only on `Right` (`case Left(_) => ()`),
  so failed predictor calls leave no trace — but reflection's signal IS failures. Record a trace entry on failure
  with the raw output.
- **P-b — parse-failure-as-feedback.** `PredictEngine.parseOutputs` aborts on `adapter.parse → Left` and discards
  the raw `LmOutput.text`. Need a `FailedPrediction`-style capture (raw completion + low score + format feedback),
  not an aborting error (dspy's `add_format_failure_as_feedback`).
- **P-c — named predictors.** At the time of this prerequisite spike, `OptimizableStructure.read` was positional
  (`Vector[DynamicPredict]`); trace entries are keyed
  by `component = moduleName` (a possibly-colliding string). GEPA's whole model is keyed by unique component names.
  The names are **latent** — the Mirror derivation has `MirroredElemLabels` but only uses `MirroredElemTypes`;
  surfacing a named `read` is bounded. **This is also the exact blocker for Refine per-module advice (G-5 follow-up)
  — building it once unblocks both.** Needed only for *multi-predictor* programs.
- **P-d — feedback metric** (`ScoreWithFeedback`, program- + predictor-level). New contract; the keystone.

### Proposed direction (slicing)

- **v0 (de-risk):** single-predictor GEPA — needs P-a, P-b, P-d only (P-c unnecessary when there's one predictor).
  Proves the engine + reflective feedback loop end-to-end. Engine pieces: `GEPAState`, instance-type Pareto frontier,
  pareto/current-best candidate selector, round-robin component selector, epoch-shuffled batch sampler, reflective-
  mutation proposer, `max_metric_calls` budget, result.
- **v1:** add P-c (named predictors) → multi-predictor programs; closes Refine per-module advice as a freebie.
- **v2:** merge proposer (crossover), multi-objective frontier types, eval cache, run_dir resume, wandb/mlflow.

Lives in `modules/gepa` (`dspy4s-gepa`, depends on `optimize` for the shared `OptimizableStructure`/`ProgramRunner` capabilities
and the `Teleprompter` contract). Tier 2.

---

## G-13 — `SIMBA` optimizer not ported

**Status:** Open

**Summary.** `dspy.SIMBA` (Stochastic Introspective Mini-Batch Ascent) is unported.

### Python reference

`dspy/teleprompt/simba.py` (+ `simba_utils.py`, ~450 lines, exported as `dspy.SIMBA`). Samples train
mini-batches, runs candidate programs over them at varied temperatures, identifies challenging examples
(high score variance across candidates), and applies one of two self-improvement strategies per round:
`append_a_demo` (attach a successful trajectory as a demo) or `append_a_rule` (LM-introspect a
failure→success pair into an instruction rule). Soft dependency on numpy (`require("numpy")` — softmax
sampling, easily replaced).

### dspy4s current state

Not ported.

### Why it matters

Part of the exported optimizer surface; the demo+rule dual strategy is distinct from both COPRO/MIPRO
(instruction search) and GEPA (reflective evolution).

### Proposed direction

Build the port on the existing `OptimizableStructure`, `ProgramRunner`, `Evaluate`, and per-module trace. The trajectory
capture GEPA added (failure traces, per-component association) covers SIMBA's introspection needs; the
numpy softmax is a few lines of Scala. Tier 2.

---

## G-14 — `AvatarOptimizer` (and the `Avatar` actor program) not ported

**Status:** Won't fix (by design)

**Summary.** `dspy.AvatarOptimizer` optimizes tool-using agents by contrasting positive/negative
examples — but it operates only on the `Avatar` actor program, which upstream itself treats as
vestigial.

**Decision rationale (verified against the dspy 3.x checkout, 2026-06):**

- **`Avatar` is not in the public API.** It is exported from neither `dspy/__init__.py` nor
  `dspy/predict/__init__.py` — reachable only by deep import (`dspy.predict.avatar`). The optimizer is
  exported while the only program it can optimize is not: a vestigial surface.
- **No documentation.** `docs/docs/api/optimizers/` has a page for every other optimizer
  (BetterTogether, SIMBA, InferRules, …) but none for `AvatarOptimizer`.
- **No feature work.** The `avatar/` git history is purely mechanical maintenance (import style,
  deprecated-arg cleanup); the tool-agent space upstream has moved to ReAct (and the experimental
  `ReActV2`, G-19), both of which dspy4s ports.

Porting would mean bringing over a legacy actor design (`Avatar` + `ActionOutput` types) solely to
support an optimizer for it. dspy4s covers the same space with `ReAct`/`CodeAct` + the ported
optimizer surface.

**Revisit trigger:** if upstream re-exports/documents `Avatar` or ships an `AvatarOptimizer` that
operates on ReAct-style agents, reopen.

### Python reference

`dspy/teleprompt/avatar_optimizer.py` (~220 lines) + `dspy/predict/avatar/` (the `Avatar` tool-using
actor module with its own `ActionOutput` types). The optimizer evaluates the actor on a trainset, splits
results into positive/negative by score, and uses a `Comparator` signature to LM-generate instruction
feedback (including tool-usage modifications).

### dspy4s current state

Neither `Avatar` nor `AvatarOptimizer` is ported, by design (see decision rationale above).

---

## G-15 — `BetterTogether` meta-optimizer not ported

**Status:** Open

**Summary.** `dspy.BetterTogether` alternates prompt optimization and weight optimization (fine-tuning)
in a configurable sequence (e.g. "p -> w -> p").

### Python reference

`dspy/teleprompt/bettertogether.py` (~630 lines). Composes `BootstrapFewShotWithRandomSearch` (prompt
arm) and `BootstrapFinetune` (weight arm).

### dspy4s current state

Not ported. The prompt arm exists (`BootstrapFewShotWithRandomSearch`); the weight arm is gated on the
fine-tuning track (G-16).

### Why it matters

Exported optimizer surface; the headline "prompts + weights beat either alone" recipe.

### Proposed direction

Blocked on G-16 (fine-tuning). Once `BootstrapFinetune` exists, the composition itself is small. Tier 3.

---

## G-16 — Fine-tuning / RL track (`BootstrapFinetune`, `GRPO`, provider training APIs) not ported

**Status:** Open

**Summary.** The whole weight-optimization track is unported: `BootstrapFinetune` (SFT on bootstrapped
traces), `GRPO` (RL policy optimization), `bootstrap_trace` data collection, and the provider-side
fine-tuning/reinforcement APIs they call.

### Python reference

`dspy/teleprompt/bootstrap_finetune.py` (~320 lines), `dspy/teleprompt/grpo.py` (~640 lines),
`dspy/teleprompt/bootstrap_trace.py`; provider training surfaces in `dspy/clients/` (OpenAI/Databricks
fine-tune jobs, local training stack via `LocalProvider`).

### dspy4s current state

Nothing on this track. dspy4s optimizers are prompt-space only.

### Why it matters

It's the other half of program optimization (weights, not prompts), and gates G-15.

### Proposed direction

A major track, not a single gap: needs trace collection (partially exists via GEPA's failure-aware
traces), a data-formatting layer, provider fine-tune job APIs, and job lifecycle management. Scope a
spike before committing; OpenAI fine-tune jobs first if pursued. Tier 3.

---

## G-17 — `BootstrapFewShotWithOptuna` not ported

**Status:** Open

**Summary.** The Optuna-backed demo-subset search variant of random search is unported.

### Python reference

`dspy/teleprompt/teleprompt_optuna.py` (~90 lines) — `BootstrapFewShotWithRandomSearch` with Optuna TPE
suggesting which bootstrapped-demo candidate each predictor uses.

### dspy4s current state

Not ported. `BootstrapFewShotWithRandomSearch` covers the same space with random search.

### Why it matters

Completeness only; the search-strategy delta is the same one already accepted for MIPROv2
(random search instead of Optuna TPE, documented).

### Proposed direction

Either port with seeded random search over per-predictor demo choices (small), or fold into
`BootstrapFewShotWithRandomSearch` as a config knob, or mark `Won't fix (by design)` citing the MIPROv2
precedent. Tier 3.

---

## G-18 — `propose` remainder: `DescribeProgram` + iterative dataset-summary refinement

**Status:** Open

**Summary.** `GroundedProposer` shipped without two upstream grounding sources: program-source
description (`DescribeProgram` — LM-summarize the program's code) and the iterative refinement loop for
the dataset summary.

### Python reference

`dspy/propose/grounded_proposer.py` + `dspy/propose/dataset_summary_generator.py`.

### dspy4s current state

`GroundedProposer` v1 grounds in a dataset summary + demos. Program-source grounding is impossible
as-is (no source introspection in Scala; the same delta documented for Refine's `OfferFeedback`); a
structural program description (from `OptimizableStructure.inspectNamed` + layouts) is the feasible analogue.
Summary refinement is just unimplemented.

### Why it matters

Proposal quality for COPRO/MIPROv2 on complex programs.

### Proposed direction

Add a layout-derived program description (signatures, field descriptions, instructions per named
predictor) as the `DescribeProgram` analogue; add the summary-refinement rounds. Tier 3.

---

## G-19 — `ReActV2` (experimental native-tool-calling ReAct) not ported

**Status:** Open

**Update (2026-10-11).** ReActV2 is still `@experimental` at upstream `f1260ff14`. New since the first version:
the reserved outputs `history` and `termination_reason`, an error when the forced submit gives no outputs, and an
async path. The RLM prompt now recommends it. The dspy4s prerequisite is G-28 (tool call ids and the tool role).

**Summary.** Upstream added `dspy.ReActV2` (marked `@experimental`): a ReAct variant built on native
function calling (`Tool`/`ToolCalls` adapter types) instead of the text protocol.

### Python reference

`dspy/predict/react_v2.py` (~250 lines).

### dspy4s current state

Not ported. dspy4s `ReAct` is the text-protocol port, per the G-7b decision (native function-calling is
adapter-level; ReAct deliberately stays text — matching upstream `ReAct`). The adapter-level native
tool-calling plumbing (G-7b: `ToolSchemaBridge`, `tool_calls` output, `ToolChoice`) already exists and
is what ReActV2 builds on.

### Why it matters

Likely the future default agent loop upstream; the G-7b plumbing makes it a moderate port, not a
foundational one.

### Proposed direction

Wait for it to stabilize upstream (it is explicitly experimental), then port over the G-7b seams. Do
NOT rewire the existing `ReAct` (per the standing G-7b decision). Tier 2 once stable.

---

## G-20 — Sandboxed interpreter (Deno + Pyodide) and `RLM` not ported

**Status:** Resolved — both halves done. **(1)** the sandboxed interpreter (below); **(2)** `RLM`
(`programs/RLM.scala`): `RLM[I, O]` with the verbatim-ported action-instructions template, REPL
prompt types (`ReplVariable` metadata with head+tail previews, `ReplEntry`/history rendering with
upstream's `=== Step N ===` / `Output (N chars):` truncation), `llm_query`/`llm_query_batched` as
`SandboxTool`s with a shared call counter capped at `maxLlmCalls`, user tools (reserved-name validated,
documented in the prompt), fence stripping with non-Python-tag rejection, SUBMIT payload validation
(missing fields / type errors become observations and the loop continues), and the max-iterations
extract fallback. Deltas (documented): `llm_query_batched` is sequential (upstream thread-pools); output
typing is the signature's Schema decode (not per-field pydantic); `SandboxSerializable` and verbose/async
are omitted. Live-validated: a scripted action LM drove real Python through the default Deno interpreter —
injected variable + REPL state + in-sandbox `llm_query` combined in the final SUBMIT. Note upstream marks
RLM `@experimental`; revisit when it stabilizes.

**Resolution (part 1, commit `a793e1b`+).** `DenoPyodideInterpreter` (core/runtime) ports upstream's
`PythonInterpreter`: Python runs in a Pyodide (WASM) sandbox inside a Deno subprocess, with upstream's
`runner.js` vendored VERBATIM as a resource and dspy4s implementing the JSON-RPC 2.0 stdio client —
verified live against Deno 2.7.4 (protocol detail: `tool_call` ids are strings, echoed verbatim).
Capabilities: sandboxing (allowlist-only fs/net/env via Deno flags), **stateful REPL** across executes,
**variable injection** (`execute(code, variables)`), **host tools** (`SandboxTool` in contracts — sandboxed
code calls host functions by name mid-execution, the seam RLM's `llm_query` needs), **`SUBMIT(...)`** →
`CodeResult.finalOutput`, file mounting + write-back sync. Wired into the programs:
`ProgramOfThought` now prefers a SUBMIT `finalOutput` over printed stdout (closing its documented delta),
and `CodeAct.sandboxTools` bridges `ToolFunction`s into the sandbox (closing the tools-inside-code delta).
Delta: variables inject uniformly as `json.loads(...)` rather than upstream's literal/file split.

### Python reference

`dspy/primitives/python_interpreter.py` + `runner.js` (Deno/Pyodide), `dspy/predict/rlm.py` (~740 lines).

### dspy4s current state

Interpreter done (see above). `RLM` absent — but everything it needs from the interpreter now exists
(stateful REPL, variable injection, host tool callbacks, SUBMIT).

### Why it matters

The RLM program family (long contexts explored programmatically with recursive sub-LM calls).

### Proposed direction

Port `RLM` on the new interpreter: action/extract signatures over `SignatureLayout`, the iterate loop
(matching the CodeAct pattern), `llm_query`/`llm_query_batched` as `SandboxTool`s over the ambient LM,
REPL history rendering, extract-fallback. Note upstream marks RLM `@experimental` (like ReActV2, G-19) —
consider letting it stabilize first. Tier 3.

---

## G-21 — `datasets` module not ported

**Status:** Open

**Summary.** No dataset loaders: upstream ships `DataLoader` (HF/CSV/JSON → `Example`s) plus benchmark
wrappers (`HotPotQA`, `GSM8K`, `MATH`, `Colors`, `AlfWorld`).

### Python reference

`dspy/datasets/` (dataloader, dataset, gsm8k, hotpotqa, math, colors, alfworld).

### dspy4s current state

Nothing; tests/examples build `Example`s by hand.

### Why it matters

Benchmarking parity with upstream recipes (optimizer papers all report on these sets).

### Proposed direction

Port `DataLoader`'s CSV/JSON→`Example` core (no HF hub dependency; load from local files), then thin
benchmark wrappers that parse the standard distribution files. Tier 3.

---

## G-22 — Single LM provider (OpenAI); no Anthropic / Ollama / router

**Status:** Open — **(1) the OpenAI-compatible route is done**; (2) the Anthropic provider remains.

**Update (2026-10-11).** Upstream now has a native Anthropic adapter in
`dspy/_vendor/lm15/providers/anthropic.py`. Use it as the reference in place of the LiteLLM route.

**Resolution (part 1).** The `baseUrl` route for OpenAI-compatible servers is now first-class and
documented: `OpenAiLanguageModel.fromEnv` takes a `baseUrl` (env-keyed compatible servers — Azure,
OpenRouter); `OpenAiLanguageModel.local` / `OpenAiEmbedder.local` are the no-environment, no-key
constructors for local servers that don't check credentials (Ollama `:11434/v1`, vLLM `:8000/v1`,
LM Studio `:1234/v1` — a placeholder bearer token is still sent, since the wire format requires the
header). The `learn/programming/LanguageModels` example shows the Ollama route.

### Python reference

`dspy/clients/lm.py` (LiteLLM routing).

### dspy4s current state

OpenAI-compatible HTTP, with the compatible-server route documented and constructor-supported (part 1
above). No Anthropic Messages-API provider; no provider router.

### Why it matters

Anthropic models are unreachable except through OpenAI-compatible proxies.

### Proposed direction

Add an `AnthropicLanguageModel` on the existing `HttpTransport`/`LanguageModel` seams (Messages API,
tool-use mapping). A LiteLLM-style universal router is a non-goal. Tier 2.

---

## Entries from the 3.4.0 analysis (2026-10-11)

G-23 to G-50 come from the comparison with dspy 3.4.0 plus 25 commits (`f1260ff14`). The upstream change list is
[UPGRADE-3.2.1-to-3.4.0.md](../../UPGRADE-3.2.1-to-3.4.0.md); the `L`, `A`, `P`, `I`, and `O` ids below refer to it.

These entries use a short form. **Verification** tells how sure the entry is:

- **C**: a second read of the dspy4s code confirmed it.
- **R**: one review pass reported it after a read of the two sides.
- **I**: the review pass inferred it from one side.

No test reproduced any of these items. Write a failing test before each fix.

---

## G-23 — All provider failures are one `RuntimeError`, so retry cannot classify them

**Status:** Resolved (2026-10-11, batch 3) · **Verification:** C · **Upgrade item:** L1

**Upstream.** Errors carry `status`, `provider_code`, `request_id`, and `retry_after`
(`dspy/utils/exceptions.py:9-45,190-278`), mapped by one table (`dspy/clients/errors.py:34-104`). Retries use
transient classes only and obey the `Retry-After` hint (`dspy/clients/execution.py:397-399,570-571`).

**dspy4s.**
- `RuntimeError(component, message)` is the only provider failure (`modules/core/.../contracts/Errors.scala:19-20`,
  `modules/lm/.../providers/OpenAiClient.scala:111-120`).
- `RetryPolicies.maxRetriesOnCodes` reads `error.code`, which is always `"runtime_error"`
  (`modules/lm/.../runtime/ManagedLanguageModel.scala:33-34`).
- The default predicate `_ => true` retries authentication and context-window errors (`:28,41`).
- The component string separates `openai_auth`, `openai_rate_limit`, and `openai_server`
  (`OpenAiClient.scala:111-120`), but no retry policy reads the component.
- Timeouts and other HTTP 4xx share the component `"openai_http"` (`modules/lm/.../providers/JdkHttpTransport.scala:78-84`).
- The `Retry-After` header is dropped (`OpenAiClient.scala:36`).

**Resolution.** The list above describes the code before the correction.

- **Error family.** `LmError` is a sealed branch of `DspyError` (`modules/core/.../contracts/Errors.scala`). Each
  error has `details: LmErrorDetails` (model, provider, provider code, status, request id, retry hint) and the
  upstream `code`. The classes are `LmTransportError`, `LmNotConfiguredError`, `LmUnexpectedError`, and below
  `LmProviderError`: `LmAuthError`, `LmBillingError`, `LmRateLimitError`, `LmInvalidRequestError`,
  `ContextWindowExceededError`, `LmUnsupportedModelError`, `LmTimeoutError`, `LmServerError`, and
  `LmProviderResponseError`.
- **One mapper.** `OpenAiErrors.fromResponse` (`modules/lm/.../providers/OpenAiErrors.scala`) serves the chat call,
  the streamed call, and the embedder. The class comes from the code and the type of the error envelope first, and
  from the HTTP status second (upstream `normalize_error` and `map_http_error`). `insufficient_quota` on HTTP 429 is
  a billing error, so no retry follows.
- **Retry hint.** The mapper reads `Retry-After` (seconds or an HTTP date), then `retry-after-ms` and
  `x-ms-retry-after-ms`. It reads the request id from six header names.
- **Transport.** `JdkHttpTransport` gives `LmTimeoutError` for a timeout, `LmTransportError` for an IO failure, and
  `LmUnexpectedError` for each other failure. The client adds the model and the provider.
- **Retry.** The default predicate of `RetryPolicies.maxRetries` and `RetryPolicies.exponentialBackoff` is
  `LmError.isRetryable`: a rate limit, a timeout, a server failure, and a transport failure. The delay is the retry
  hint when the error carries one (upstream `_delay`).
- **Missing key.** `fromEnv` gives `LmNotConfiguredError`.

Tests: `ErrorsSuite`, `OpenAiErrorMappingSuite`, `OpenAiErrorEdgeCasesSuite`, `JdkHttpTransportErrorSuite`,
`RetryClassificationSuite`.

Changes in behavior to know:

- A provider failure is no longer `RuntimeError("openai_...", …)`. Code that matched on the component must match
  on the class.
- `ContextWindowExceededError` is now `(message, details)`. The model is in `details.model`.
- A retry policy without an explicit predicate no longer retries a `RuntimeError`, an authentication failure, or a
  context-window failure. A custom `LanguageModel` must return an `LmError` of a transient class to get a retry, or
  the caller must pass `retryOn`.
- `DynamicJson.decode` returns a `ParseError` for JSON that is nested too deep for the stack. Before, it threw
  `StackOverflowError`.

Deliberate differences from upstream:

- `ContextWindowExceededError` and `LmUnsupportedModelError` are siblings of `LmInvalidRequestError`, not
  subclasses, because a case class cannot extend a case class.
- `LmProviderResponseError` takes the place of a direct `LMProviderError` instance.
- Four upstream classes have no source in dspy4s and are absent: `LMLockTimeoutError`, `LMCollectionLimitError`,
  `LMUnsupportedFeatureError`, and `LMStreamAssemblyError`. The last one comes with the stream guard (G-25).
- Three rules of the upstream OpenAI mapper are not ported: the text rule for a missing model on HTTP 404, the
  billing codes of Z.AI and Moonshot, and the `(code)` suffix of the message.

Open:

- No policy limits the retry hint. A large `Retry-After` makes the caller wait for that time, as upstream.
- A streamed call that fails after the first chunk still gives `RuntimeError("streaming_lm", …)` (G-25).
- The adapter fallback (G-29) can now use the taxonomy.
- `JdkHttpTransport` joins duplicate headers with a comma, so a duplicate `Retry-After` gives no hint.

---

## G-24 — LM cache: the key ignores model default options; the disk format is Java serialization

**Status:** Resolved (2026-10-14, batch 14). The cache key was resolved on 2026-10-11 (batch 1). · **Verification:** C (key), R (disk) · **Upgrade item:** L9

**Upstream.** The key uses the merged request options (`dspy/clients/execution.py:58-70,145,166`). The disk
record is versioned plain data (`dspy/clients/call_result.py:11,126-138`).

**dspy4s.**
- `ManagedLanguageModel.call` hashes the `LmRequest` only (`modules/lm/.../runtime/ManagedLanguageModel.scala:148,162`,
  `modules/lm/.../runtime/RequestHash.scala:13-20`). The provider merges `defaultOptions` later
  (`modules/lm/.../providers/OpenAiLanguageModel.scala:55`). Two models with one id and different `temperature` or
  `max_tokens` share entries when they share a cache. A disk cache also returns old entries after a change of the
  defaults.
- `DiskLmCache` reads with an unfiltered `ObjectInputStream` (`modules/lm/.../runtime/DiskLmCache.scala:125-130`).
  Each `put` lists and sorts the directory (`:89,139-144`), with a limit of 200,000 entries.

**Resolution (key).** `LanguageModel.defaultOptions` exposes the defaults, and each wrapper forwards it.
`ProviderRequestNormalizer.effectiveOptions` holds the merge for the wire payload and for the cache key, so the
two cannot differ. Tests: `LmCacheKeySuite`. A disk cache entry of a model that has defaults misses one time,
because its key changed.

**Resolution (disk, batch 14).** `DiskLmCache` writes `<key>.json`, a JSON object with `_dspy4s_format`
(`dspy4s-lm-result-v1`), `outputs`, `usage`, and `model_name`. A file that is not JSON or has another format is a
miss and is removed. A legacy `<key>.bin` entry is read with an `ObjectInputFilter` that accepts only the classes of
the old record (one-dimension arrays of its element classes, `String`, `Long`, `Number`, `HashMap`, and a depth of 8
at most), then moved to the JSON record. Tests: `DiskCacheRecordSuite`. Open: the directory listing on each `put`
stays; a deeply nested `args` value can still overflow the stack in the encoder, as before.

---

## G-25 — Streamed LM calls skip the managed path

**Status:** Resolved (2026-10-14, batch 11). The capability flags and the typed errors were resolved on 2026-10-11 (batch 1). · **Verification:** C (flags, typed error), R (others) · **Upgrade items:** L2, L3

**Upstream.** One call path applies cache, retry before the first token, usage, and history to streams
(`dspy/clients/execution.py:574-646`). A stream without an end event is an error, and the typed error survives
(`dspy/clients/engines/stream_guard.py:18-51`).

**dspy4s.**
- `StreamingLanguageModelWrapper` overrides only `id` and `mode`
  (`modules/streaming/.../StreamingLanguageModelWrapper.scala:55-56`). The capability flags fall back to `false`, so
  `response_format` and native tools go off under `streamify`. `ManagedLanguageModel` forwards the flags correctly.
- Errors travel as a chunk with `raw: Option[Any]` (`modules/lm/.../contracts/LmStreaming.scala:15-21`). The wrapper
  rebuilds each one as `RuntimeError("streaming_lm", …)` (`StreamingLanguageModelWrapper.scala:78`). A
  `ContextWindowExceededError` is lost, so `TrajectoryTruncation` cannot start.
- Bad chunks are dropped (`modules/lm/.../providers/OpenAiClient.scala:85`). End of input without `[DONE]` is
  accepted (`:86-87`). A mid-stream `{"error": …}` decodes to an empty chunk
  (`modules/lm/.../providers/OpenAiStreamChunk.scala:19-22`).
- `ManagedLanguageModel.stream` applies no cache, no retry, no history, and no usage
  (`ManagedLanguageModel.scala:135-145`).

**Resolution (flags, typed errors).** `StreamingLanguageModelWrapper` forwards the three flags and
`defaultOptions`. `LmChunk` has an `error: Option[DspyError]` field, and `LmChunk.failure` builds the terminal
chunk. The wrapper returns the typed error. Tests: `StreamingWrapperContractSuite`.

**Resolution (batch 11, with the decisions of the owner of 2026-10-14).**

- The OpenAI client ends a stream at `[DONE]`. A chunk with a finish reason and a chunk with usage are end
  events. When the lines stop with no end event, the last chunk is an `LmStreamAssemblyError` (code
  `stream_assembly`, not transient). A `data:` line that is not JSON gives the same error. A JSON value that is not
  an object is skipped, and so is an object that the chunk codec cannot read.
- A mid-stream `{"error": ...}` event gives the typed error of its code (`code`, else `type`), with the upstream
  `_stream_error_code_map`. The details have the model, the provider, and the code.
- `ManagedLanguageModel.stream` applies the rules of `call`. A cache hit gives one `LmChunk.cached` chunk, and the
  stream wrapper gives no token of it to a listener. The cache stores a complete stream only. A stream with no
  chunk that has a finish reason ends with an `LmStreamAssemblyError`. A failure before the first chunk with text
  or a tool call gets the retry policy, and the caller gets no chunk of the failed attempt. Each streamed call has
  one history entry, and the usage of its chunks is recorded, also after a failure.
- Tests: `OpenAiStreamGuardSuite`, `ManagedStreamSuite`, `StreamingCacheSuite`.

**Open points (batch 11).** A managed stream that the caller closes early gets no history, usage, or cache entry.
No check rejects an event after the end event. The usage of a retried attempt is not recorded. An outer
`ManagedLanguageModel` reads a cached chunk of an inner one as a new response. The UPGRADE file lists the details.

---

## G-26 — LM request surface: strict schema, capabilities, Responses endpoint, reasoning models, timeouts

**Status:** In progress. The strict schema (2026-10-14, batch 12), the timeouts (batch 17), the Responses shapes
(batch 20), and `close` (batch 22) are resolved; the capabilities (L5) and the reasoning-model parameters are open. · **Verification:** C (strict flag), R (others) · **Upgrade items:** L4 to L8

**Upstream.** `strict: true` with a schema shaped for strict mode (`dspy/clients/legacy_requests.py:13-48`,
`execution.py:176-183`). Capabilities are data, declared for each server (`dspy/clients/capabilities.py:22-27`,
`dspy/lm15.py:47-185`). Responses request and output shapes (`legacy_requests.py:104-174`,
`dspy/clients/legacy_outputs.py:24-68`). Reasoning-model parameters (`dspy/clients/lm.py:37-44,301-317`).
`reasoning_content` in the result (`dspy/clients/base_lm.py:352-353`). Bounded connect and read times
(`dspy/clients/engines/lm15_engine.py:26-59`). `close` and `aclose` (`lm.py:396-442`).

**dspy4s.**
- `strict` is `false` (`modules/adapters/.../JSONAdapterPrompt.scala:79`).
- Capability flags are fixed `true` values (`modules/lm/.../providers/OpenAiLanguageModel.scala:48-49`).
- `LmMode` selects no endpoint and no body (`modules/lm/.../runtime/ProviderLanguageModel.scala:48-53`). Responses
  `function_call` and `reasoning` items are not parsed.
- No mapping to `max_completion_tokens` and no temperature rule for OpenAI reasoning models.
- Nothing reads `supportsReasoning`, and the `message` is dropped from the metadata (`ProviderLanguageModel.scala:110-112`).
- One 60 s value covers connect and request, and a stream has no idle limit
  (`modules/lm/.../providers/HttpTransport.scala:28`, `JdkHttpTransport.scala:17,64`).
- No custom headers and no `User-Agent` (`OpenAiClient.scala:19-21`). The transport has no `close`.
- Empty content becomes a `ParseError` (`ProviderLanguageModel.scala:86,104-109`). Upstream returns the output and
  warns about truncation (`execution.py:411-414`).

**Resolution (strict schema, batch 12).** The JSON adapter sends `strict: true` with the name
`DSPyProgramOutputs` and a schema in strict shape (`StrictJsonSchema.shape`, upstream `_strict_json_schema`). A string
signature gets a schema from its field types (`StrictJsonSchema.fromFields`). JSON mode replaces the schema for an
open map, a text `tool_calls` field, or a schema that cannot be built. A `oneOf` goes out as `anyOf`. Tests:
`StrictResponseFormatSuite`, `JSONAdapterResponseFormatSuite`.

**Resolution (timeouts, batch 17).** `HttpTimeouts(connect = 10 s, read = 600 s)`, the lm15 defaults. The read limit
bounds the wait for the headers and for each line of a stream. A stream that stops ends with an `LmTimeoutError`, and a
connection that fails during a stream ends it with an `LmTransportError` (`HttpStreamResponse.failure`). Tests:
`HttpTimeoutsSuite`. Open: the body of a plain call has no limit after the headers.

**Resolution (Responses, batch 20).** `OpenAiLanguageModel(mode = LmMode.Responses)` calls `/responses` with the
body of upstream `chat_to_responses` and reads one output with `responses_outputs` (text, tool calls with `call_id`,
`reasoning_content`, `citations`). Tests: `ResponsesWireSuite`, `ResponsesModeSuite`. Open: no parser for the
Responses stream events (a stream is one final chunk from a plain call).

**Direction (open).** Add a `capabilities` value to the OpenAI client for compatible servers (L5, needs a decision of
the owner). Reasoning-model parameters and custom headers remain. `close` is resolved in batch 22
(`CloseLifecycleSuite`).

---

## G-27 — LM history holds no prompt and no completion; no cost estimate

**Status:** In progress. The history content and `inspectHistory` are resolved (2026-10-14, batch 15); the cost
estimate is open. · **Verification:** R · **Upgrade items:** L10, I11

**Upstream.** A history entry stores messages, outputs, usage, cost, and the response id
(`dspy/clients/execution.py:738-754`). `inspect_history` prints messages, responses, and tool calls
(`dspy/utils/inspect_history.py:40-98`). Cost is advisory: an unknown price is not zero, and a price failure never
fails a call (`dspy/clients/costs.py:12-110`).

**dspy4s.** An LM entry stores `model`, `cache_hit`, an output count, and `mode`
(`modules/lm/.../runtime/ManagedLanguageModel.scala:178-190`). Module entries share the list, so `inspectHistory(1)`
can return a module entry (`modules/core/.../runtime/RuntimeEnvironment.scala:209-211`). The "Recently resolved"
note for `inspect_history` covers the function, not this content. There is no cost field.

**Resolution (history content, batch 15).** `LmHistoryRecord` writes the LM entry with the upstream keys: `model`,
`model_type`, `cache_hit`, `messages`, `kwargs`, `outputs`, `usage`, `response_model`, and `uuid` (a failure has
`error_code` and `error_message` in place of the outputs). `ManagedLanguageModel` uses it for a call, a cache hit, and
a streamed call. `RuntimeEnvironment.inspectHistory` shows only the `lm:` entries, in the layout of upstream
`pretty_print_history`, with the tool calls. Tests: `LmHistoryEntrySuite`, `InspectHistorySuite`.

**Direction (cost, open).** Upstream reads the prices from a model catalog. The port has no catalog, so a cost
estimate needs a decision of the owner: a catalog, prices that the user declares, or no cost.

---

## G-28 — No tool call ids, no tool message role, no `dspy.History`

**Status:** Resolved (2026-10-11, batch 5) · **Verification:** C · **Upgrade items:** A3, A4

**Upstream.** `ToolCalls` keeps the provider id (`dspy/adapters/types/tool.py:284`, `dspy/adapters/base.py:626-644`).
`ToolCallResults` exists (`tool.py:440`). Adapters send `assistant.tool_calls` and `role: tool` messages again
(`base.py:553-585`). `dspy.History` replays earlier turns (`dspy/adapters/types/history.py`, `base.py:513-603`);
since f207d35cf an earlier user turn keeps the output reminder, and an output-only turn gets no user message.

**dspy4s.** The id is dropped (`modules/lm/.../runtime/ProviderLanguageModel.scala:145`,
`modules/lm/.../runtime/ToolCallAssembler.scala:46`). `ToolCall` has no id
(`modules/core/.../contracts/ToolCall.scala:33`). `MessageRole` has three roles
(`modules/lm/.../contracts/LmRequest.scala:10-13`). There is no `History` type; the `ConversationHistory` example is
blocked.

**Resolution.** The text above describes the code before the correction.

- **Types.** `ToolCall` has an optional `id` and a record form (`toRecord`, `fromRecord`, `fromDynamic`).
  `ToolCallResult` and `History` are new (`modules/core/.../contracts/ToolCall.scala`, `History.scala`).
  `MessageRole.Tool` is new, and `Message` has `toolCalls`, `toolCallId`, and `name`.
- **Wire.** The response parser and the stream assembler keep the id (`call_id` before `id`, upstream
  `_provider_tool_call_to_tool_call_dict`). The request normalizer writes `tool_calls`, `tool_call_id`, and `name`
  in the provider form. The cache key covers the tool fields only when a message has them, so the keys of current
  requests do not change. The disk cache keeps the id and still reads the entries of the old format.
- **Signatures.** A field of type `History` gets `TypeRef.history` from its type, for a derived signature, a spec
  trait, the builder, and the string DSL. No mark call is necessary.
- **Adapters.** `ChatAdapter`, `JSONAdapter`, and `XMLAdapter` share one replay
  (`modules/adapters/.../internal/ConversationHistory.scala`), with the upstream rules of `format` and
  `format_conversation_history`: the order system, demos, history, current request; the output reminder in a
  replayed user turn (f207d35cf); no user turn for a message with output fields only; an assistant message with
  tool calls and one tool message for each result when the ids agree (2ec31b3aa); text in each other case.
- **Example.** `ConversationHistory.scala` is translated.

Tests: `ToolCallHistorySuite`, `ToolCallWireSuite`, `ToolCallIdSuite`, `HistorySignatureSuite`,
`HistoryFieldSurfacesSuite`, `ConversationHistorySuite`, `ConversationHistoryRulesSuite`, `PredictHistorySuite`,
`ProgramHistorySuite`.

Changes in behavior to know:

- `ToolCall` has a third field. A pattern `ToolCall(name, args)` must name the id too.
- The typed decode gives `None` for an optional field that a record lacks, at each level. Before, it failed. A
  model that writes a tool call as text gives no id, so this rule is necessary.
- The JSON schema of an output type does not show the id of a tool call (upstream removes it too).

Deliberate differences from upstream:

- A history message holds the results of its tool calls under the key `tool_call_results` of the message
  (`History.ToolCallResultsKey`). Upstream holds them in the `ToolCalls` value. dspy4s has no wrapper type for tool
  calls: the field is a `Vector[ToolCall]`.
- Tool messages are replayed only when the adapter flag is on and the model supports function calling. Upstream
  checks the flag only, so a model without function calling gets messages that it rejects.
- A message with no output field gives no assistant turn. Upstream writes `None` values there.
- The JSON and XML system messages of dspy4s list no input field, so they get one sentence that names the history
  field.

Open:

- `TwoStepAdapter` does not replay a history; it shows the value as a field. Upstream does the same.
- A real `function_call` item of the Responses API gives no tool call, and a Responses request uses the chat
  message form (G-26, L7).
- `DynamicValues.renderText` writes a nested value with strings that have no quotes, so the history field of a
  demo is not valid JSON.
- An `Option[History]` field is not replayed.
- `markToolCalls` is still necessary for a `Vector[ToolCall]` field, although the derivation can now find the type
  by its name.
- `PredictEngine` writes the raw calls under the fixed key `tool_calls`. A signature field with a different name
  gives two keys in the raw prediction.
- No test covers a `History` input of `ReAct`, `RLM`, `Refine`, or `BestOfN`.
- `ToolCallResults.from_tool_calls_and_values` has the dspy4s form `ToolCallResult.forCalls`. The `execute` method
  of an upstream tool call is not ported.

---

## G-29 — Adapter output handling differs from upstream

**Status:** Resolved (2026-10-13, batch 10) · **Verification:** C (optional outputs), R (others) · **Upgrade items:**
A1, A2

**Upstream.** A missing optional output takes its default or `None` (`dspy/adapters/utils.py:40-55`). ChatAdapter
tries JSONAdapter after an `AdapterParseError` only (`dspy/adapters/chat_adapter.py:86-94`). Incomplete demos get a
prefix and the text "Not supplied for this particular example." (`dspy/adapters/base.py:445-476`). When native
function calling is off, `tool_calls` stays a text field (`base.py:91-119`).

**dspy4s.**
- Each adapter rejects a missing output before the typed layer can exempt an `Option` field
  (`modules/adapters/.../ChatAdapterParser.scala:34`, `JSONAdapterParser.scala:60`, `XMLAdapter.scala:63`). `FieldSpec`
  has no optional flag.
- There is no fallback. `PredictEngine` does format, call, and parse one time
  (`modules/programs/.../runtime/PredictEngine.scala:68-94`).
- Demos omit the missing fields (`modules/adapters/.../ChatAdapterPrompt.scala:24-34`).
- `tool_calls` is always removed from the prompt (`ChatAdapterPrompt.scala:17`) and comes from provider calls only.
  The default is `useNativeFunctionCalling = false`, so the field is always empty by default.
- A coercion failure is a `ValidationError` with no field name and no raw text
  (`modules/adapters/.../internal/AdapterTextSupport.scala:92-102`). Upstream raises a parse error with both.
- Duplicate headers concatenate (`ChatAdapterParser.scala:51-57`); upstream uses the first.

**Direction.** Decide D5 first. Make coercion failures parse errors, then add the fallback with the parse error as
the key.

**Resolution (batch 10, with the decisions of the owner of 2026-10-13).**

- `FieldSpec.optional` marks an optional field (decision D5). An `Option[T]` field of a typed signature sets it, and
  the string syntax `Optional[T]` and `T | None` sets it. A missing output takes the `defaultValue` of the field, else
  a null when the field is optional, else it is a parse error, in the Chat, JSON, and XML adapters. An explicit JSON
  null for an optional field is a null.
- `ChatAdapter` falls back to a `JSONAdapter` after a parse error of the parse step (`useJsonAdapterFallback`, on by
  default). An LM error or a configuration error gives no second call, and a call that streamed visible output gets
  no second call. The predictor runs the second call (`Adapter.fallback`).
- A value that does not fit the type of its field is a `ParseError` with the field name and the raw response.
- In the chat format, the first header of a field wins.
- An incomplete demo comes first, with the upstream prefix, and a missing output shows "Not supplied for this
  particular example." A demo with no input or no output is dropped. A field default no longer fills a demo; it
  still fills a missing input of the live call.
- With native function calling off, or with an LM that does not support it, `tool_calls` is a text output field in
  the Chat and JSON adapters.
- Tests: `AdapterOutputRulesSuite`, `AdapterFallbackSuite`, `StreamingFallbackSuite`, `OptionalFieldSuite`,
  `OptionalFieldSpecSuite`.

**Open points (batch 10).** The XML `tool_calls` field, a type with a comma such as `Union[int, None]`, and the JSON
string `"3"` for an `int` field are resolved in batch 18. A typed `Option[Int]` field has the prompt type `str`. A JSON fallback call
streams through the chat stream state. The extraction step of `TwoStepAdapter` and `Adapter.execute` get no
fallback. An incomplete demo hides its null inputs, and a
null output shows the "Not supplied" text, where upstream shows `None`. The UPGRADE file lists the details.

---

## G-30 — Adapter prompts are not byte-equal to upstream

**Status:** Open (needs decision D3) · **Verification:** C (ChatAdapter), R (JSON, XML)

**ChatAdapter differences.**

| Difference | dspy4s | Upstream |
|---|---|---|
| No `: ` after the type when there is no description | `ChatAdapterPrompt.scala:84-86` | `dspy/adapters/utils.py:276` |
| Blank lines between the blocks, not one newline | `ChatAdapterPrompt.scala:64-72` | `dspy/adapters/base.py:338-342` |
| Objective inline, not on a new line with an indent | `ChatAdapterPrompt.scala:72` | `dspy/adapters/chat_adapter.py:142-145` |
| Default instructions have no backticks | `ChatAdapterPrompt.scala:101-104` | `dspy/signatures/signature.py:35-38` |
| Reminder has no "Python" | `ChatAdapter.scala:78` | `chat_adapter.py:192` |
| "true or false", not "True or False" | `ChatAdapter.scala:88` | `utils.py:137` |
| Constraints inline, not on a new line | `ChatAdapterPrompt.scala:87-91` | `utils.py:277-279` |

**JSONAdapter and XMLAdapter.** They use field names only (`JSONAdapterPrompt.scala:24-35`, `XMLAdapter.scala:31-36`).
Upstream keeps the description, the structure, and the reminder (`dspy/adapters/json_adapter.py:95-127`,
`dspy/adapters/xml_adapter.py:38-93`).

**Why it matters.** Optimized instructions and demos move between the two libraries only if the prompt around them
is the same. The dspy4s tests use `contains`, so they do not detect a difference.

**Direction.** Decide D3. If parity is the goal, add golden prompt tests from upstream's exact format tests
(52efe7781, 6fb3f96cb).

---

## G-31 — Stream chunks can contain boundary whitespace

**Status:** Resolved (2026-10-14, batch 13) · **Verification:** R · **Upgrade item:** A5

**Upstream.** The listener removes leading whitespace and holds trailing whitespace
(`dspy/streaming/streaming_listener.py:309-321,376`).

**dspy4s.** `ChatStreamingState` skips the newline after a marker only if it is already in the buffer
(`modules/adapters/.../ChatStreamingState.scala:66`), then emits the prefix unchanged (`:85-88`). A newline in the
next delta is emitted. `XmlStreamingState.scala:57` does not trim.


**Resolution (batch 13).** The chat and XML stream states drop the leading whitespace of a field, hold a run of
whitespace until more text of the field arrives, and drop it at the end of the field. The chat parser now strips each
value at both ends, as upstream `"\n".join(v).strip()`. Thus the joined chunks of a field equal the parsed value for
each split of the response. Tests: `StreamWhitespaceSuite`. Open: with one output field and no marker, the stream and
the parser can differ for rare whitespace characters; a repeated header streams each section, but the parser keeps
the first.
---

## G-32 — Custom adapter types, JSON mode, and nested XML are absent

**Status:** Open · **Verification:** R · **Upgrade item:** A6

- **Custom types.** `Image`, `Audio`, `File`, `Document`, `Citations`, `Code`, and `Reasoning` have no port.
  `ContentPart` encodes `type` and `text` only (`modules/lm/.../runtime/ProviderLanguageModel.scala:62-71`), and no
  adapter makes parts. `Reasoning` alone is a small item (`dspy/adapters/types/reasoning.py:46-83`).
- **JSON mode.** Upstream can send `{"type": "json_object"}` (`dspy/adapters/json_adapter.py:58-63`). dspy4s sends
  `json_schema` or nothing.
- **Nested XML.** Upstream parses nested elements for list, dict, and model outputs
  (`dspy/adapters/xml_adapter.py:95-135,159-218`). dspy4s reads the text of the first match and parses it as JSON
  (`modules/adapters/.../XMLAdapter.scala:98`).
- **BAMLAdapter.** Not ported. Low value.

---

## G-33 — Sandbox protocol robustness

**Status:** Resolved (2026-10-11: the first two items in batch 1, the others in batch 4) · **Verification:** C · **Upgrade items:** I2, I3, I4

- **Unsolicited error.** The runner can send an error with a null id. `readLoop` accepts any error without an id
  check and returns (`modules/core/.../runtime/DenoPyodideInterpreter.scala:264-275`). The real response stays in the
  pipe, and each later call fails with "Response id mismatch". Upstream: `dspy/primitives/python_interpreter.py:612-628,828-831`
  (6334c2ba5).
- **A tool that throws.** `handleToolCall` has no catch (`DenoPyodideInterpreter.scala:299`). The exception leaves
  `execute`, and the runner waits for a reply. Upstream replies with a JSON-RPC error (`python_interpreter.py:526-529`).
- **Request ids and recursion.** Ids are a counter from 1 and there is no guard against a nested `execute`
  (`DenoPyodideInterpreter.scala:225-226,286-305`). Upstream: `python_interpreter.py:608-610,513-517` (80b118e52).
- **Mount collisions.** Mounts map by basename with no check (`DenoPyodideInterpreter.scala:190-193,311`). Upstream
  rejects them (`python_interpreter.py:276-283`, 072c72164).
- **Wrong component name.** `SubprocessPythonInterpreter.scala:84` uses the literal `"interpreter"`.

**Resolution (first two items).** `DenoPyodideProtocol.outOfBand` classifies each message. The client skips a
notification and an error without an id, stops on a protocol error code without an id, and stops on an error with
a different id. `handleToolCall` catches a tool exception and replies with an error. Tests: `SandboxProtocolSuite`
(a scripted sandbox process) and two live tests in `DenoPyodideInterpreterSuite`.

**Resolution (batch 4).**

- **Request ids.** Each request has a new random id of 32 hexadecimal characters (`DenoPyodideProtocol.newRequestId`,
  upstream `secrets.token_hex(16)`). Sandboxed code cannot guess the id of a response.
- **One call at a time.** A tool that calls `execute` on its interpreter gets a `Left`, and the outer call continues.
  A call from a different thread while a call is in progress gets a `Left` too.
- **Id mismatch.** A response with a different id is a protocol breakdown for `execute` and for the setup requests.
- **The end of a session.** A failure of the process or of the protocol ends the session and stops the process
  (upstream `_raise_terminal_error`). Each later call returns "The DenoPyodideInterpreter session has ended". `close`
  ends the session too. Before, the client started a new process and lost the REPL state without a message.
- **Error codes.** An error response with a code from -32099 to -32000 is a user-code error. Each other code ends the
  session.
- **Mount collisions.** Two mounts with one file name and different host files are an error before the process
  starts (`DenoPyodideLaunch.checkMounts`).
- **Component name.** The spawn failure of `SubprocessPythonInterpreter` uses `CodeInterpreterErrors.Interpreter`.

Tests: `SandboxSessionSuite`, `SandboxProtocolSuite`, `DenoPyodideInterpreterSuite`, `SubprocessPythonInterpreterSuite`.

Changes in behavior to know:

- An interpreter does not start again after `close` or after a failure. Create a new interpreter.
- An error response to a mount or to a registration ends the session.
- A file sync that fails is a `Left`. Before, the client ignored it.

Deliberate difference from upstream: upstream binds an interpreter to the thread of its first call until
`shutdown`. dspy4s permits calls from different threads when they do not overlap.

Open:

- A tool that throws `InterruptedException` still leaves `execute`.
- The read of a response has no deadline, as upstream. Code such as `while True: pass` blocks the call.
- The client does not read the standard error of the sandbox during a session. A sandbox that fills that pipe blocks.
- `mountOne` throws when the parent directory of a write path does not exist.
- The client sends the file sync after a user-code error too. Upstream sends it after a success only.
- `ProtocolErrorCodes` has five codes. Upstream has three.

---

## G-34 — The vendored sandbox runner is the 3.2.1 file

**Status:** Resolved (2026-10-11, batch 4) · **Verification:** C · **Upgrade items:** I1, I5

**Upstream.** `dspy/primitives/runner.js` pins Pyodide, freezes `JSON` and `console`, uses a private control
exception, and revokes read access to the Deno cache (`runner.js:3,6,23,118,145-146`). The launcher passes
`--no-config --no-lock --node-modules-dir=false`, checks the Deno version, and bounds the probe
(`dspy/primitives/python_interpreter.py:104-138,298-304`).

**dspy4s.** `modules/core/src/main/resources/dspy4s/core/runtime/runner.js` differs from the 3.2.1 file in three
comment lines only, and from the current file in 41 lines. `DenoPyodideLaunch.scala:20-29,49-51` has none of the
launch changes.

**Resolution.** The text above describes the code before the correction.

- **Runner.** `runner.js` is the upstream file of f1260ff14. Three comment lines keep the dspy4s wording; a `diff`
  with the upstream file shows only those lines. The file fixes Pyodide at 0.29.4, uses the private control exception
  `_DSPyFinalOutput`, reports an unhandled promise rejection as a notification, freezes `JSON` and `console`, and
  revokes the read permission for the Deno cache directory after it loaded Pyodide.
- **Launch.** `DenoPyodideLaunch.command` passes `--no-config`, `--no-lock`, and `--node-modules-dir=false`. After the
  runner path it always passes the list of environment variables, and then `--dspy-deno-dir=`. The process gets
  `DENO_NO_PACKAGE_JSON=1`.
- **Version check.** The default launch command needs Deno `>=2.0.0,<3.0.0`. The probes (`deno --version` and
  `deno info --json`) have a time limit of 10 seconds.
- **Write paths.** A write path cannot overlap the runner file or the Deno cache directory.
- **Canonical paths.** `canonical` resolves the symbolic links of the part of a path that exists, as
  `os.path.realpath` does. Before, a write path that did not exist in a directory behind a symbolic link failed at the
  mount.

Tests: `DenoPyodideLaunchSuite` and four live tests in `DenoPyodideInterpreterSuite`. Two of the live tests showed
real faults of the old runner: sandboxed code could read the Deno cache directory through `js.Deno`, and a user
exception with the name `FinalOutput` looked like a `SUBMIT` call.

Changes in behavior to know:

- The sandbox runs Pyodide 0.29.4. Before, Deno selected the newest version that it found.
- A Deno version outside 2.x is an error for the default launch command.
- A successful probe serves the JVM. A failed probe runs again on the next call; upstream caches the failure too.

Not ported: upstream first looks for the Deno binary of the `deno` Python package. dspy4s uses `deno` from PATH.

---

## G-35 — Interpreter lifecycle in the code programs

**Status:** In progress. The failure handling is resolved (2026-10-11, batch 1). The shared interpreter instance ended with the removal of `CodeAct` and `ProgramOfThought` (batch 3, G-39). The factory and the silent restart are resolved (batch 4). The runtime description (I7) is open. · **Verification:** C (failure handling, factory), R (others) · **Upgrade items:** P1, P2, I7

**Upstream.** An interpreter failure ends the run (`dspy/predict/rlm.py:775-778`, 89696e0cc). Each `forward` gets
its own interpreter from a factory, and `dspy.settings.interpreter_factory` sets the default
(`dspy/primitives/code_interpreter.py:178-204`; 623360078, 6fb74fcf4). An interpreter describes its runtime through
`execution_instructions` (`dspy/primitives/python_interpreter.py:346-350`).

**dspy4s.**
- RLM changes each interpreter `Left` into an `[Error]` observation and continues
  (`modules/programs/.../strategies/RLMExecution.scala:106-109`). `CodeAct.scala:99-100` does the same. A missing Deno
  binary causes the full iteration budget of LM calls. `ProgramOfThought.scala:276-278` is correct.
- After a process death, the interpreter starts a new process and loses the REPL state silently
  (`modules/core/.../runtime/DenoPyodideInterpreter.scala:140-152`).
- `CodeAct` and `ProgramOfThought` hold one interpreter instance in the program value (`CodeAct.scala:57`,
  `ProgramOfThought.scala:42`). The Deno client has no lock, so a parallel evaluation interleaves JSON-RPC lines.
- Only RLM has a factory (`RLM.scala:220-224`), and its type names `DenoPyodideInterpreter.OutputField`.
- `readLine` has no deadline (`DenoPyodideInterpreter.scala:358-365`). `while True: pass` blocks forever.
- The runtime description in the RLM prompt is fixed text (`RLMReplProtocol.scala:31`).

**Resolution (failure handling).** In RLM and CodeAct an interpreter `Left` is now the result of the program,
and the LM gets no more calls. A user-code error stays an observation. One effect to know: a timeout of
`SubprocessPythonInterpreter` is a `Left`, so a slow generated snippet now ends a CodeAct run.

**After the removal (batch 3).** `CodeAct` and `ProgramOfThought` are removed (G-39), so the points about them no
longer apply. `RLM` is the one code program, and it gets one interpreter for each call from its factory.

**Resolution (factory, batch 4).**

- `ReplInterpreterFactory` and `SandboxOutputField` are in `core.contracts`. The factory type of `RLM` no longer names
  `DenoPyodideInterpreter`.
- `RuntimeServices.interpreterFactory` is the factory of the runtime context (upstream
  `dspy.settings.interpreter_factory`). `RuntimeEnvironment.withSettings` sets it for a scope, which also covers the
  upstream factory at call time (430af8d87).
- `RLM.interpreterFactory` is `Option[...]` with the default `None`. `RLM.resolveInterpreterFactory` selects the
  factory for each forward: the factory of the program, then the factory of the context, then the Deno sandbox
  (upstream `resolve_interpreter_factory`).
- The silent restart after a process death is resolved in G-33: a session does not start again.

Tests: `RLMSuite`, `RuntimeEnvironmentSuite`.

Deliberate difference from upstream: the dspy4s factory gets the tools and the output fields as arguments. The
upstream factory has no arguments, and RLM changes the `tools` dictionary of the interpreter after the creation.

**Direction (open).** Let an interpreter describe its runtime (`execution_instructions`, I7) and put that text in the
RLM prompt. This changes the prompt, so it waits for decision D3. Add a deadline to the REPL read; upstream has none
for this interpreter.

---

## G-36 — No persistent local Python REPL

**Status:** Resolved (2026-10-14, batch 21) · **Verification:** R · **Upgrade item:** I6

**Upstream.** `LocalInterpreter` runs a persistent CPython worker with tools, `SUBMIT`, variables, a deadline, a
process-group kill, and leaked-thread detection (`dspy/primitives/local_interpreter.py:47-63,187-209,267-294`).

**dspy4s.** `SubprocessPythonInterpreter` is a stateless `python3 -c` (`modules/core/.../runtime/SubprocessPythonInterpreter.scala:44-50`).
Only the Deno class implements `ReplCodeInterpreter`, so RLM needs Deno.

**Resolution (batch 21).** `LocalPythonInterpreter` is a second `ReplCodeInterpreter` in `core/runtime`, with the
vendored upstream worker script as a resource and the JSON-line protocol of upstream. `LocalPythonInterpreter.factory`
gives RLM an interpreter with no Deno. Tests: `LocalPythonInterpreterSuite` (skips when `python3` 3.10+ is absent).

---

## G-37 — ReAct differs from upstream

**Status:** In progress. The truncation rule is resolved (2026-10-11, batch 1). The default limit is resolved (2026-10-11, decision D4). The prompt needs decision D3. · **Verification:** C · **Upgrade item:** P3

- **Truncation with one entry (resolved).** On a context overflow, `truncateOnOverflow` dropped the only entry and
  tried again with an empty trajectory. Upstream raises `ContextWindowExceededError`
  (`dspy/predict/react.py:189-194`, 7eb5acce2). The loop now keeps the last entry: the react step ends the loop and
  the extract steps return the overflow error. Tests: `TruncateOnOverflowSuite`, `ReActSuite`.
- **Default limit (resolved).** The default was `IterationLimit(5)` against `max_iters = 20` (`react.py:17`). It is
  now `IterationLimit(20)` (`modules/programs/.../strategies/ReAct.scala:45`). Test: `ReActSuite`. A program that
  relied on the old default now makes up to 20 react steps.
- **Prompt.** The instructions are a paraphrase (`ReAct.scala:159-165` against `react.py:53-58,71`). The tool list has
  its own format (`ReAct.scala:151-158`). `next_tool_name` is a free string (`ReActProtocol.scala:29-33`); upstream uses
  a `Literal` of the tool names (`react.py:77`), which `FieldSpec.enumValues` can express.

---

## G-38 — RLM differs from upstream

**Status:** In progress. The namespace checks, the odd truncation limit, and the `max_iters` text are resolved
(2026-10-14, batch 13). · **Verification:** R · **Upgrade items:** P5, P6, P8, I8

- **Namespace checks.** Upstream rejects duplicate tools, names that are not identifiers, inputs that collide with
  built-ins or tools, and outputs named `trajectory` or `final_reasoning` (`dspy/predict/rlm.py:243-297`, 74194bfab).
  dspy4s checks four reserved tool names (`modules/programs/.../strategies/RLM.scala:84-89`) and then writes those two
  keys into `raw` (`RLMExecution.scala:136-137`).
- **Odd truncation limit.** One character is lost (`RLMReplProtocol.scala:54-56`, `RLMModel.scala:53-54`; 35ef21f16).
- **Output list.** dspy4s prints `- name (type): desc` (`RLM.scala:164-167`). Upstream prints the type constraints,
  which include the JSON schema (`rlm.py:392-395`).
- **`SandboxSerializable`** (`dspy/primitives/sandbox_serializable.py:41-89`) and the async path are absent.
- **Sandbox `dspy` facade.** Generated code can build `Predict`, `ChainOfThought`, `ReActV2`, or `RLM` and run
  them on the host under one LM budget (`rlm.py:82-113,577-597`, `dspy/primitives/facade.py:132-261`; ba3f9198e).
  Large. It is a prerequisite for `dspy.Flex`.
- Minor (resolved in batch 13): the description text said `max_iterations`; it now says `max_iters`, as upstream.

**Resolution (batch 13).** `RLM.checkNames` rejects duplicate tools, tool names that are not Python identifiers or
that are keywords (`PythonNames`), reserved sandbox names for tools and inputs (with the facade names `dspy`,
`_dspy*`, `__dspy*`), inputs with the name of a tool, and outputs named `trajectory` or `final_reasoning`. The input
name `interpreter_factory` has no meaning in the port and is not reserved. The truncation keeps `limit / 2` characters
at the start and `limit - limit / 2` at the end. Tests: `RLMNamespaceSuite`.

---

## G-39 — `CodeAct` and `ProgramOfThought` are deprecated upstream

**Status:** Resolved (2026-10-11, batch 3, decision D1: removed) · **Verification:** C · **Upgrade items:** P4, P7

**Upstream.** Both carry a deprecation for removal in 3.5, with RLM as the replacement
(`dspy/predict/code_act.py:54-58`, `dspy/predict/program_of_thought.py:59-63`; 99bb44bf4).

**dspy4s.** Both are full ports with no deprecation. One parser difference stays: the echo of a final assignment
needs more than one line (`modules/programs/.../runtime/GeneratedPython.scala:26`), and
`GeneratedPythonSuite.scala:15` pins that result. Upstream removed the condition (fe75476cd).

**Resolution.** dspy4s removed the two programs, before the upstream removal in 3.5. The text above describes the
code before the removal. Removed: `CodeAct`, `CodeActProtocol`, `ProgramOfThought`, `ProgramOfThoughtProtocol`,
`GeneratedPython`, the two guides, the instances of `ProgramRunner`, `OptimizableStructure`, and `Streamable`, and
the six test suites. The parser difference ended with `GeneratedPython`. `PORT_DIFFERENCES.md` section 15 records
the difference from upstream 3.4.

Four tests covered shared machinery through the two programs. They moved: the sandbox tool bridge to `RLMSuite`,
the replacement of the second predictor to a `ReAct` test in `ProgramOptimizableStructureSuite`, and two
`Streamable` tests to the new `StreamableSuite`.

Open after the removal:

- `RLM` has no `Streamable` instance. The two removed programs had one, so `Streamify` cannot run the replacement.
- These public items have no user in main sources: `SubprocessPythonInterpreter`, the plain `CodeInterpreter`
  trait (`RLM` needs `ReplCodeInterpreter`), `ActionOutcome.isError`, and the rejection branch of
  `InterpretedTrajectoryAgent` (`ActionPreparation.Rejected`). `TrajectoryAgent` and `InterpretedTrajectoryAgent`
  have one implementation, `ReAct`.
- Entries of this ledger that are older than this one name the two programs as they were at that time.

---

## G-40 — Callback coverage

**Status:** Resolved (2026-10-14, batch 16). The end event on interrupt was resolved on 2026-10-11 (batch 1). · **Verification:** C (end event), R (new events) · **Upgrade item:** I9

- **End event on interrupt (resolved).** `runWithEnd` now also reports an `InterruptedException` and rethrows
  it. Before the correction, `runWithEnd` caught `NonFatal` only
  (`modules/core/.../runtime/CallbackDispatcher.scala:102-112`). `InterruptedException` is not `NonFatal`, and
  `ParallelExecutor.scala:142` interrupts workers. A start event then has no end event. Upstream reports base
  exceptions (`dspy/utils/callback.py:362,392`; 970f72900).
- **New events.** Upstream has interpreter execute, tool, startup, and shutdown events (b11cfe874), optimizer
  compile events (80553206b), and evaluate events (`callback.py:232-303`). Resolved in batch 16: the evaluate,
  compile, and interpreter events, with `CallbackDispatcher.withEvaluate`, `withCompile`, and `withInterpreter`.
  Tests: `LifecycleCallbackSuite`, `DenoInterpreterCallbackSuite`, `EvaluateCallbackSuite`, `CompileCallbackSuite`.
  The new sealed cases need a case in an exhaustive match of user code.

---

## G-41 — Parallel execution differences

**Status:** Open · **Verification:** R

- **Timeout.** `ParallelExecutor` fails the full batch if no task completes in the limit
  (`modules/programs/.../runtime/ParallelExecutor.scala:98-100,121-122`). Upstream submits the slow task again and
  continues (`dspy/utils/parallelizer.py:207-225`). `PORT_BACKLOG.md` defers the straggler retry only.
- **Typed `Parallel`.** `Parallel.run` accepts `DynamicModule` only (`modules/programs/.../Parallel.scala:25-27`), and
  `fanout` is sequential (`modules/programs/.../compose/Both.scala:12-15`).
- **Temperature for `n > 1`.** Upstream sets 0.7 when the temperature is low (`dspy/predict/predict.py:179-185`).
  `PredictEngine.scala:96-121` has no equivalent.

---

## G-42 — `BootstrapFewShot` differs from upstream in six places

**Status:** Resolved (2026-10-11, batch 2, after decision D2) · **Verification:** C · **Upgrade item:** O1

It also feeds MIPROv2, InferRules, KNNFewShot, and the random search.

- **Demos.** dspy4s builds one demo from the program inputs and outputs and gives it to all leaves
  (`modules/optimize/.../BootstrapFewShot.scala:91-97,126-127`). Upstream builds a demo for each predictor from its
  trace step (`dspy/teleprompt/bootstrap.py:224-240`). In a program with more than one predictor, the inner
  predictors get demos with the wrong fields.
- **Labeled pool.** dspy4s uses only the examples that it tried and that failed (`:68,114,118`). Upstream uses all
  examples that were not bootstrapped (`bootstrap.py:173`). If the first examples pass, dspy4s adds no labeled demo.
- **Errors.** A `Left` result is not counted, so `maxErrors` does not trip (`:80,100-102`; `bootstrap.py:213-219`).
- **Retries.** dspy4s repeats the same call (`:72-78`). Upstream changes the rollout id and the temperature
  (`bootstrap.py:191`).
- **Metric.** dspy4s passes no trace (`:84`; `bootstrap.py:206`).
- **Teacher.** dspy4s does not prime the teacher with labeled demos (`bootstrap.py:103-106`).

Commit 1bc87de15 (labeled demos sampled for each predictor) has no target until the demos are per predictor.

**Resolution.** `BootstrapFewShot` now follows the upstream steps. It finds the calls of each leaf by the predictor
key of the trace entries (decision D2; `PredictorTraces`, `TracedRun`). All six items above are corrected, and the
labeled demos are sampled for each predictor. The metric runs in the isolated context of the run. MIPROv2,
InferRules, KNNFewShot, and the random search return a configuration error from the bootstrap; before, two of them
discarded it. Tests: `BootstrapFewShotPerPredictorSuite`, `OptimizerConfigurationErrorSuite`, `BootstrapFewShotSuite`.

**Deliberate differences that stay** (also in the scaladoc of `BootstrapFewShot`):
- dspy4s has no `_compiled` flag. A teacher is "not compiled" when none of its leaves has a demo; only such a
  teacher gets labeled demos first. A student that is its own teacher and has demos keeps them.
- The signature check between student and teacher compares the structure, which holds no instructions.
- The random choices are repeatable for a seed, but they are not equal to those of Python.
- The report key `num_labeled_demos` is now `num_labeled_demos_per_predictor`.
- No end-to-end bootstrap test exists yet for ReAct or RLM.

---

## G-43 — Random search and MIPROv2 candidates are less diverse than upstream

**Status:** Open · **Verification:** R

- `BootstrapFewShotWithRandomSearch` gives a subset of 1 to N examples as the trainset
  (`modules/optimize/.../BootstrapFewShotWithRandomSearch.scala:82-101`). Upstream shuffles the full trainset and
  changes `max_bootstrapped_demos` (`dspy/teleprompt/random_search.py:106-112`).
- In MIPROv2 the seed changes only the labeled shuffle (`modules/optimize/.../MIPROv2.scala:132-146`). Upstream
  shuffles and changes the size (`dspy/teleprompt/utils.py:392-393`).

---

## G-44 — GEPA engine differs from the upstream engine

**Status:** Resolved (2026-10-13, batch 9). Resolved: dominated candidates (batch 1); trace association, failure feedback, and empty reflective data (2026-10-11, batch 2); round-robin pointer and merge depth (batch 5); second proposal call (2026-10-12, batch 6); error limit of an evaluation (2026-10-12, batch 7); resume and the stop reason (2026-10-13, batch 9). · **Verification:** C (a second read of the two sides confirmed the open items, 2026-10-11)

The reference is `dspy/teleprompt/gepa/gepa_utils.py` and the pinned `gepa` 0.1.4 package.

- **Trace association (resolved).** `recordFor` found the trace entry by position. Upstream matches the signature,
  prefers a failed call, and otherwise chooses at random (`gepa_utils.py:375-390`). The position was wrong for
  ReAct, CodeAct, and RLM, where the first leaf runs in a loop. The adapter now takes the entries with the predictor
  key of the leaf (decision D2) and applies the upstream selection with a seeded random source. Tests:
  `GepaTraceAssociationSuite`, `GepaReActTraceSuite`, `GepaPredictorKeySuite`.
- **Dominated candidates (resolved).** `CandidateSelector.Pareto` and `MergeProposer` sampled from the raw
  frontier. Upstream removes dominated programs first (`gepa/gepa_utils.py:37-75,90-117`).
  `GepaState.nonDominatedFrontier` now holds that routine for the two callers. A comparison with the pinned `gepa`
  0.1.4 on 4,000 random frontiers gave no difference. Tests: `GepaStateSuite`, `MergeProposerSuite`.
- **Round-robin pointer (resolved).** The pointers were a local map of `search`, and a new candidate had no entry,
  so it started at 0. Upstream gives a new candidate the largest pointer of its parents
  (`gepa/core/state.py:539-543`). dspy4s also moved the pointer when a perfect minibatch skipped the iteration;
  upstream runs the component selector after the skip checks
  (`gepa/proposer/reflective_mutation/reflective_mutation.py:379-417`). The pointers are now in the state
  (`GepaState.nextComponent`), `GepaState.add` applies the inheritance rule, the selector runs after the
  perfect-score check, and a saved state keeps the pointers. Tests: `GepaPointerMergeSuite`, `GepaStateSuite`,
  `GepaStatePersistenceSuite`.
- **Empty reflective data (resolved).** dspy4s asked the reflection LM with zero examples and evaluated an unchanged
  candidate. Upstream skips the proposal (`gepa_utils.py:462-469`). A component with no record now gets no
  reflection LM call, and an iteration with no new text makes no acceptance evaluation. The minibatch calls stay
  counted, so the loop still ends at the budget.
- **Error limit of an evaluation (resolved).** The scores-only path ran through `Evaluate` with the error limit of
  the context (10 by default). When more than 10 runs of one batch failed, each example of the batch got the
  failure score, also the runs that passed. Upstream gives `Evaluate` a limit of 100 times the batch size
  (`gepa_utils.py:285-294`), so the limit never applies. The scores-only path now runs each example in an isolated
  context and keeps each result. Test: `GepaObjectiveEngineSuite`. A run or a metric that throws an exception
  still counts for the error limit of the executor, on the two paths; a failure that is a `Left` does not.
- **Second proposal call (resolved).** A reflection call that failed dropped its component only, with no second
  call. Upstream calls the proposer for the task one more time, and a second failure gives no proposal for the task
  (`gepa/proposer/reflective_mutation/reflective_mutation.py:203-225`). The engine now does the same. With more than
  one selected component, the failure of one component drops the texts of all of them. Tests:
  `GepaProposerEngineSuite`, `GepaFailureFeedbackSuite`.
- **Failure feedback (resolved).** dspy4s always included failures and called the user metric with an empty
  prediction. The new option `GepaConfig.addFormatFailureAsFeedback` is `false` by default, as upstream. With the
  option on, a failed call gives the upstream texts and the user metric gets no call. Tests:
  `GepaFailureFeedbackSuite`.
- **API change.** `Gepa.compile` and `GepaEngine.optimize` now return `Either[DspyError, GepaResult[P]]`, because two
  leaves with the same key and a leaf type with no key are errors (decision D2).
- **Resume (resolved, batch 9).** The iteration index and the random sources started again, `Gepa.compile` did not
  pass a run directory, and a checkpoint that was not valid threw an exception. Upstream continues the iteration
  index and the pointers, and restores the random state of the adapter; it loses the merge schedule and the engine
  random source. Now `GepaConfig.runDir` is the run directory of the engine and of the facade. The state holds the
  iteration index and the merge schedule. The random source of each iteration, each epoch shuffle, and each
  reflective dataset comes from the seed and fixed coordinates, so a resumed run makes the same draws as a run with
  no stop. This is a deliberate difference: it is stronger than upstream, and it changed the seeded results of the
  runs of earlier versions. Each checkpoint error and each configuration error is a `Left`, and the file stays
  unchanged. A checkpoint that cannot be written is a `Left` too. Test: `GepaRunSuite`. One gap stays: the
  checkpoint does not keep the cached scores of a merge subsample that the run rejected, so in a rare case a resumed
  run makes more metric calls than a run with no stop.
- **Budget (resolved, batch 9).** dspy4s runs an iteration only when its worst-case cost fits. Upstream checks the
  budget only at the start of an iteration, so a run can go above it (`gepa/core/engine.py:731`). The hard limit is
  a decision of the owner and stays. The gap was the missing signal: a run with zero iterations did not say why.
  `GepaResult` now has `stopReason` (`BudgetExhausted`, `IterationDoesNotFit(required, remaining)`, or
  `PerfectScore`), `iterations`, and `maxMetricCalls`, and `GepaState.failedProposals` counts the proposal tasks
  whose proposer failed two times. Test: `GepaRunSuite`.
- **Merge depth (resolved).** dspy4s made one loop of 10 pair draws against 10 attempts of 10 pair draws
  (`gepa/proposer/merge.py:87,126-141`). A merge description that was already made ended the search; upstream
  continues with the next attempt (`merge.py:195-197`). Upstream also makes no merge when the two candidates share
  fewer than 5 validation examples (`merge.py:199-201,231`). `MergeProposer.findMerge` now has the outer loop with
  the two rules, and `GepaConfig.mergeValOverlapFloor` (default 5) is the floor. Tests: `GepaPointerMergeSuite`,
  `MergeProposerSuite`. Change in behavior: a validation set with fewer than 5 examples gets no merge unless the
  floor is lower. Deliberate difference: dspy4s accepts a floor of 0 (no floor), and it keeps the uniform choice of
  an ancestor when each weight is zero, where upstream raises.
- **Evaluation cache.** dspy4s always uses it. Upstream has it off by default, so an accepted merge costs 5 plus
  the validation size upstream and the validation size at most in dspy4s. This difference is deliberate.

---

## G-45 — GEPA surface added upstream since 3.2.1

**Status:** Resolved (the instruction proposer, 2026-10-12, batch 6; the objective frontiers, 2026-10-12, batch 7;
more than one proposal, 2026-10-12, batch 8; the facade, 2026-10-13, batch 9). · **Verification:** C (a second read of the two sides,
2026-10-11) · **Upgrade items:** O3, O4, O5

The second read corrected the first report in two points: objective frontiers and more than one proposal are options
upstream, and their defaults agree with dspy4s. Only the instruction proposer changes default behavior.

- **`InstructionProposer` is the default proposer** (`dspy/teleprompt/gepa/instruction_proposal.py:189-213,314-437`;
  b6c8aaf1e). It is a `Predict` over the signature `ProposeInstruction`, with a JSON adapter. The response is a JSON
  object with `new_instruction`; no fence rule applies. The options are `skills`, `additional_instructions`,
  `base_instructions`, `max_chars`, `truncate_history_outputs`, and `adapter`.
  - The reflective records hold values, not text: `Inputs` and `Generated Outputs` are records, and a `History`
    input moves to the key `Context` (`gepa_utils.py:398-432`).
  - An empty `new_instruction` is an error. The engine calls a failed task one more time, and a second failure gives
    no proposal (`gepa/proposer/reflective_mutation/reflective_mutation.py:203-225`).
  - With `max_chars`, a draft that is too long gets one `CompressInstruction` call. A result that is still too long
    is an error; nothing is cut. The count is in code points.
  - dspy4s before batch 6: `InstructionProposer` was an object with one raw LM call, a paraphrase of the old engine
    template, text records, and a fence parser. An empty response gave a child with an empty instruction, and a
    failed call was not repeated.
  - **Resolved (batch 6).** `InstructionProposer` is a trait (gepa's `ProposalFn`), and `DefaultInstructionProposer`
    is the default of `Gepa` and `GepaEngine`. It runs a `DynamicPredict` over the upstream signature and texts, with
    its own `JSONAdapter` that has no fallback to plain text. `ReflectiveRecord` holds values, and a `History` input
    moves to the key `Context`. The engine calls the proposer in a context whose language model is the reflection LM,
    and one more time after a failure. The options are `additionalInstructions`, `baseInstructions`, `maxChars`, and
    `adapter`. Tests: `InstructionProposerSuite`, `GepaProposerEngineSuite`.
  - **Differences that stay.**
    - The proposer writes the JSON text of the records itself (`ReflectiveRecord.toJson`, in the form of Python
      `json.dumps`), because the dspy4s `JSONAdapter` does not write a sequence as JSON. The other parts of the
      prompt are those of the dspy4s adapter, not the upstream bytes (G-37): the JSON adapter shows no field
      description.
    - `skills` and `truncate_history_outputs` are not ported. The skills loader needs a YAML parser. The ReAct
      trajectory of dspy4s is a string that is already rendered, so only a real `History` input can be compacted.
    - Upstream logs each failed proposal. dspy4s has no logger in this module, so a run in which each reflection
      call fails returns the seed with no signal. The stop reason of a run (G-44) is the place for that report.
    - A response `{"new_instruction": null}` gives the instruction `null`: `JSONAdapterParser` gives the JSON text
      of a value that is not a string. Upstream fails there. The correction is in the adapter (decision D5).
    - Only a `Left` of a proposer gets the second call. An exception goes to the caller; upstream catches it.
- **Objective-aware frontiers are an option** (`gepa_utils.py:70-79,301-310`; 638e155cf; engine `frontier_type` with
  the default `"instance"`). The metric can return `objective_scores`, and the engine can select candidates by
  `objective`, `hybrid`, or `cartesian` fronts (`gepa/core/state.py:157-236,474-601`). Acceptance and the best
  candidate use the scalar score only. dspy4s before batch 7 had the instance front only, and `ScoreWithFeedback`
  had no objective field. The G-12 reason for the exclusion ("the dspy surface does not use them") no longer holds.
  - **Resolved (batch 7).** `ScoreWithFeedback.objectiveScores` is new. The adapter keeps the objective scores of the
    program-level feedback on its two paths, the evaluation cache keeps them, and the state keeps them for each
    candidate and each validation example (`GepaState.valObjectiveScores`). `GepaConfig.frontierType` selects
    `Instance` (default), `Objective`, `Hybrid`, or `Cartesian`; `GepaState.fronts` derives the fronts, and candidate
    selection and merge use them. A saved state keeps the objective scores and the type, and a resume with another
    type is an error. `GepaResult.state` gives the data of a run. Tests: `GepaObjectiveFrontierSuite`,
    `GepaObjectiveEngineSuite`.
  - **Differences that stay.**
    - The order of the fronts. gepa keeps the keys in the order of their first appearance. dspy4s orders the
      objectives by name, because a map of objective scores has no order. The order has an effect only on the
      candidate that a seeded random draw selects.
    - A candidate with no objective score. gepa raises when the frontier type is not `instance`, and that stops
      the run. This occurs when each validation run of an accepted candidate fails. dspy4s keeps the candidate in
      the pool, and it is in no objective front and in no cartesian front. Only a seed candidate with no objective
      score is an error.
    - dspy4s keeps the objective scores of each validation example. gepa keeps the aggregates, and the raw scores
      only for the cartesian type.
- **More than one proposal in one iteration is an option** (aa9d2d053, 822f39319; engine `sampling_strategy` with the
  default `SingleMutationSampling`, and `AllImprovements` selection). Upstream evaluates the parents, the children,
  and the selected candidates in batches on threads (`gepa_utils.py:312-332`). dspy4s before batch 8 made one
  proposal with strict acceptance, which is the upstream default.
  - **Resolved (batch 8).** `GepaConfig` has `proposalSampling` (`Single`, `SameParent(n)`, `Independent(n)`,
    `PxN(p, n)`), `proposalSelection` (`AllImprovements`, `BestImprovement`, `TopK(k)`), and `acceptance`
    (`StrictImprovement`, `ImprovementOrEqual`), with the upstream defaults. The iteration has the upstream stages:
    the tasks, one batch for the parents with shared equal evaluations, the reflection in task order, one batch for
    the children, the selection, the removal of equal children, one batch for the validations, and the adds in
    order. Each task moves the round-robin pointer of its parent, and each added candidate schedules one merge.
    `GepaAdapter.batchEvaluate` divides the threads between the candidates as upstream does. A later call of the
    minibatch sampler in one iteration takes the next window. Tests: `GepaProposalsSuite`.
  - **Differences that stay.**
    - The budget is a hard limit (decision of the owner). An iteration starts only when its largest possible cost
      fits: `2 × tasks × minibatch + limit × |distinct valset|`. Upstream checks the budget only at the start of an
      iteration, so it can go above the budget.
    - dspy4s captures no traces for the children and for the validations. Upstream does; this changes no score.
    - The selection and the acceptance are fixed sets of strategies. Upstream takes any object with the method of
      the strategy.
    - Equal minibatches are equal by the content and the order of their examples. Upstream compares the ids of the
      examples. The result differs only when the trainset holds equal examples at different indices.
- **Facade.** `auto` budgets (`light` 6, `medium` 12, `heavy` 18 candidates through `auto_budget`), `max_full_evals`,
  the trainset as the default validation set, and `track_stats` with all candidates and their lineage
  (`dspy/teleprompt/gepa/gepa.py:26-30,448-453,513-594,690-692`). dspy4s before batch 9 required `maxMetricCalls`
  and a validation set, and returned the best candidate only.
  - **Resolved (batch 9).** `GepaConfig.budget` is `GepaBudget.MetricCalls(n)`, `FullEvals(n)`, or `Auto(Light |
    Medium | Heavy)`, with the upstream formula (`GepaBudget.auto`) and the upstream rules for the validation set
    that the caller gave. `Gepa.compile(student, trainset)` uses the trainset as the validation set, and an empty
    validation set does the same. An empty trainset is a `Left` before any LM call. `GepaResult.state` holds each
    candidate, its lineage, its scores, and its discovery count (`GepaState.discoveryCalls`, gepa's
    `num_metric_calls_by_discovery`). Tests: `GepaBudgetSuite`, `GepaRunSuite`.
  - **Not ported.** `track_best_outputs` (the best outputs of each validation example), and the callbacks of the
    engine. `track_stats` is not necessary: the result always holds the state.

**Direction.** The proposer, the frontiers, the proposals, and the facade are done.

**Decisions of the owner (2026-10-12).**

- The proposer writes the JSON text of the records itself.
- The first version has no `skills` and no `truncate_history_outputs`.
- The budget stays a hard limit. An iteration that does not fit does not start, and the result must report why a
  run stopped (G-44). This rule also applies to an iteration with n proposals (O5).

---

## G-46 — `Evaluate` returns 0.0 for an empty devset

**Status:** Resolved (2026-10-11, batch 1) · **Verification:** C · **Upgrade item:** O2

`Evaluate` returned a result with the score 0.0. Upstream raises a descriptive error
(`dspy/evaluate/evaluate.py:157-158`, c7ee4462a). An optimizer that received an empty validation set then saw a
valid score of zero.

**Resolution.** `Evaluate` returns `Left(ValidationError)` and does not call the program. COPRO, MIPROv2,
InferRules, and the random search return `OptimizerSupport.emptyEvalsetError` before any LM call. Tests:
`EvaluateSuite`, `OptimizerEmptyEvalsetSuite`.

**Two differences that stay.** The random search rejects `valset = Some(Vector.empty)`; upstream (`valset or
trainset`) uses the trainset. GEPA with an empty validation set uses the trainset since batch 9, as upstream
(G-45).

---

## G-47 — No MCP tool conversion

**Status:** Open · **Verification:** R · **Upgrade item:** I10

**Upstream.** `dspy/utils/mcp.py:54-86`, with SDK v2 support and opt-in structured results.

**dspy4s.** None; the MCP examples are blocked. `ToolFunction.argSchema` is a flat map
(`modules/programs/.../contracts/ToolFunction.scala:23`), so a JSON Schema argument model is a prerequisite.

---

## G-48 — Small surface gaps

**Status:** Open · **Verification:** R · **Upgrade item:** A7

- **Test doubles.** Upstream publishes `DummyLM` and `dummy_rm` (`dspy/utils/dummies.py:16,154`). Many dspy4s suites
  define their own scripted LM.
- **Retriever persistence.** `Embeddings.save`, `load`, and `from_saved` (`dspy/retrievers/embeddings.py:121-247`).
- **Version in saved state** (`dspy/primitives/base_module.py:200,278-280`). `ProgramPersistence` writes none.
- **Custom rationale field** in `ChainOfThought` (`dspy/predict/chain_of_thought.py:39-48`). dspy4s fixes
  `reasoning: String`.
- **`append_instructions`** (`dspy/signatures/signature.py:307-333`). Resolved in batch 13:
  `SignatureLayout.appendInstructions` and `Signature.appendInstructions` (tests: `AppendInstructionsSuite`,
  `SignatureAppendInstructionsSuite`).
- Low value: `Unbatchify`, LangChain tool conversion, the asyncify worker limit.

---

## G-49 — Experimental upstream tracks that wait

**Status:** Open (deferred, decision D6) · **Verification:** I · **Upgrade items:** A8, P9, O6

- **Decision types.** `Noul`, `Score`, and `Choice` outputs, a `DecisionAdapter` that asks for evidence and decodes
  with thresholds, cuts, and weights (`dspy/adapters/decision.py:68-146`, `dspy/adapters/types/decision.py`), and the
  "System One" client (`dspy/clients/typesafe.py`). Three weeks old at the time of the analysis.
- **ReAnchor.** A calibration optimizer for those types (`dspy/teleprompt/reanchor/calibrate.py`). Its fold check
  (`calibrate.py:236-253`) is useful alone: it keeps a candidate only if the gain holds on held-out folds.
- **`dspy.Flex`.** The module parameter is Python source that runs in the sandbox, and GEPA rewrites the source
  (`dspy/predict/flex/flex.py`). It needs the sandbox facade of G-38 and a "code" parameter kind.
- **ReActV2.** See G-19.

---

## G-50 — The runtime context has two sources of truth

**Status:** Open · **Verification:** R

This item is a dspy4s design point, not an upstream difference. `RuntimeEnvironment` has a process-wide default and
a `ThreadLocal` (`modules/core/.../runtime/RuntimeEnvironment.scala:41,51`). The LM comes from the `using` context
(`modules/programs/.../runtime/SettingsProgramRuntime.scala:12-13`), but callbacks and workers read the ambient one
(`CallbackDispatcher.scala:36-37`, `ParallelExecutor.scala:32-38`). The two can differ when a caller passes a context
that is not installed.

---

## Recently resolved (this session)

The following gaps were closed in the 3.1.3 → 3.2.1 port batches. They never had
their own `G-N` entries; recorded here for history.

- **`ContextWindowExceededError` type + wiring** — added to the `DspyError`
  hierarchy (`modules/core/.../contracts/Errors.scala`, 360aa30) and wired into
  `OpenAiClient.statusError` (HTTP 400 + context-window body marker, b01c627).
- **LM capability flags** — `supportsFunctionCalling` / `supportsResponseSchema`
  / `supportsReasoning` on `LanguageModel` (default false; OpenAI overrides true)
  (b01c627). Enabler for G-7.
- **`inspect_history`** — `RuntimeEnvironment.inspectHistory(n)` + `HistoryRenderer`
  (b01c627).
- **`Ensemble` optimizer** — `modules/optimize/.../Ensemble.scala`, majority-vote
  default (b01c627).
- **Evaluate `display_table` + `provideTraceback`** — `EvaluationResult.renderTable`
  (text table) and failing-example `DspyError` capture (b01c627).
- **CoT/PoT prefix normalization** — field prefixes derived via `FieldSpec.normalize`
  instead of hardcoded literals (360aa30).
