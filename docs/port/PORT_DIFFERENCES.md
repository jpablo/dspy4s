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
   analogue (the I/O layer is the headline example).
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
| `Signature.fromString("q -> a")` | the string DSL — a **compile-time macro** that parses the literal into `NamedTuple` I/O |
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

## 2. I/O layer (Scala-native addition)

**Scala-added.** This is the single largest architectural addition.

Python `Prediction.value("toxic")` returns `Any` (well, its
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
  `Either[DspyError, Prediction[O]]`. `prediction.output.toxic` has
  static type `Boolean`.

The `Shape[A]` typeclass has three implementations
(the product shape from `ZioSchemaCodec.derivedFromZioSchema` for
case-class I/O via `zio-blocks-schema`, `TupleShape` for named-tuple
I/O from the macros, `MapShape` for the string DSL).
The representation pattern repeats at two levels: `RawPrediction` is the raw execution evidence retained by every
`Prediction[O]` on `.raw`, while prediction has record-valued and domain-valued facades, `DynamicPredict` and
`Predict[I, O]`. The two facades are **siblings** — thin `Module`s
over the shared `PredictEngine`, not wrapper-and-wrapped. The domain-valued
programs are themselves `Module[I, O]` values,
so the runtime stack sees them like any other program; `ChainOfThought`
is a signature augmentation that *composes* an inner `Predict[I, O]`
(its `forward` delegates to it).

Dynamic programs retain record-valued semantics, but now share the same `Prediction[O]` module result as domain-valued
programs: use `.output` for the record and `.raw` for the engine envelope. Adapter authors still never see the
layer, and new code can opt into I/O surface-by-surface. See
[TYPED_SIGNATURES.md](../TYPED_SIGNATURES.md) for the design rationale.

## 3. Module parameter discovery: `__dict__` walking → typeclass

**Language-forced.**

Python `BaseModule.named_parameters()` walks `self.__dict__`,
recurses through nested modules, yields anything that looks like a
`Parameter`. Optimizers consume that iterator.

Scala has no `__dict__`. The optimizer side uses **`OptimizableLeaf[P]`** for
one leaf and **`OptimizableStructure[P]`** for composite structure. Each leaf exposes
read-only metadata separately from the exact optimizable parameters:

```scala
trait OptimizableLeaf[P]:
  def get(program: P): OptimizableParameters
  def metadata(program: P): PredictorKey
  def set(program: P, parameters: OptimizableParameters): P
```

`OptimizableParameters` contains instructions, demos, and module config;
signature structure, names, runtimes, schemas, LMs, and tools cannot be
replaced through the optimizer lens. Mirror derivation builds a
`OptimizableStructure[P]` evidence for composites. The trade-off is explicit by design.

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
(XML), or after the hold of a tail that can still become a marker
(Chat). Concatenated content
matches Python; per-chunk shape doesn't. See
[STREAMING_POSTPONED.md](../STREAMING_POSTPONED.md) for the per-token
refactor that would close the gap, and
[PORT_MAP §4](PORT_MAP.md#4-behavioral-deltas) for the other
documented streaming deltas (completion-chunk emission).

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
- `ConfigurationError`: missing LM or setting, or an adapter of the wrong type
- `NotFoundError` — missing field / resource
- `RuntimeError` — unexpected provider failures

Internally things still throw at deep enough layers; the boundary is
checked. The layer additionally elevates adapter-parse
failures to the `Predict.apply` boundary instead of surfacing them
via lazy field access — a decode failure is a `Left`, not a
`Throwable` at first field read.

An empty reply has one form in the port: an `LmOutput` with an empty text. Upstream keeps a null and an empty text
apart, and a blank text stays as it is. This gives three differences:

- `llm_query` of `RLM` gives an error for each form, and `llm_query_batched` puts the text
  `[ERROR] The sub-LM returned no outputs.` in its list for each form. Upstream gives an error only for a null, and each
  of the two functions gives `""` for an empty text. The port has one text for an empty and a null content, so it cannot
  tell them apart.
- A chat content of only white space gives the error of an empty reply, and the fallback. Upstream parses that text. The
  parse of its chat format fails, unless each output has a default. Upstream then succeeds with one call.
- The raw response of the error is the text of the output. Upstream has the text of the whole output record.

The parse step of a `Predict` call runs the decode of the output type on each output of the reply. Thus a value that
the decode rejects is a `ParseError` with the raw response, as an upstream `AdapterParseError`, and it gets the JSON
fallback. This rule has one difference. A reply with provider tool calls gives a null to each output that its text does
not give. Upstream keeps that `None` for each annotation. A Scala `String` output has no null, so the decode rejects
the reply, and the call fails after the fallback. An `Option[String]` output takes the null. When the decode rejects the
values of such a reply, the decode runs again on the provider tool calls with each default and a null for each other
output (upstream `except AdapterParseError: value = {}`), so a reply that passes this second decode gets no second call.

## 7. Save / load: Python state/program modes → state restore

**Convention.**

Current Python DSPy has two modes. With `save_program=False`, `module.save()` /
`module.load()` persist named-parameter state as JSON or pickle. A `Predict`
state currently contains its signature, demos, traces, training data, and bound
LM. Only `save_program=True` serializes the whole architecture with
`cloudpickle`.

dspy4s `ProgramPersistence` serializes each leaf's `OptimizableParameters`
(instructions, demos, config) into clean JSON keyed by leaf ordinal.
It applies that state to a fresh program while preserving architecture and
execution resources. The format is dspy4s-native and **deliberately not**
compatible with Python state/pickle artifacts or the former layout-bearing
predictor-state format. The non-compat is tracked as an explicit non-goal in
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
| `utils/usage_tracker.py` | `core/runtime/UsageScope.scala` (the usage scopes and `UsageTracker`); the user facade `UsageTracking` is in `lm/runtime/ManagedLanguageModel.scala` |

The convention is **no catch-all utility namespace**. Each helper
lives in the module that owns its lifecycle, which makes ownership
unambiguous and removes the temptation to dump unrelated helpers
into the same package.

## 9. Decorator pattern: decorators → wrapper objects

**Language-forced.**

Python uses decorators (`@cached`, `@retry`, etc.) to layer behavior
onto LM calls.

Scala has no decorator equivalent that composes well with generic
signatures. Behavior layers are explicit wrapper objects that
decorate a `LanguageModel` and conform to the same trait:

- `ManagedLanguageModel` wraps a base LM with cache + retry + history.
  It records no usage and sends no LM callback events:
  `LanguageModel.callAndRecordUsage` is the one record point.
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
non-streaming path (`ToolCallAssembler.decodeArguments` in `ChatWire`). Documented
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
dspy4s models both result-determining values as `MultiChainInput[I]` (the base input plus candidate attempts)
inside the same `ProgramCall` envelope used by every module. Consequently `MultiChainComparison[I, O]` is a
`Module[MultiChainInput[I], …]`, and the real work flows through the wrapped `apply` (callbacks/trace), not a side
method. The `compare(input, attempts)` convenience builds the semantic input and envelope. (Earlier this was an
record-valued `runWithAttempts` that side-stepped the wrapping.)

## 13. Recent: domain/record split inside `programs/`

**Scala-added.** Recent refactor (six-step series in May 2026).

`programs/` now has an internal architectural distinction Python
doesn't have:

- `runtime/PredictEngine` — the shared execute body
  (`private[dspy4s]`).
- `contracts/Module` — the semantic program base `Module[I, O]`; `forward` returns `Prediction[O]` and its `final apply`
  adds module callbacks and tracing. `DynamicModule` specializes both semantic types to `DynamicValue.Record` and
  lifts its `RawPrediction` through `Prediction.dynamic`.
- `DynamicPredict` — record-valued predict, extends `DynamicModule`.
- `Predict[I, O]` — domain-valued predict, a `Module[I, O]`; a
  *sibling* of `DynamicPredict` over `PredictEngine` (not a wrapper).

`ChainOfThought` is itself a `Module` that composes an inner `Predict`.
The programs are a Scala-native addition that doesn't change the
underlying call flow; the engine is the single home for the
adapter/LM/callback dance that Python has spread across
`Predict.__call__` and its helpers.

**Every program now carries its domain and codomain.** Beyond `Predict`/`ChainOfThought`, the agents (`ReAct` / `RLM`),
`MultiChainComparison`, and `BestOfN` / `Refine` are all `Module[I, …]` —
`DynamicPredict` is the prediction leaf for record-valued programs: it executes runtime-built signatures while
`Predict` is its sibling over the shared engine. Some agent internals construct dynamic prediction passes, but
programs do not delegate through a universal `DynamicPredict` substrate. Three pieces made this clean:
- **`Signature.prependOutput`** (`dspy4s.signatures`), with the `WithField[O, Name, T]` type and the `PrependField`
  evidence of `OutputAugmentation`. The output-augmenting programs use it to prepend `reasoning` or `rationale`. The
  operation does not add a field that the output has, uses no cast, and always gives a named tuple. It changes the
  layout and the output shape in one call, as `Signature.appendInput` does for an input.
- **`Signature.fromString`** — a `transparent inline` macro that parses a literal DSL at compile time into
  `NamedTuple` I/O, so the string DSL is compiler-checked; `fromStringDynamic` is the runtime (`Record`) version.
- **`Streamable[P]`** (`dspy4s.streaming`) — `Streamify` takes any program through this typeclass, so
  `ReAct` streams without needing a record-valued `DynamicModule` twin.

The cohort mutation helpers on `SignatureLayout` (`withInputFields` and
`withOutputFields`) are `private[dspy4s]`. Composite programs use them
internally to augment a base layout with extra fields (e.g.
`ChainOfThought` prepending a `reasoning` field). User code goes
through the `Signature` API instead. Python has no
equivalent boundary because there's no separate surface to
funnel users toward.

## 14. Deferred areas (parity gaps, not design choices)

These are differences because work hasn't landed yet, tracked
separately. Several items previously listed here have since shipped —
the **retriever stack** (embedders, `KNN`, `KNNFewShot` — G-10),
**`GEPA`** (G-12) and **`MIPROv2`**, the **sandboxed `CodeInterpreter`**
(Deno + Pyodide) and **`RLM`** (G-20), and **`TwoStepAdapter`** (G-8).
What genuinely remains:

- **Advanced optimizers** (`SIMBA` — G-13, `GRPO` / `BootstrapFinetune`
  — G-16, `BetterTogether` — G-15, `BootstrapFewShotWithOptuna` — G-17)
  — not ported. (`AvatarOptimizer` is **won't-fix by design** — G-14.)
- **Non-OpenAI providers** (Anthropic, LiteLLM router) — the
  OpenAI-compatible / Ollama route shipped (G-22 part 1); the
  `LanguageModel` trait is provider-agnostic.
- **Multimodal reliability matrix** — out of scope for v1.
- **Per-token streaming chunks** — see §5; tracked in
  [STREAMING_POSTPONED.md](../STREAMING_POSTPONED.md).
- **A typed output that is a sealed trait with fields.** The schema shows a `oneOf` with the case name as a key, for
  example `{"Circle": {"radius": 1}}`. The decode rejects that form with "Expected a variant", in a list and as a direct
  field. A direct field also has the wire type `string`, so the prompt shows `str`. A case name alone gives "Missing
  field". A sealed trait with only cases that have no fields is not in this item.
- **A typed output that is a tuple.** The schema of `(Int, String)` is an array with `prefixItems`. The decode rejects
  `[1, "a"]` with "Expected a record". Only `{"_1": 1, "_2": "a"}` decodes, and the schema does not show that form.
- **A typed output that is an `Instant` or a `UUID`.** The schema is a string with the `format` `date-time` or `uuid`,
  and the wire type is `json`. The JSON adapter rejects the string with "Field could not be parsed as JSON". The decode
  alone also rejects it, with "Expected Instant" or "Expected UUID".
- **A `Schema.transform` of a record with an `Option` field, through `Shape.derived`.** The schema lets the model leave
  out the field, or give a null. The decode rejects each form: an absent field gives "Missing field", and a null or a
  value gives "Expected a variant". `Signature.derived` does not read such a schema. It derives the record of the class,
  and that form decodes.

The non-architectural gaps live in [PORT_BACKLOG.md](PORT_BACKLOG.md);
the streaming-specific ones in [STREAMING_POSTPONED.md](../STREAMING_POSTPONED.md);
the per-symbol deltas in [PORT_MAP.md](PORT_MAP.md).

## 15. `CodeAct` and `ProgramOfThought`: removed

**Convention.**

Python DSPy 3.4 still has `dspy.CodeAct` and `dspy.ProgramOfThought`.
Upstream deprecated the two modules in 3.4 and removes them in 3.5.
Upstream names `dspy.RLM` as the replacement.

dspy4s removed `CodeAct` and `ProgramOfThought` at the 3.4.0 target, so
that no more work goes into them. Use `RLM` for a program that
generates code and runs it.

## 16. Prompt text: the boundary rule of decision D3

**Convention.**

The adapters write the upstream prompt text, character for character, where the text is fixed or where the port can
compute it from its own field data: `TypeRef`, the enum values, the optional flag, the descriptions, and the
constraints. The port has no model of Python types, so a few places of a prompt differ by design:

- A structured type shows the type name of the port (`list`, `dict`, or the token of a string signature, such as
  `list[str]`) and the JSON schema of the port (the property of the field in the output schema of a typed
  signature). Upstream shows the Python annotation (`list[str]`, `Union[str, NoneType]`, the name of a model) and the
  pydantic schema.
- A field with enum values shows `str` and the note of a `Literal`. Upstream shows the `Literal` or the `Enum` type.
- An optional field shows the text of its element type. Upstream shows `Union[T, NoneType]`.
- The XML adapter asks for a list, dict, or model output as JSON text inside the tag (decision A6). Upstream asks
  for nested elements.
- The two-step adapter prints a list or dict value as JSON. Upstream prints the Python `repr`.
- A value of an input or a demo prints as upstream prints it: `True`, `False`, `None`, the Python form of a float,
  and JSON with the separators `", "` and `": "`.
- ReAct and ReActV2 write the arguments of a tool as JSON text. Upstream writes the Python text of a dict.
- After whitespace before a chat header, upstream `ChatAdapter.parse` keeps a part of the header in the value, because
  it cuts the rest of the line with the offset of the stripped line (`line[match.end():]`). The port cuts the rest of
  the stripped line, so the value is the text after the header.
- ReActV2 (experimental) types `next_thought` as a `str` field, so the output reminder has no type hint for it.
  Upstream types it as `dspy.Reasoning`, which the port does not have.
- The RLM action prompt has no text about the sub-agents of the sandbox `dspy` facade, because the port has no facade
  (upgrade item P8).
- The evidence request of a decision output (experimental) shows the evidence JSON schema in the form of the port.
  Upstream shows the pydantic form, with title-cased labels. A decision value prints with the field order of the
  port (`value`, `confidence`, ...); upstream prints `confidence` first.
- The decode of a Score output (experimental) gives its `level` as the name of the level, and a typed `Score[L]`
  has `level: Option[L]`, a case of the level enum. Upstream gives the zero-based level index. The probabilities of
  both are keyed by the level index.

## 17. Output values: one lenient rule for numbers and bools

**Convention.**

Upstream `parse_value` reads an output value with `json_repair`, then `ast.literal_eval`, then pydantic lax mode. The
port reads a number and a bool with one rule, `LenientValues`. The Chat, JSON, and XML adapters, the typed decode, and
the accessors of `RawPrediction` use this rule. Thus one text has one value on each path, and a lenient form needs no
second call of the model.

Each number text and each bool text that upstream reads for its type, the port reads with the same value:

| Type | The text forms |
|---|---|
| `int` | Digits with a sign, a `_` between digits (`1_000`), and a whole float text (`3.0`, `5.`, `1e3`), of any size. |
| `float` | Decimal forms with a sign (`.5`, `5.`, `1e3`), a `_` between digits, and `inf`, `infinity`, and `nan` in any case. `1e400` is an infinite float. |
| `bool` | `true`, `false`, `yes`, `no`, `on`, `off`, `t`, `f`, `y`, and `n` in any case, and a number or a number text with the value 1 or 0. |

The port also reads these forms, and upstream rejects them:

- A currency symbol (`$ € £ ¥`) and thousands commas: `$1,200` and `1,200`. A comma that does not separate the
  thousands makes the text no number, so a decimal comma (`3,5`) is an error, not `35`.
- The null texts of an optional field: `n/a`, `none`, and the empty text. Upstream reads only `None` and `null`.
- Spaces around a bool word: ` yes `.
- A whole float text out of the range of a 64-bit integer: `1e19`.

The port does not read these forms, and upstream reads them:

- The Python integer literals `0x10`, `0o7`, and `0b11`. Upstream reads them through `ast.literal_eval`. A model does
  not write them.
- A bool for an `int` or a `float` field. Upstream gives `1` or `1.0` for `true`, and `0` or `0.0` for `false`.
- A space between a sign and the digits: `- 5`.
- A `_` next to a decimal point: `1_.5` for a `float`.

A form that only Java reads (`3d`, `3f`, `0x1p3`) is no number on each path, as upstream.

An item of a list type of a string signature has the same rule: `list[int]`, `list[float]`, and `list[bool]` read each
item with `LenientValues`, and `list[str]` takes only a string. A tuple of any length, for example `tuple[int, ...]`, has
the rule of its item type. The port has no check for each other item type, for example `Optional[int]`, `dict`, or the
items of `tuple[int, str]`. Upstream validates each annotation.

## 18. Sandbox tools and ReActV2 names

**Convention.**

- The vendored `runner.js` differs from the upstream file in the tool wrappers. A tool with no declared parameters
  (`ToolFunction(...)`, `ToolFunction.of(...)`) has an unknown argument list, so its wrapper is `def name(**kwargs):`,
  as in the local interpreter. Upstream generates `def name():`, and that function rejects each argument. Each wrapper
  calls one shared helper, so an argument can have the name `json` or `run_sync`. Upstream puts the call in each
  wrapper, so such an argument hides the module or the function. A parameter can be optional (section 25). The port
  does not send a `default` for a parameter, so the runner has no such case.
- ReActV2 rejects an output with the name `next_thought`, `tool_calls`, or `tool_call_results` at construction,
  because the history event of a turn has these keys and the final outputs go over them. Upstream does not check
  these names.

## 19. The errors of the `TwoStepAdapter` extraction step

**Convention.**

The extraction step gives each `ParseError` the text "Failed to parse response from the original completion: ", and
the raw text of the error is the completion of the main LM. Two errors differ from upstream:

- An extraction reply with no outputs is a `ParseError` with that text. Upstream takes `parsed_result[0]` of an empty
  list, and that raises an `IndexError`.
- A `ParseError` of the model, for example a provider reply that is not JSON, also gets that text. Upstream wraps only
  an `AdapterParseError`, and a model call does not raise it.

The extraction step has no check of the output type of a typed signature: the check runs after the step, for the output
of the main call. Thus a value that the typed decode rejects is a `ParseError` with the completion of the main LM as its
raw text, with no JSON fallback on the extraction model and without the text of the wrapper. Upstream `parse_value`
rejects that value in the extraction step, so its JSON fallback comes first.

## 20. The trace limit, and the failure budget and the controls of `BestOfN` and `Refine`

**Convention.**

- `maxTraceSize` has the default and the rule of upstream `max_trace_size`: the default is 10000, the oldest entry
  drops, and 0 keeps no entry. The port records each module call. Upstream records only each predictor call. Thus a
  limit holds fewer predictor calls than upstream.
- `failCount` of `BestOfN` and `Refine` is the number of failures that the program can take, and the next failure
  gives a `Left`. Upstream checks `idx > fail_count`, which mixes the index of the attempt and the count. Upstream also
  lowers `self.fail_count` at each failure, so the lower value applies to all later calls of the same module.
- A call with no successful attempt gives a `Left` with the last error. Upstream returns `None`.
- The controls of an attempt are the temperature 1.0 and the rollout id of the attempt. They are in
  `RuntimeScope.lmDefaults`, for the extent of the attempt. The port has no copy of the LM. Upstream sets them on a copy
  (`lm.copy(rollout_id=rid, temperature=1.0)`, `mod.set_lm(lm_)`). `PredictEngine` reads them under the config of a
  predictor and under a per-call config, so they stay under these two values as in upstream. They also reach each
  predictor when a composite builds a new `ProgramCall`. A retry round of `BootstrapFewShot` uses the same field.
- A predictor with a bound LM keeps its LM and gets the controls. In `BestOfN` and `Refine`, upstream `set_lm` replaces
  each bound LM with a copy of one LM. In `BootstrapFewShot`, upstream sets the LM with `dspy.context(lm=lm)`, so a
  predictor with a bound LM gets no rollout. The port gives the controls to this predictor too, because a repeat of the
  same request adds nothing.
- Inside a nested attempt, the critic of `Refine` and a reward function have the controls of the outer attempt. Upstream
  gives them `dspy.settings.lm`, which does not have the controls.
- A System One model rejects the temperature of an attempt, so an attempt on this model gives a `Left`. In upstream the
  call also fails: `TypeSafe` has no `kwargs`, and `TypeSafe.copy` does not accept `temperature`.

## 21. Optimizer rules with no upstream form

**Convention.**

The optimizers follow the failure rule of upstream: a `Left` stops the compile where upstream raises. These rules differ
from upstream:

- A `maxBootstrappedDemos` of 0 gives a shuffled bootstrap the size 0. Upstream `randint(1, 0)` raises a `ValueError`.
- The current instruction of a leaf stays a candidate of COPRO and MIPROv2 that writes nothing, so a leaf with no
  instructions keeps `None`. A prompt shows its effective instructions. Upstream reads the default text and writes it
  back, which gives the same prompt.
- A blank proposal gives no candidate, and a blank rule text of InferRules keeps the instructions of the leaf. Upstream
  writes the blank text.
- The check of ReAnchor for a cache reads only the model of the context. Upstream reads the bound LM of each predictor,
  else `dspy.settings.lm`, and its `cache` config.

## 22. Evaluate rules with no upstream form

**Convention.**

The `evaluate` module follows `dspy/evaluate/`. These rules differ from upstream:

- A number or a bool answer reads as its text, and a list prediction reads as its JSON text. Upstream raises in
  `normalize_text`.
- The columns of `saveAsCsv` are the union of the keys of the rows, in first-seen order. Upstream takes the keys of the
  first row, and `csv.DictWriter` raises on another key.
- The table is plain text, with a column `<error>` when a result has an error. A metric cell has the form `%.3f`.
  Upstream prints a pandas table, and a metric cell has a check mark.
- `ExampleEvaluation.error` always holds the failure as `[code] message`, and there is no `provideTraceback`, because an
  `Either` has no stack trace. Upstream logs each error, and its `provide_traceback` adds the stack trace to the log.

## 23. Streaming rules with no upstream form

**Convention.**

The `streaming` module follows `dspy/streaming/`. These rules differ from upstream:

- A listener on a field that is not unique, or on an unknown field, gives a warning through `warningSink`. The port gives
  a warning because `streamify` gives a function and not an `Either`. For a field that is not unique, upstream raises a
  `ValueError`. For an unknown field, upstream also raises: its `ValueError` branch cannot run, so the error is a
  `TypeError`.
- The port streams the text of any field. Upstream also accepts any field, but it gives a field of a streamable
  `dspy.Type` to `parse_stream_chunk`. The port has no such hook.
- A direct `call` or `stream` of a model by user code does not stream, because only
  `LanguageModel.callAndRecordUsage` applies the sink. Upstream streams each call of an `LM`.
- The stream rules come from the adapter at the start of the stream, so a scope with another adapter inside a program
  still uses the first rules. Upstream reads `settings.adapter` for each chunk.
- A model chunk that holds a whole field gives a token to the listener. Upstream reads this chunk as `cache_hit`: the
  listener gets no token, and the stream keeps the prediction with `include_final_prediction_in_output_stream=False`.
- The stream of a raw provider with no finish reason fails with `LmStreamAssemblyError`, as in `ManagedLanguageModel`.
  The stream wrapper has this check because a raw provider can be the model of the wrapper. Upstream has this check in
  `checked_stream`, below each model.

## 24. GEPA rules with no upstream form

**Convention.**

The `gepa` module follows `dspy/teleprompt/gepa/`, `dspy/teleprompt/bootstrap_trace.py`, and the `gepa` package. These
rules differ from upstream:

- A score that is not finite counts as a metric failure: the example gets the failure score and no trajectory. The
  adapter drops each objective score that is not finite, and it keeps the finite ones. Upstream keeps a float NaN. The
  port saves the state as JSON, and JSON has no such number.
- With `addFormatFailureAsFeedback`, a parse failure that a program caught, in a run that passed, gives a record. The
  trace of the port holds an entry for that call. Upstream has no such entry, because it adds a failed entry only when
  the exception leaves the program.
- A parse failure with a partial result keeps a trajectory. Upstream computes `present / expected` on two lists
  (`bootstrap_trace.py`), so it raises a `TypeError` and drops that example. The port does not follow this defect.
- A fatal error in a worker of the executor gives each example of the batch the failure score, with a warning. In
  upstream, a `BaseException` of a worker goes to the caller of the evaluation, because `utils/parallelizer.py` catches
  only `Exception`.

## 25. MCP rules with no upstream form

**Convention.**

The `mcp` module follows `dspy/utils/mcp.py` and the schema functions of `dspy/adapters/types/tool.py`. These rules
differ from upstream:

- The `required` list of a tool is the list of the server. Upstream `format_as_litellm_function_call`
  (`tool.py:162-166`) marks each argument with no `default` as required, so it also marks an optional argument with no
  default.
- The port does not check the arguments on the client. Upstream `_validate_and_parse_args` (`tool.py:124-151`) checks
  each argument with `jsonschema` and parses it before the call. In the port, the server checks the arguments, and its
  error text goes to the model. A server with no check runs with the value of the model.
- A `$ref` with no definition and a recursive definition give `{}`, the schema of any value. Upstream
  `_resolve_json_schema_reference` (`tool.py:523-546`) raises `KeyError` and `RecursionError` at conversion. A `type`
  that is a list gives `Any`. Upstream `convert_input_schema_to_tool_args` (`tool.py:573`) raises `TypeError`.
- An explicit null `structuredContent` through the Java SDK gives the text fallback, because the record of the SDK has
  `null` for a null and for an absent field. Upstream returns `None` (`mcp.py:48-49`). `resultValue` gives the null when
  the client sends `Some(DynamicValue.Null)`.
- The error text of several text parts is JSON text, under the boundary rule of section 16. Upstream writes the Python
  list text (`mcp.py:46`).
- The Java client has a request timeout of 20 seconds by default (`McpClient.java:167`). It does not check structured
  content against the output schema by default, because `enableCallToolSchemaCaching` is false (`McpClient.java:201`,
  `McpAsyncClient.java:703`). The Python client has no default read timeout (`mcp/shared/session.py:284-288`), and it
  checks structured content (`mcp/client/session.py:412-441`). The caller owns the client.
- A part that is not text is its JSON object. Upstream keeps the pydantic object (`mcp.py:39`).
- In the sandbox of `RLM`, a tool with a JSON schema has a wrapper with the names of the schema, and the code can leave
  out an optional argument. A tool with an argument name that is not a Python name takes keyword arguments. Upstream
  `PythonInterpreter._extract_parameters` (`dspy/primitives/python_interpreter.py:461-475`) reads the Python signature
  of the tool. For an MCP tool the signature is `func(*args, **kwargs)`, so the upstream wrapper has the two parameters
  `args` and `kwargs` (`dspy/predict/rlm.py`, `_make_interpreter_tool`).

## 26. The tool specs of a predictor

**Python.** The tools of a call are an input field of the type `list[dspy.Tool]`. `Adapter._call_preprocess`
(`dspy/adapters/base.py:91-120`) reads that field. The request gets the tools, or the prompt shows the field. Upstream
has no other way to give tools to a predictor.

**Convention.**

The port has this input field (`TypeRef.tools`, which `ReActV2` uses), and `Predict` and `DynamicPredict` also have
`tools`, a list of `ToolSpec` values apart from the layout. A layout with a tool calls output has these rules:

- When native function calling is active, the request has the tool specs as provider tools, and the prompt hides a
  tools input.
- Else, the prompt shows the tool specs as one more input field `tools` at the end of the inputs, with the text of each
  tool (`ToolSpec.text`, the port form of `Tool.__str__`). The text has the form of a tools input of the layout.
- A layout with a tools input shows that input, and gets no second field. A layout with an other field with the name
  `tools` keeps that field, and the prompt does not show the tool specs. Upstream reads only the type of a field, so it
  has no such case.
- With native function calling on, a tool calls output with no tool specs and no tools input is a `ConfigurationError`
  from `format`. Upstream raises a `ValueError`. Upstream does this check before it reads the support of the model, so
  the port reads the flag only. Upstream has this check in `__call__`, so its `format` alone does not raise.
- With native function calling active, a tool calls output with no tool specs is a `ConfigurationError` also when the
  layout has a tools input. A tools input of the port holds only text, so it cannot become provider tools. Upstream
  makes the provider tools from the `Tool` values of that input. When native function calling is not active, the prompt
  shows such an input, and there is no error.
- A demo with no value for the input field `tools` is an incomplete demo when the prompt shows the field, as upstream
  for a demo with no value for a tools input. A replayed turn of a history does not show the tools.
- A layout with no tool calls output does not show the tool specs, because the model has no field for a call.
- `TwoStepAdapter` has native function calling off, so its prompt shows the tool specs of a layout with a tool calls
  output, as a `tools: ...` block of the user message, and it gives no error with no tools. The value is JSON text,
  where upstream writes the Python `repr` of a tools input.

## What this means in practice

If you're porting a Python DSPy script to dspy4s, the friction will
cluster in two places:

1. **Defining signatures.** You'll pick one of the six factories
   instead of writing a class. The trait-spec macro (`Signature.of[T]`)
   feels closest to Python; the function-type macro
   (`Signature.fromType[F]`) is the most compact.
2. **Reading prediction outputs.** Decide upfront whether you want
   the domain-valued path (`Predict[I, O]` + `prediction.output.toxic`) or
   the dynamic path (`DynamicPredict` + `prediction.value("toxic")`).
   Mixing them works but adds friction; pick one per program.

Everything else — settings, callbacks, evaluators, optimizers,
streaming consumers — translates with mechanical renames. The
companion doc [PORT_SIMILARITIES.md](PORT_SIMILARITIES.md) lists
the patterns that transfer 1:1.
