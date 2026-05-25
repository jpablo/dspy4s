# dspy4s ⇄ Python DSPy — Similarities

> **Companion docs:**
> [PORT_DIFFERENCES.md](PORT_DIFFERENCES.md) covers where the architecture
> diverged. [PORT_MAP.md](PORT_MAP.md) is the per-symbol mapping +
> behavioral-delta ledger. [PORT_LANGUAGE_NOTES.md](PORT_LANGUAGE_NOTES.md)
> covers Python→Scala idiom mechanics.
>
> This doc is **the high-level "what stayed the same"** story for someone
> coming from Python DSPy. If you sketch both architectures on a
> whiteboard, the boxes and arrows match — what changed lives in the
> companion doc.

## Why so much is the same

Python DSPy's core insight is language-agnostic:

- A **signature** declares the shape of a single LM call.
- An **adapter** turns `(signature, demos, inputs)` into messages and
  parses the response back.
- A **module** composes one or more LM calls with control flow (tool
  loops, voting, refinement).
- An **optimizer** introspects a module's demos and signatures and
  produces a better version.

Most of the work of porting was finding the Scala-shaped expression of
that insight. The boundaries didn't move.

## Module decomposition

```
Python `dspy/...`                dspy4s `modules/...`
─────────────────────            ─────────────────────
primitives/  +  signatures/  →   core/
clients/                     →   lm/
adapters/                    →   adapters/
predict/                     →   programs/
evaluate/                    →   evaluate/
teleprompt/                  →   optimize/
streaming/                   →   streaming/
utils/                       →   folded into consumers (see PORT_MAP §3)
```

Only the names changed (see [PORT_MAP §1](PORT_MAP.md#1-module-renames)).
The dependency graph has the same shape — each layer depends only on
the ones below it. There is no Python module that has no dspy4s home,
modulo Tier 1+ deferrals (`retrievers`, `datasets`, `propose`).

The split between `core/contracts` and `core/runtime` inside dspy4s
mirrors Python's `primitives/` vs `signatures/` split for code-search
purposes — same logical division, collapsed at the build-graph level.

## Data spine

A single LM call walks the same path in both:

```
Signature ──→ ProgramCall ──→ Adapter.format ──→ LM.call ──→ Adapter.parse ──→ Prediction
```

The vocabulary is identical: `Signature`, `Example`, `Prediction`,
`Completions`, `LM`, `Adapter`. The types carrying the data are
analogues:

| Python | dspy4s |
|---|---|
| `Signature` (class) | `SignatureLayout` (erased) + `Signature[I, O]` (typed wrapper) |
| `dspy.Example` | `dspy4s.core.contracts.Example` |
| `dspy.Prediction` | `DynamicPrediction` (erased) + `Prediction[O]` (typed) |
| `dspy.Completions` | `CompletionData` |
| `dspy.LM` / `BaseLM` | `LanguageModel` |
| `dspy.Adapter` | `Adapter` |
| `dspy.Tool` | `ToolFunction` |

The pair-of-types pattern (erased + typed) is the one substantive
addition; the erased side mirrors Python 1:1, and the typed side sits
on top as a Scala-native layer. See `PORT_DIFFERENCES.md` §2.

## Adapter pipeline

`ChatAdapter`, `JSONAdapter`, `XMLAdapter` all exist on both sides
with the same purpose and the same framing:

- `ChatAdapter` uses the `[[ ## field_name ## ]]` marker form (full
  parity — the earlier `prefix: value` form was a delta that has
  since been closed; see [PORT_MAP §4](PORT_MAP.md#4-behavioral-deltas)).
- `JSONAdapter` emits a JSON-typed system instruction and parses
  top-level JSON object responses.
- `XMLAdapter` produces `<outputs>...</outputs>` framing.

Each adapter implements the same two-method contract — `format` and
`parse` — with the same input shape (`signature, demos, inputs`) and
the same output shape (`messages` / `Map[String, Any]`). Streaming
state machines (`*StreamingState`) are a dspy4s structural detail but
implement the same per-field routing semantics as Python's holdback
logic.

## Settings + callbacks

The contextual-execution model is the same:

- A **scoped settings stack**: nested overrides without global
  mutation (`dspy.context(lm=...)` in Python,
  `RuntimeEnvironment.with(...)` in dspy4s).
- **Thread-local propagation**: settings flow into the worker
  thread; both stacks copy at scope-enter.
- **Callback channels** at the same four boundaries: module start/end,
  LM start/end, adapter start/end, tool start/end. dspy4s adds an
  `evaluate` channel for completeness.

The cross-thread copying (Python `ContextVar` + threadpool wrapping,
dspy4s `ContextPropagation`) is mechanically different but
behaviorally identical.

## Composite programs

The full set of Python `predict/` modules is ported with the same
behavior:

| Python | dspy4s | Notes |
|---|---|---|
| `Predict` | `Predict[I, O]` + `DynamicPredict` | typed + erased pair |
| `ChainOfThought` | `ChainOfThought[I, O]` | prepends `reasoning` output field and delegates through typed `Predict` |
| `ReAct` | `ReAct` | tool loop with `next_thought` / `next_tool_name` / `next_tool_args` |
| `BestOfN` | `BestOfN` | run-and-score-N times |
| `Refine` | `Refine` | iterative correction loop |
| `Parallel` | `Parallel` | fan-out with bounded concurrency |
| `Aggregation.majority` | `Aggregation.majority` | mode-based voting |
| `MultiChainComparison` | `MultiChainComparison` | compare N attempts via a meta-call |
| `ProgramOfThought` | `ProgramOfThought` | generate → execute → regenerate loop |
| `CodeAct` | `CodeAct` | code-action variant of ReAct |

`ChainOfThought`'s "augment the signature with a reasoning field"
trick, `ReAct`'s tool-call loop, `BestOfN`'s scoring threshold,
`Refine`'s feedback iteration — all the control-flow patterns
transfer 1:1.

## Cache + retry + history + usage

The LM runtime preserves Python's semantics line-for-line:

- **Cache key** is computed from request fields with the same
  deterministic hashing approach. Cache hits skip both the network
  call and the history append, matching Python's behavior.
- **Retry policy** is identical: bounded retries with exponential
  backoff on transient failures, fast-fail on validation errors.
- **History** is a thread-local list of `HistoryEntry`s appended on
  each successful (non-cached) LM call. The shape matches Python's
  `lm.history`.
- **Usage tracking** sums per-call token counts into a thread-local
  accumulator. `dspy.settings.usage_tracker` becomes
  `ManagedLanguageModel`'s per-call usage hook.

The cache backends — in-memory LRU and disk — are direct ports.

## Optimizer pattern

The bootstrap-and-search compile loop is identical:

1. Start with `LabeledFewShot` to sample k labeled demos.
2. `BootstrapFewShot` runs a teacher program against a trainset,
   keeps the traces that pass a metric, marks them
   `augmented = true`, and attaches them as demos.
3. `BootstrapFewShotWithRandomSearch` generates N candidate programs
   by varying the bootstrap seed, evaluates each via the `Evaluate`
   runner, keeps the best.

Both `BootstrapFewShot` parameters (`max_bootstrapped_demos`,
`max_labeled_demos`, `max_rounds`, `max_errors`, `seed`) and
`BootstrapFewShotWithRandomSearch` parameters (`num_candidate_programs`,
`stop_at_score`) port directly. Random-search seed semantics — ±3,
±2, ±1, then ≥ 0 — match Python exactly.

The one structural divergence is **how** the optimizer reads a
program's parameters: Python walks `__dict__`, dspy4s uses a
`PredictOps[P]` typeclass. The optimizer code on top is the same.

## Evaluate runner

- `Evaluate(devset, metric, num_threads, max_errors, failure_score,
  ...)(program)` is a direct port.
- Parallel execution uses `ParallelExecutor` (Python:
  `parallelizer.py`) with the same thread-isolation, max-errors
  cancellation, and timeout semantics.
- The built-in metrics — `ExactMatch`, `ContainsMatch`, `F1Score`,
  `AnswerMatch`, `PassageMatch`, plus a `FunctionMetric` adapter —
  match Python's `evaluate/metrics.py` behavior.
- The text-normalization utility (lowercase + strip punctuation +
  remove articles + NFD-strip combining marks + collapse whitespace)
  ports byte-for-byte from `dspy.evaluate.evaluate.normalize_text`.
- Result persistence (`saveAsJson` / `saveAsCsv`) uses the same
  flat-row schema with `example_` / `pred_` collision prefixes.

## Streaming v1

The minimum-viable streaming surface ports:

- A `StreamingLanguageModel` extension of `LanguageModel` that yields
  `LmChunk`s (Python: `LM.stream(...)` returning a generator of
  delta chunks).
- A `Streamify.streamify(program)(inputs)` entry point that wraps a
  program and yields a stream of typed events.
- `StatusMessageProvider` for tool-call status messages, with the
  same default behavior (tool start/end).
- `StatusStreamingCallback` bridging the callback channel into the
  stream.
- Per-field `StreamListener` chunk routing with the same field-name
  filter semantics.

The deltas — async-iterator vs producer-thread, per-token vs
per-field chunk granularity, `allow_reuse` default — are in
`PORT_DIFFERENCES.md` §5 and the Python parity gap in
[STREAMING_POSTPONED.md](../STREAMING_POSTPONED.md).

## Error contracts

Both projects distinguish:

- **Parse errors** — adapter parse failures with field context.
- **Validation errors** — signature or input shape problems
  detected before the LM call.
- **Configuration errors** — missing LM / adapter / setting in the
  active context.
- **Runtime errors** — unexpected LM provider failures.

The error classes have analogous names and the same triggering
conditions; Python raises, dspy4s returns `Either` at boundaries
(see `PORT_DIFFERENCES.md` §6).

## Save / restore conceptual model

`Signature.dump_state` / `from_state` exist in both, with the same
JSON-friendly `Map[String, Any]` shape. Only the binary-pickle path
diverges (dspy4s doesn't ship pickle compat; see
`PORT_DIFFERENCES.md` §7).

## What this means in practice

If you've used Python DSPy, the dspy4s API will feel familiar:

- The names you reach for first (`Predict`, `ChainOfThought`,
  `Evaluate`, `BootstrapFewShot`) exist with the same purpose.
- The composition patterns transfer — "wrap a Predict in
  ChainOfThought", "wrap a program in BestOfN", "fan out with
  Parallel" — all the same shape.
- The contextual-execution model (`with dspy.context(lm=...)`
  becomes `RuntimeEnvironment.with(...)`) feels the same once you
  know the rename.
- The optimizer interaction (give me a program and a trainset, get
  back a better program) is identical at the call site.

What you have to learn separately is the **Scala-shaped surface for
defining signatures** (six factories instead of one class form) and
the **typed I/O layer** (which Python doesn't have at all). Both are
covered in [TYPED_SIGNATURES_GUIDE.md](../TYPED_SIGNATURES_GUIDE.md).
