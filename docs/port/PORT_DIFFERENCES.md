# dspy4s ⇄ Python DSPy — Differences

> **Companion docs:**
> [PORT_SIMILARITIES.md](PORT_SIMILARITIES.md) covers what stayed the
> same. [PORT_MAP.md](PORT_MAP.md) is the per-symbol mapping +
> behavioral-delta ledger. [PORT_LANGUAGE_NOTES.md](PORT_LANGUAGE_NOTES.md)
> covers Python→Scala idiom mechanics with code samples.
>
> This doc is **the high-level "what changed shape and why"** story for
> someone coming from Python DSPy. The boundaries match (see
> companion); the connective tissue is what's different here.

## How to read this doc

Each section names the area, contrasts the Python and dspy4s
approaches, explains why the shape changed, and links to where to
read more. Differences fall into four buckets:

1. **Language-forced** — Python idiom has no Scala equivalent, so the
   architecture had to adapt.
2. **Scala-added** — features that exist in dspy4s with no Python
   analogue (the typed I/O layer is the headline example).
3. **Convention** — judgment calls about cleanup or restructuring
   during the port.
4. **Deferred** — areas where the architecture differs because work
   hasn't landed yet, not because of a design choice.

## 1. Signature definition: metaclasses → macros + values

**Language-forced.**

Python:

```python
class Toxicity(dspy.Signature):
    """Mark toxic comments."""
    comment: str = dspy.InputField()
    toxic: bool = dspy.OutputField()
```

`dspy.Signature`'s metaclass walks the class body at definition time
and turns the attributes into a runtime descriptor. The class form
also gives users `Toxicity.with_instructions(...)`, `append(...)`,
etc. as classmethods on the same object.

dspy4s: Scala has no metaclass equivalent. The architecture has **six
factory entry points** that all produce the same immutable
`Signature[I, O]` value:

| Surface | Reaches for |
|---|---|
| `Signature.derived[I, O]("name")` | case classes both sides |
| `Signature.from(someMethod _)` | a method reference |
| `Signature.fromType[F]` | a Scala function type |
| `Signature.of[T <: Spec]` | the trait-as-spec macro, the closest analogue to Python's class form |
| `Signature.builder(name).input[A](...).output[B](...).build` | programmatic |
| `Signature.fromString("q -> a")` | the string DSL — a **compile-time macro** that parses the literal into typed `NamedTuple` I/O |
| `Signature.fromStringDynamic("q -> a")` | the string DSL from a **runtime** string (no static types; `Record` I/O) |

The trait-spec macro is what most Python class signatures translate
to:

```scala
trait ToxicitySpec extends Spec:
  def comment: InputField[String]
  def toxic:   OutputField[Boolean]

val sig = Signature.of[ToxicitySpec]
```

It looks similar but the mechanics are different: a transparent
inline macro inspects the abstract trait at compile time and emits
a `Signature[I, O]` whose I/O types are named tuples. There is no
runtime class introspection.

See [TYPED_SIGNATURES_GUIDE.md](../TYPED_SIGNATURES_GUIDE.md).

## 2. Typed I/O layer (Scala-native addition)

**Scala-added.** This is the single largest architectural addition.

Python `Prediction.value("toxic")` returns `Any` (well, the typed
attribute access works at runtime via Pydantic, but the static type
is opaque). Field-name typos compile; type mismatches surface at
runtime.

dspy4s splits the picture in two:

- `SignatureLayout` is the **erased runtime contract** that adapters,
  the LM stack, callbacks, trace, and history all consume. This
  mirrors Python's runtime view 1:1.
- `Signature[I, O]` is a **compile-time wrapper** that wraps a
  `SignatureLayout` with `Shape[I]` and `Shape[O]` typeclass
  instances. `Predict[I, O].run(input: I)` returns
  `Either[DspyError, Prediction[O]]`. `prediction.output.toxic` is
  statically `Boolean`.

The `Shape[A]` typeclass has three implementations
(the product shape from `ZioSchemaCodec.derivedFromZioSchema` for
case-class I/O via `zio-blocks-schema`, `TupleShape` for named-tuple
I/O from the macros, `MapShape` for the string DSL).
The pair-of-types pattern (erased + typed) repeats: `Prediction`
becomes `DynamicPrediction` + `Prediction[O]` (the typed one keeps the
erased prediction on `.raw`), and `Predict` becomes `DynamicPredict` +
`Predict[I, O]`. The two `Predict`s are **siblings** — thin `Module`s
over the shared `PredictEngine`, not wrapper-and-wrapped. The typed
programs are themselves `Module`s (`Module[TypedCall[I], Prediction[O]]`),
so the runtime stack sees them like any other program; `ChainOfThought`
is a typed signature augmentation that *composes* an inner `Predict[I, O]`
(its `forward` delegates to it).

This is "purely additive": existing dynamic code keeps working
unchanged; adapter authors never see the typed layer; new code can
opt into typed I/O surface-by-surface. See
[TYPED_SIGNATURES.md](../TYPED_SIGNATURES.md) for the design rationale.

## 3. Module parameter discovery: `__dict__` walking → typeclass

**Language-forced.**

Python `BaseModule.named_parameters()` walks `self.__dict__`,
recurses through nested modules, yields anything that looks like a
`Parameter`. Optimizers consume that iterator.

Scala has no `__dict__`. The optimizer side uses a **`PredictOps[P]`
typeclass**: each Predict-shaped program provides a `given` instance
describing how to read its `layout` + `demos` and how to produce a
demo-shuffled copy:

```scala
trait PredictOps[P]:
  def name(program: P): String
  def layout(program: P): SignatureLayout
  def demos(program: P): Vector[Example]
  def withDemos(program: P, demos: Vector[Example]): P
```

`BootstrapFewShot` and `BootstrapFewShotWithRandomSearch` program
against this interface. Less magical at the program-author side
(you have to provide an instance for new Predict types), mechanically
equivalent at the optimizer side. The trade-off is explicit by
design.

## 4. `predict_name` resolution: frame introspection → explicit naming

**Language-forced.**

Python walks the calling stack frame to discover that
`self.cot = ChainOfThought(...)` should be named `"cot"`, so trace
entries and stream listeners can refer to predicts by their parent
attribute name.

Scala has no equivalent runtime attribute introspection. dspy4s
requires explicit naming at construction:

```scala
ChainOfThought(sig, name = Some("cot"))
```

The default `name = None` falls back to the module type's canonical
name (`"chain_of_thought"`, `"predict"`, etc.). Users that need a
distinct per-instance name pass it in.

## 5. Streaming + async: async generators → producer thread + iterator

**Language-forced** at the streaming layer, **deferred** for full
effect-system integration.

Python `streamify` returns an `AsyncIterator` by default;
`async_streaming=False` produces a sync iterator via an internal
queue. Everything composes with `asyncio`.

dspy4s `Streamify.streamify` is **sync-only**: a producer thread
pushes `StreamEvent`s onto a `LinkedBlockingQueue`, the consumer
reads through a `ClosableIterator`. The architectural decision was
deliberate — sufficient for inference, avoids committing to an
effect system before a real consumer needs one. `arun` exists as a
`Future` for non-streaming async, but the streaming path doesn't
plug into it yet.

Per-chunk granularity also differs: dspy4s emits one chunk per
field at the value boundary (JSON), at the close-tag boundary
(XML), or via a holdback window (Chat). Concatenated content
matches Python; per-chunk shape doesn't. See
[STREAMING_POSTPONED.md](../STREAMING_POSTPONED.md) for the per-token
refactor that would close the gap, and
[PORT_MAP §4](PORT_MAP.md#4-behavioral-deltas) for the other
documented streaming deltas (completion-chunk emission,
`allowReuse` default).

## 6. Error handling: exceptions → `Either[DspyError, A]` at boundaries

**Convention.**

Python raises across the whole stack. Callers either let the
exception propagate or wrap with `try/except`.

dspy4s returns `Either[DspyError, A]` from public `.run` boundaries:

```scala
val result: Either[DspyError, Prediction[O]] = predict.run(input)
```

with a structured error ADT:

- `ValidationError` — signature / input shape problems
- `ParseError` — adapter parse failures with field context
- `ConfigurationError` — missing LM / adapter / setting
- `NotFoundError` — missing field / resource
- `RuntimeError` — unexpected provider failures

Internally things still throw at deep enough layers; the boundary is
checked. The typed layer additionally elevates adapter-parse
failures to the `Predict.run` boundary instead of surfacing them
via lazy field access — a typed decode failure is a `Left`, not a
`Throwable` at first field read.

## 7. Save / load: pickle → typed dump/restore

**Convention.**

Python `module.save()` / `module.load()` use `cloudpickle`. The
artifact is opaque Python bytes.

dspy4s defines `SignatureLayout.dumpState: Map[String, Any]` and
`SignatureLayout.fromState(state)`, with a similar pattern per
program. The artifact format is JSON-friendly, dspy4s-native, and
**deliberately not** binary-compatible with Python pickle. The
non-compat is tracked as an explicit non-goal in
[PORT_SCOPE.md](PORT_SCOPE.md).

This means: a model trained in Python DSPy cannot be loaded into
dspy4s and vice-versa. Training pipelines that need both runtimes
have to export to a portable format at the integration boundary.

## 8. Utility namespace: `dspy/utils/*` → folded into consumers

**Convention.**

Python has a catch-all `dspy/utils/` package (callbacks, caching,
parallelizer, usage tracker, etc.). dspy4s folded each one next to
its primary consumer:

| Python | dspy4s |
|---|---|
| `utils/callback.py` | `core/contracts/Callbacks.scala` (because every module depends on it) |
| `utils/caching.py` | `lm/runtime/CacheRuntime.scala` (LM-specific) |
| `utils/parallelizer.py` | `programs/runtime/ParallelExecutor.scala` (lives with `Parallel` / `BestOfN` / `Evaluate`) |
| `utils/usage_tracker.py` | inside `lm/runtime/ManagedLanguageModel.scala` (per-call accumulator) |

The convention is **no catch-all utility namespace**. Each helper
lives in the module that owns its lifecycle, which makes ownership
unambiguous and removes the temptation to dump unrelated helpers
into the same package.

## 9. Decorator pattern: decorators → wrapper objects

**Language-forced.**

Python uses decorators (`@cached`, `@retry`, etc.) to layer behavior
onto LM calls.

Scala has no decorator equivalent that composes well with typed
signatures. Behavior layers are explicit wrapper objects that
decorate a `LanguageModel` and conform to the same trait:

- `ManagedLanguageModel` wraps a base LM with cache + retry + usage
  + history.
- `StreamingLanguageModelWrapper` wraps a streaming LM with the
  stream-pump + per-call queue + callback bridging.

The trait-implements-the-same-trait pattern is the Scala-shaped
expression of "decorate without losing the type". Composition is
ordinary function composition.

## 10. Tool-calling fallback for malformed args

**Convention.**

When the LM returns tool-call `arguments` that aren't a JSON object,
Python keeps the raw string under the `arguments` key. dspy4s wraps
the raw value as `Map("input" -> raw)` for uniformity with the
non-streaming path's `ProviderResponseParser.parseArgs`. Documented
in [PORT_MAP §4](PORT_MAP.md#4-behavioral-deltas).

## 11. `Aggregation.majority` normalizer

**Convention.**

Python's `majority(...)` defaults to the full `dspy.evaluate.normalize_text`
function (lowercase, strip punctuation, remove articles, NFD-strip,
collapse whitespace).

dspy4s defaults to a minimal trim-and-blank-check normalizer to
avoid `Aggregation` pulling in a hard dependency on `evaluate`'s
normalization. Pass the full normalizer explicitly for Python
parity. Documented in [PORT_MAP §2a](PORT_MAP.md#2a-programs-per-file-port-status-vs-python-predict).

## 12. `MultiChainComparison` invocation shape

**Convention.**

Python's `MultiChainComparison.__call__(attempts, **inputs)` mixes the `attempts` parameter with input kwargs.
dspy4s models the dual input faithfully as a bespoke call object, `MultiChainCall[I]` (the typed base input `I`
plus the candidate `attempts`), so `MultiChainComparison[I, O]` is a `Module[MultiChainCall[I], …]` and the real
work flows through the wrapped `apply` (callbacks/trace), not a side method. The `compare(input, attempts)`
convenience builds the call. (Earlier this was an untyped `runWithAttempts` that side-stepped the wrapping.)

## 13. Recent: typed/dynamic split inside `programs/`

**Scala-added.** Recent refactor (six-step series in May 2026).

`programs/` now has an internal architectural distinction Python
doesn't have:

- `runtime/PredictEngine` — the shared execute body
  (`private[dspy4s]`).
- `contracts/Module` — the generic program base `Module[I, O]`; its `final apply`
  does the module-level callback + trace wrapping over an abstract `forward`.
  `DynamicModule` is the untyped-spine alias (`Module[ProgramCall, DynamicPrediction]`).
- `DynamicPredict` — erased predict, extends `DynamicModule`.
- `Predict[I, O]` — typed predict, a `Module[TypedCall[I], Prediction[O]]`; a
  *sibling* of `DynamicPredict` over `PredictEngine` (not a wrapper).

`ChainOfThought` is itself a `Module` that composes an inner typed `Predict`.
The typed programs are a Scala-native addition that doesn't change the
underlying call flow; the engine is the single home for the
adapter/LM/callback dance that Python has spread across
`Predict.__call__` and its helpers.

**Every program is now typed.** Beyond `Predict`/`ChainOfThought`, the agents (`ReAct` / `CodeAct` /
`ProgramOfThought`), `MultiChainComparison`, and `BestOfN` / `Refine` are all `Module[TypedCall[I], …]` —
`DynamicPredict` is the only program left on the untyped spine (it's the substrate the others build inner
predicts from). Three pieces made this clean:
- **`OutputAugmentation`** (`dspy4s.typed`) — the shared `WithField[O, Name, T]` + `PrependField` typeclass that
  output-augmenting programs use to prepend `reasoning` / `rationale` (idempotent, cast-free, always a named tuple).
- **Typed `Signature.fromString`** — a `transparent inline` macro that parses a literal DSL at compile time into
  `NamedTuple` I/O, so the string DSL is a *typed* surface; `fromStringDynamic` is the runtime (`Record`) version.
- **`Streamable[P]`** (`dspy4s.streaming`) — `Streamify` takes any program through this typeclass, so the typed
  agents stream without needing an untyped `DynamicModule` twin.

The mutation helpers on `SignatureLayout` (`append`, `prepend`,
`insert`, `delete`, `withFields`, `withUpdatedField*`,
`updateField`) are `private[dspy4s]`. Composite programs use them
internally to augment a base layout with extra fields (e.g.
`ChainOfThought` prepending a `reasoning` field). User code goes
through the typed `Signature` surface instead. Python has no
equivalent boundary because there's no separate typed surface to
funnel users toward.

## 14. Deferred areas (parity gaps, not design choices)

These are differences because work hasn't landed yet, tracked
separately:

- **Retriever stack** (`Retrieve`, embedders, `KNNFewShot`) — not
  ported.
- **Advanced optimizers** (`GEPA`, `MIPROv2`, `SIMBA`, `GRPO`,
  `AvatarOptimizer`) — not ported.
- **Non-OpenAI providers** (Anthropic, Ollama, LiteLLM) — only
  OpenAI implemented; the `LanguageModel` trait is
  provider-agnostic.
- **Sandboxed `CodeInterpreter`** (Deno + Pyodide bridge) — the
  contract trait exists; only a plain `python3 -c "..."` subprocess
  implementation ships. `ProgramOfThought` and `CodeAct` are
  scaffolded against the contract; `RLM` depends on the sandbox
  and is therefore also deferred.
- **`TwoStepAdapter`** — not yet ported.
- **Multimodal reliability matrix** — out of scope for v1.
- **Per-token streaming chunks** — see §5; tracked in
  [STREAMING_POSTPONED.md](../STREAMING_POSTPONED.md).

The non-architectural gaps live in [PORT_BACKLOG.md](PORT_BACKLOG.md);
the streaming-specific ones in [STREAMING_POSTPONED.md](../STREAMING_POSTPONED.md);
the per-symbol deltas in [PORT_MAP.md](PORT_MAP.md).

## What this means in practice

If you're porting a Python DSPy script to dspy4s, the friction will
cluster in two places:

1. **Defining signatures.** You'll pick one of the six factories
   instead of writing a class. The trait-spec macro (`Signature.of[T]`)
   feels closest to Python; the function-type macro
   (`Signature.fromType[F]`) is the most compact.
2. **Reading prediction outputs.** Decide upfront whether you want
   the typed path (`Predict[I, O]` + `prediction.output.toxic`) or
   the dynamic path (`DynamicPredict` + `prediction.value("toxic")`).
   Mixing them works but adds friction; pick one per program.

Everything else — settings, callbacks, evaluators, optimizers,
streaming consumers — translates with mechanical renames. The
companion doc [PORT_SIMILARITIES.md](PORT_SIMILARITIES.md) lists
the patterns that transfer 1:1.
