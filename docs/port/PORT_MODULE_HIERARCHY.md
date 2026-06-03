# dspy4s ⇄ Python DSPy — Module/Program Class Structure

> **Companion docs:** [PORT_DIFFERENCES.md](PORT_DIFFERENCES.md) (narrative),
> [PORT_GAPS.md](PORT_GAPS.md) (open gaps — G-1/G-2 referenced below),
> [PORT_MAP.md](PORT_MAP.md) (per-symbol map).
>
> This doc is a **side-by-side of the class hierarchy and method names** for
> `Predict` / `ChainOfThought` / `ReAct` / … — base classes, the caller entry
> point (`__call__`/`apply`), the async variant (`acall`/`arun`), and the
> overridable hook (`forward`).

## The two base hierarchies

**Python DSPy** (`dspy 3.1.3`):

```
BaseModule                       # introspection + persistence
  └─ Module (metaclass=ProgramMeta)   # callable: __call__ -> forward, acall -> aforward
       ├─ Predict(Module, Parameter)  # also a Parameter (learnable leaf)
       ├─ ChainOfThought(Module)      # composes self.predict: Predict
       ├─ ReAct(Module)               # composes self.react + self.extract
       └─ …
Parameter                        # empty marker class (`pass`) for named_parameters()
```

**dspy4s**:

```
Module[-In, +Out]                       # callable contract: apply (+ arun)
  └─ PredictProgram                     # = Module[ProgramCall, DynamicPrediction] + apply(inputs*) sugar
       ├─ BasePredictProgram            # final apply (wraps callbacks/trace/history) -> abstract forward
       │    ├─ DynamicPredict           # implements forward (the leaf predict engine)
       │    ├─ ReAct                    # implements forward; builds react + extract DynamicPredicts
       │    ├─ CodeAct                  # implements forward
       │    └─ ProgramOfThought         # implements forward
       └─ Refine / BestOfN / MultiChainComparison   # override apply directly (bypass the wrapper — see G-2)

# Typed layer (NOT Module subtypes — standalone case classes):
Predict[I, O]          # typed apply; wraps a lazy inner DynamicPredict
ChainOfThought[I, O]   # typed apply; wraps a Predict[I, O']
```

There is **no `BaseModule`** and **no `Parameter`** in dspy4s — see [PORT_GAPS.md G-1](PORT_GAPS.md#g-1--no-typed-predictor-introspection-layer-pythons-basemodulenamed_predictors).

## Method-name mapping

| Concept | Python DSPy | dspy4s |
|---|---|---|
| Caller entry (sync) | `__call__` | `apply` |
| Caller entry (async) | `acall` (coroutine) | `arun` (`Future`) |
| Overridable hook (sync) | `forward` | `forward` |
| Overridable hook (async) | `aforward` (coroutine) | — *(no async hook; `arun` wraps the sync `apply` via `ContextPropagation.future`)* |
| Universal callable base | `Module` | `Module[-In, +Out]` |
| Container/persistence base | `BaseModule` | — *(absent; immutability + typeclasses, G-1)* |
| Learnable-leaf marker | `Parameter` | — *(typeclass `PredictOps[P]`, G-1)* |
| Enumerate sub-predictors | `named_predictors()` / `named_parameters()` | — *(gap, G-1)* |
| Attach demos | mutate `predictor.demos` | `PredictOps.withDemos` (returns a copy) |
| Set the LM | `set_lm` / `get_lm` | ambient `RuntimeContext.lm` (no per-module LM) |
| Where cross-cutting wrapping lives | `Module.__call__` (universal, non-bypassable) | `BasePredictProgram.apply` (opt-in by base class — [G-2](PORT_GAPS.md)) |

## Per-class side-by-side

| Program | Python base(s) | Python entry → hook | dspy4s base | dspy4s entry → hook |
|---|---|---|---|---|
| **Predict** | `Module, Parameter` | overrides `__call__`/`acall` **and** `forward`/`aforward` | `Predict[I,O]` — *standalone case class* (wraps `DynamicPredict`) | typed `apply` → inner `DynamicPredict.apply` |
| *(untyped predict)* | — *(Predict is the leaf)* | — | `DynamicPredict` ◂ `BasePredictProgram` | inherited `apply` → own `forward` |
| **ChainOfThought** | `Module` | `forward` → `self.predict(**kwargs)` | `ChainOfThought[I,O]` — *standalone case class* (wraps `Predict`) | typed `apply` → inner `Predict.apply` |
| **ReAct** | `Module` | `forward`/`aforward`; `self.react` + `self.extract` | `BasePredictProgram` | inherited `apply` → own `forward` |
| **CodeAct** | `Module` | `forward` | `BasePredictProgram` | inherited `apply` → own `forward` |
| **ProgramOfThought** | `Module` | `forward` | `BasePredictProgram` | inherited `apply` → own `forward` |
| **Refine / BestOfN / MultiChainComparison** | `Module` | `forward` | `PredictProgram` (direct) | override `apply` directly — *no `forward`, bypasses wrapping (G-2)* |

## Key structural differences (callouts)

1. **`__call__` ⇒ `apply`, `acall` ⇒ `arun`, `forward` ⇒ `forward`.** Scala's
   `apply` is the idiomatic `__call__`, so `program(input)` works like Python's
   `program(input)`. The hook keeps the name `forward`.

2. **No async hook.** Python has a full async path (`acall` → `aforward`).
   dspy4s has only `arun` (a `Future` wrapper over the sync `apply` with
   thread-local context propagation); there is no `aforward`-equivalent
   override point.

3. **Python `Predict` overrides the caller entry too.** It customizes
   `__call__`/`acall`, not just `forward`/`aforward`. In dspy4s the caller
   entry (`apply`) is `final` on `BasePredictProgram` (you only override
   `forward`) — and the typed `Predict[I,O]` is a *separate* standalone type
   with its own `apply`, not a subclass of the untyped predict.

4. **`Predict` is a `Parameter`; dspy4s's leaf is `DynamicPredict`.** Python's
   `Predict` is simultaneously a `Module` and a learnable `Parameter`. dspy4s
   has no `Parameter` marker; the learnable leaf is `DynamicPredict`, and
   "this is tunable" is expressed by a `PredictOps[DynamicPredict]` typeclass
   instance rather than a base class (G-1).

5. **Composition vs subclassing for the typed layer.** Python's
   `ChainOfThought` *is a* `Module` that *has a* `Predict`. dspy4s's
   `ChainOfThought[I,O]` and `Predict[I,O]` are standalone case classes
   (typed wrappers) that are **not** `Module` subtypes — they delegate to an
   inner untyped `DynamicPredict`/`Predict`. The `Module` contract is the
   untyped spine; the typed layer sits beside it.

6. **Where cross-cutting wrapping lives differs.** Python puts the
   callback/trace/usage wrapping on `Module.__call__` — universal and
   non-bypassable. dspy4s puts it on `BasePredictProgram.apply` (`final`,
   wrapping `forward`), so it's guaranteed only for that subtree;
   `Refine`/`BestOfN`/`MultiChainComparison` extend `PredictProgram` directly
   and override `apply`, bypassing it. See [PORT_GAPS.md G-2](PORT_GAPS.md).
