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
Module[I, O]                            # ONE generic base (port of dspy.Module):
  │                                     #   final apply (wraps callbacks/trace/history) -> abstract forward
  │
  ├─ DynamicModule = Module[ProgramCall, DynamicPrediction]   # untyped spine (bag projection hooks defaulted)
  │    └─ DynamicPredict                # the leaf predict engine — the runtime substrate the typed programs use
  │
  └─ typed layer = Module[TypedCall[I], Prediction[O]]        # EVERY user-facing program is here
       ├─ Predict[I, O]                 # forward = encode -> PredictEngine -> decode (sibling of DynamicPredict)
       ├─ ChainOfThought[I, O]          # forward delegates to an inner Predict[I, Out]
       ├─ ReAct[I,O] / CodeAct[I,O] / ProgramOfThought[I,O]   # run loop/extractor internally; decode -> WithField[O,"reasoning",String]
       ├─ MultiChainComparison[I, O]    # Module[MultiChainCall[I], …]; decode -> WithField[O,"rationale",String]
       └─ BestOfN[I, O] / Refine[I, O]  # best-of-n over an inner typed program (output-preserving)
```

dspy4s has **one generic base `Module[I, O]`** — the port of `dspy.Module` — with `apply` `final` (the lifecycle
wrapping) over an abstract `forward`. It is instantiated at two layers: the untyped spine
`Module[ProgramCall, DynamicPrediction]` (aliased **`DynamicModule`**, with the callback/trace projection hooks
defaulted to the bag shapes), and the typed surface `Module[TypedCall[I], Prediction[O]]` that **every
user-facing program extends** — `Predict` / `ChainOfThought` / `ReAct` / `CodeAct` / `ProgramOfThought` /
`MultiChainComparison` / `BestOfN` / `Refine`. Only **`DynamicPredict`** lives on the untyped spine, where it
belongs: it's the runtime substrate the agents and CoT build their inner predicts from. `TypedCall[I]` is the
typed-layer call object (the typed counterpart of `ProgramCall`: a typed `input` plus `config` / `traceEnabled`
/ `rolloutId`). The agents run their loop/extractor over the data-bag layer internally and decode the result
back to the typed output; `MultiChainComparison` uses a bespoke `MultiChainCall[I]` (base input + candidate
completions, mirroring Python's `forward(completions, **kwargs)`).

Output-augmenting programs (`ChainOfThought`, `ReAct`, `CodeAct`, `ProgramOfThought`, `MultiChainComparison`)
prepend a field to the output via the shared
[`OutputAugmentation`](../../modules/typed/src/main/scala/dspy4s/typed/OutputAugmentation.scala) helper
(`WithField[O, Name, T]` + the `PrependField` typeclass — idempotent, cast-free, always a named tuple). Every
typed signature surface (`of` / `fromType` / `from` / a **literal** `fromString`) yields a product type, so these
programs are uniformly typed; only the genuinely-runtime `Signature.fromStringDynamic` (Record I/O) is outside
the typed surface.

Because `apply` is `final` on the single common base, the lifecycle wrapping is universal and non-bypassable —
typed **and** untyped — so [G-2](PORT_GAPS.md) stays resolved even though `Module` is generic. (`Module` was
briefly collapsed to a non-generic `Module[ProgramCall, DynamicPrediction]`; the type params returned once the
typed layer joined as the second instantiation. There is still **no `PredictProgram` alias** and **no separate
`BasePredictProgram`**.)

There is **no `BaseModule`** and **no `Parameter`** in dspy4s — see [PORT_GAPS.md G-1](PORT_GAPS.md#g-1--no-typed-predictor-introspection-layer-pythons-basemodulenamed_predictors).

## Method-name mapping

| Concept | Python DSPy | dspy4s |
|---|---|---|
| Caller entry (sync) | `__call__` | `apply` |
| Caller entry (async) | `acall` (coroutine) | `applyAsync` (`Future`) |
| Overridable hook (sync) | `forward` | `forward` |
| Overridable hook (async) | `aforward` (coroutine) | — *(no async hook; `applyAsync` wraps the sync `apply` via `ContextPropagation.future`)* |
| Universal callable base | `Module` | `Module[I, O]` *(one generic base; untyped spine `DynamicModule` + typed `Module[TypedCall[I], Prediction[O]]`)* |
| Container/persistence base | `BaseModule` | — *(absent; immutability + typeclasses, G-1)* |
| Learnable-leaf marker | `Parameter` | — *(typeclass `PredictOps[P]`, G-1)* |
| Enumerate sub-predictors | `named_predictors()` / `named_parameters()` | — *(gap, G-1)* |
| Attach demos | mutate `predictor.demos` | `PredictOps.withDemos` (returns a copy) |
| Set the LM | `set_lm` / `get_lm` | ambient `RuntimeContext.lm` (no per-module LM) |
| Where cross-cutting wrapping lives | `Module.__call__` (universal, non-bypassable) | `Module.apply` (`final`; universal, non-bypassable — [G-2 resolved](PORT_GAPS.md)) |

## Per-class side-by-side

| Program | Python base(s) | Python entry → hook | dspy4s base | dspy4s entry → hook |
|---|---|---|---|---|
| **Predict** | `Module, Parameter` | overrides `__call__`/`acall` **and** `forward`/`aforward` | `Predict[I,O]` ◂ `Module[TypedCall[I], Prediction[O]]` | inherited `apply` → own `forward` (encode → engine → decode) |
| *(untyped predict)* | — *(Predict is the leaf)* | — | `DynamicPredict` ◂ `DynamicModule` | inherited `apply` → own `forward` |
| **ChainOfThought** | `Module` | `forward` → `self.predict(**kwargs)` | `ChainOfThought[I,O]` ◂ `Module[TypedCall[I], Prediction[Out]]` | inherited `apply` → `forward` delegates to inner `Predict` |
| **ReAct** | `Module` | `forward`/`aforward`; `self.react` + `self.extract` | `ReAct[I,O]` ◂ `Module[TypedCall[I], Prediction[WithReasoning[O]]]` | inherited `apply` → `forward`: run loop+extractor internally → decode |
| **CodeAct** | `Module` | `forward` | `CodeAct[I,O]` ◂ `Module[TypedCall[I], Prediction[WithReasoning[O]]]` | inherited `apply` → `forward`: run loop+extractor → decode |
| **ProgramOfThought** | `Module` | `forward` | `ProgramOfThought[I,O]` ◂ `Module[TypedCall[I], Prediction[WithReasoning[O]]]` | inherited `apply` → `forward`: generate/regenerate/answer → decode |
| **MultiChainComparison** | `Module` | `forward(completions, **kwargs)` | `MultiChainComparison[I,O]` ◂ `Module[MultiChainCall[I], Prediction[WithField[O,"rationale",String]]]` | inherited `apply` → `forward` (the call carries the completions) |
| **BestOfN / Refine** | `Module` | `forward` | `BestOfN[I,O]` / `Refine[I,O]` ◂ `Module[TypedCall[I], Prediction[O]]` | inherited `apply` → `forward`: best-of-n over inner typed program *(output-preserving)* |

## Key structural differences (callouts)

1. **`__call__` ⇒ `apply`, `acall` ⇒ `applyAsync`, `forward` ⇒ `forward`.** Scala's
   `apply` is the idiomatic `__call__`, so `program(input)` works like Python's
   `program(input)`. The hook keeps the name `forward`.

2. **No async hook.** Python has a full async path (`acall` → `aforward`).
   dspy4s has only `applyAsync` (a `Future` wrapper over the sync `apply` with
   thread-local context propagation); there is no `aforward`-equivalent
   override point.

3. **Python `Predict` overrides the caller entry too; dspy4s only overrides `forward`.** Python customizes
   `__call__`/`acall`, not just `forward`/`aforward`. In dspy4s `apply` is `final` on `Module`, so the typed
   `Predict[I,O]` — itself a `Module[TypedCall[I], Prediction[O]]` — overrides only `forward`, where the typed
   encode/decode runs *inside* the lifecycle wrapping. `Predict[I,O]` is a **sibling of `DynamicPredict`** over
   the shared `PredictEngine` (each a thin `Module`), **not** a wrapper around it — so a typed call emits exactly
   one module event. (A convenience `apply(input, config, traceEnabled)` overload builds the `TypedCall` and
   dispatches through the `final apply`.)

4. **`Predict` is a `Parameter`; dspy4s's leaf is `DynamicPredict`.** Python's
   `Predict` is simultaneously a `Module` and a learnable `Parameter`. dspy4s
   has no `Parameter` marker; the learnable leaf is `DynamicPredict`, and
   "this is tunable" is expressed by a `PredictOps[DynamicPredict]` typeclass
   instance rather than a base class (G-1).

5. **ChainOfThought composes a Predict (matches Python).** Python's `ChainOfThought` *is a* `Module` that *has a*
   `Predict` and whose `forward` returns `self.predict(**kwargs)`. dspy4s is now the same shape:
   `ChainOfThought[I,O]` *is a* `Module[TypedCall[I], Prediction[Out]]` that holds an inner `Predict[I, Out]`
   (built once, memoized) and whose `forward` delegates to it — so a CoT call emits a `chain_of_thought` module
   event wrapping the inner `predict` event, mirroring Python's nesting. The typed layer is **not** a separate
   surface beside the spine anymore; `Predict`/`ChainOfThought` are `Module`s like every other program.

6. **Cross-cutting wrapping is universal (matches Python).** Python puts the
   callback/trace/usage wrapping on `Module.__call__` — universal and
   non-bypassable. dspy4s does the same: `apply` is `final` on the single generic
   `Module[I, O]`, wrapping `forward`, so *every* program — typed or untyped — is observed identically and
   nothing can bypass it. (Earlier the wrapping lived on a separate
   `BasePredictProgram` you opted into, letting `Refine`/`BestOfN`/etc. skip it
   — that was [G-2](PORT_GAPS.md), resolved by merging into one `Module` base; re-genericizing `Module` for the
   typed layer kept `apply` `final`, so it stays resolved.)

7. **The agents are typed-only, and stream via a typeclass.** `ReAct` / `CodeAct` / `ProgramOfThought` are
   `Module[TypedCall[I], …]` with no untyped `Dynamic*` twin. They were the one place that *seemed* to need an
   untyped form: `Streamify` only accepted a `DynamicModule`. Rather than keep untyped twins, `Streamify` was
   generalized to take **any** program through a
   [`Streamable[P]`](../../modules/streaming/src/main/scala/dspy4s/streaming/Streamable.scala) typeclass that
   captures its two real requirements — *run from a record → `DynamicPrediction`* and *best-effort sub-signatures
   for listener validation*. Each typed agent provides a `Streamable` instance (decode the record → typed input →
   run → `.raw`), so it streams with no `DynamicModule` form and emits a single module event (no
   wrapper-over-untyped double event). Only `DynamicPredict` keeps the `dynamicModule` `Streamable` instance.

8. **The string DSL is a *typed*, compile-time surface.** `Signature.fromString("q -> a: bool")` is a
   `transparent inline` macro: it parses the **literal** at compile time (reusing the runtime `SignatureLayout.parse`)
   and synthesizes `NamedTuple` I/O — `Signature[(q: String), (a: Boolean)]` — so the string DSL gives typed
   dot-access like `Signature.of[Spec]` / `fromType[F]`. An invalid DSL or an unsupported field type is a compile
   error. The previous runtime, `Record`-returning version is `Signature.fromStringDynamic` (for genuinely
   runtime-built strings). This is what lets the augmenting programs be uniformly typed: every static signature
   surface produces a product type, never a bare `Record`.

## Design principle: a module is pure; the runtime owns the bookkeeping

The deeper reason dspy4s has no `ProgramMeta` / `_base_init` and no per-instance
`callbacks` / `history` / `_compiled`: **callbacks, history, and tracing are the
runtime/executor's responsibility, not the module's.** A dspy4s module is
essentially a pure `apply: In => Either[DspyError, Out]` (with `forward` as the
overridable hook); it doesn't carry or fire its own callback list or call log.

- **History/trace** are owned by `RuntimeEnvironment` — `Module.apply`
  calls `RuntimeEnvironment.appendTrace`/`appendHistory`, and the environment
  enforces `maxHistorySize` / `disableHistory`.
- **Callbacks** are dispatched by `CallbackDispatcher` off the ambient
  `RuntimeContext.callbacks` (`withModule`/`withTool`/…), not off a list hanging
  on each program.

Python instead hangs `callbacks`/`history`/`_compiled` on every `Module`
instance and merges them with global `settings.callbacks` / `GLOBAL_HISTORY` per
call — which is what forces the `ProgramMeta` metaclass to exist (to guarantee
that per-instance state is initialized even when a subclass forgets
`super().__init__()`). Moving the responsibility to the runtime removes both the
per-instance state and the machinery that babysits it.
