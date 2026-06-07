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

## G-1 — No typed predictor-introspection layer (Python's `BaseModule.named_predictors`)

**Status:** Resolved (P1–P6, commits 30420f3 / 25d7e8d / 9bcf99f / dd466be / c459d07 / 1657f9c)

**Resolution.** Closed by a typed `Predictors[P]` / `Predictor[P]` typeclass pair
with Scala 3 Mirror derivation (`modules/optimize/.../Predictors.scala`): `read`
enumerates the contained predictors of an arbitrary composite in stable order,
`replace` rebuilds it immutably (`replace(p, read(p)) == p`). User composites derive
it for free; framework composites (`ReAct`, `CodeAct`, `MultiChainComparison`) get
hand-written instances and had their sub-predicts **hoisted to stable fields**
(closing the "Related" sub-gap below). Optimizers moved off the single-`DynamicPredict`
`PredictOps` assumption onto `Predictors` (the `LabeledSampleProgram` glue was deleted),
and a `Runnable[P]` capability (`Runnable.scala`) dropped the `P <: DynamicModule` bound
so **typed** programs and user composites are now optimizable end-to-end. The legacy
`PredictOps` typeclass and its bridge were removed in P6 — `Predictors` is the sole
introspection typeclass. Remaining v1 limits: predictor edits are demos-only (instruction editing deferred);
`MultiChainComparison` has no `Runnable` (its `MultiChainCall` shape has no inputs-only
run); arbitrary user composites supply their own `Runnable`.

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

### dspy4s current state

We deliberately did **not** port `BaseModule` (see the rationale below — most
of it is un-idiomatic in immutable, strictly-typed Scala). Instead:

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

A **typed** equivalent of `named_predictors` — *not* a reflective
`BaseModule`. Candidate shapes:

- a capability method on the program contract, e.g.
  `def predictors: Vector[DynamicPredict]` (and a way to rebuild with updated
  ones), or
- a derivable typeclass `Predictors[P]` that yields/updates the contained
  predictors,

so optimizers can read and immutably replace the learnable parts of any
composite. Decide the shape before extending optimizer coverage.

### Related

- **ReAct builds its sub-predicts in `execute`, not as fields.** Python's
  `ReAct.__init__` creates `self.react` / `self.extract` once
  ([react.py](../../../dspy/dspy/predict/react.py) L88–89); dspy4s rebuilds
  the `DynamicPredict` instances (and re-runs `ChainOfThought.augmentLayout`)
  on every `execute`
  ([ReAct.scala](../../modules/programs/src/main/scala/dspy4s/programs/ReAct.scala)) in `forward`.
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

**Note (commit 42671c2).** `Module` later became generic again — `Module[I, O]`, instantiated at the untyped spine
`DynamicModule = Module[ProgramCall, DynamicPrediction]` and the typed surface `Module[TypedCall[I], Prediction[O]]`
that `Predict` / `ChainOfThought` now extend. This reopened only the `[In,Out]` type params (whose earlier removal
was justified by there being a single instantiation — no longer true once the typed layer joined). `apply` remains
`final` on the one common base, so the wrapping is still universal and non-bypassable: G-2 stays resolved.

---

## G-3 — No per-module `config` / `set_lm` (only per-call config)

**Status:** Partially resolved — module `config` done (commit b85fe27); per-module bound LM (`set_lm`/`get_lm`) deferred

**Resolution (config).** `DynamicPredict` and `Predict[I, O]` now carry an immutable
module-level `config: DynamicValue.Record`, threaded into `PredictEngine` and merged
*under* the per-call config (per-call keys win: `{**module, **call}`); empty module
config is unchanged behavior. **Still open:** a per-module bound LM — the LM is still
resolved from the ambient `RuntimeContext` via `runtime.resolveModel`; pinning a
distinct LM per predictor needs `ProgramRuntime`/model-resolution changes.

**Summary.** Python's `Predict` carries module-level `config` and a `set_lm`/`get_lm`
binding. dspy4s only supported per-*call* config; there was no place to attach a
module-scoped config or a module-bound LM.

### Python reference

`dspy.Predict(signature, **config)` stores `self.config` and `self.lm`
(`set_lm`/`get_lm`), so a predictor can carry its own generation params and a
pinned LM independent of the ambient settings.

### dspy4s current state

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

**Resolution.** Closed by wiring the `SignatureLayout` (de)serialization primitives
up through the program tree, leveraging the `Predictors[P]` introspection layer
(G-1) so a single `Predict` (a length-1 predictor list) and an arbitrary composite
share one code path. New primitives:

- `Example.dumpState` / `Example.fromState` (`modules/core/.../contracts/Data.scala`) —
  `{ "values": <record>, "inputKeys": [..], "augmented": <bool> }`.
- `DynamicPredict.dumpState` / `DynamicPredict.fromState`
  (`modules/programs/.../DynamicPredict.scala`) —
  `{ "signature": <SignatureLayout state>, "demos": [<Example state>..], "config": <record> }`
  (`name` / `runtime` are environment/identity, restored to defaults on load).
- `ProgramPersistence` (`modules/optimize/.../ProgramPersistence.scala`) —
  `dumpState` / `loadState` / `dumpJson` / `loadJson` / `save` / `load`, all
  `Predictors`-based: `{ "predictors": [<DynamicPredict state>..] }`. JSON via
  `Schema.dynamic.jsonCodec` (same codec as `SignatureLayout.dumpJson`); file IO
  wraps exceptions into `RuntimeError`.

**Round-trip scope.** Demos round-trip for every program. `DynamicPredict` leaves
round-trip everything (signature/layout + demos + config). `Predict` restores demos,
config, and layout **instructions**; `ChainOfThought` restores demos and instructions
(no module config field). The field **structure** of the layout is not written back into
a typed program (that would desync `signature.outputShape` from `signature.layout`), so
typed targets keep their own field shape (the full layout still round-trips in the JSON).
`loadState` requires the `predictors` array length to equal `Predictors.read(program).size`
(mismatch → `Left(ValidationError)`). Instruction write-back was added with the
instruction-editing enabler (commit pending), unblocking COPRO/MIPRO-style optimizers.

**Summary (original).** There is no JSON state save/load for a program. The serialization
primitives exist on `SignatureLayout`, but nothing wires them up to a program,
and the demos are never persisted.

### Python reference

`BaseModule.dump_state`/`load_state` and `save`/`load` serialize a whole program
(signatures + demos + config) to/from JSON.

### dspy4s current state

`SignatureLayout` has the layout (de)serialization primitives, but no program
exposes `save`/`load`/`dumpState`/`loadState`; demos and config are never written
out. The demos in the example programs are never serialized.

### Why it matters

Compiled programs can't be persisted and reloaded — the standard
"optimize once, deploy the artifact" workflow is unavailable.

### Proposed direction

Wire the `SignatureLayout` primitives into a program-level `dumpState`/`loadState`
(JSON) and `save`/`load`. Tier 0. A single `Predict` is doable now; composite
programs need the predictor-traversal layer (depends on **G-1**).

---

## G-5 — `Refine` is a thin best-of-n alias (no `OfferFeedback` loop)

**Status:** Open

**Summary.** dspy4s's `Refine` is just a best-of-n wrapper. Python's `Refine`
runs an `OfferFeedback` advice/feedback loop between attempts; that loop is
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

**Status:** Open

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

**Status:** Open

**Summary.** Adapters do not use native provider function-calling preprocessing,
and `JSONAdapter` does not emit `response_format` structured outputs. The
capability flags that would gate these now exist, but the adapters don't consume
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

**Status:** Open

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

**Status:** Open

**Summary.** dspy4s has no constraint vocabulary in `FieldSpec`, so Python's
constraint rendering (`gt`/`ge`/`lt`/`le`/`min_length`/…) has no equivalent.

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

**Status:** Open

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

**Status:** Open

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
