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

**Status:** Open

**Summary.** dspy4s has no way to enumerate (and transform) the `Predict`s
inside an arbitrary composite program. Python's optimizers rely on exactly
this; ours can only reach a single `DynamicPredict` via the `PredictOps`
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
