# Refactor plan: composite primitives (steps 1–5)

**Branch:** `refactor/composite-primitives`
**Status:** steps 1–5 implemented on the branch (full `sbt test` green); step 6 started — 6.1 (`bestOf`
reducer) and 6.2 (`id`/`>>>`/`parallel`) landed. The authoritative step-6 contract is
[algebra-2-program-composition.md](algebra-2-program-composition.md); the pre-grill notes lower in this file
are superseded where they disagree with that spec.
**Scope:** behavior-preserving extraction of the shared primitives hiding inside the composite
program modules. Steps 1–5 of a longer arc.

## Status (implemented)

Steps 1–5 are implemented on this branch, each a behavior-preserving commit with a focused unit suite; the
existing composite suites guard the migrations. Full `sbt test` across all 10 modules is green (111 test
runs, 0 failed) under `-Werror -Wunused:all -Wsafe-init -Wshadow:all`.

| Step | Commit | Primitive | Duplication removed |
|---|---|---|---|
| 1 | `0c4e299` | `SignatureOps` (`prependOutput` / `appendInput` / `replaceOutputs`) | A, B |
| 2 | `217f2a4` | `DynamicValues.requireString` | C |
| 3 | `d52c45f` | `OutputAugmentation.decodePrepended` (+ `productOutputRequired`) | C call sites, D, E |
| 4 | `eb12e2b` | `RuntimeEnvironment.isolatedAttempt` / `propagateAttempt` | G |
| 5 | `4b1b6e2` | `TrajectoryTruncation.truncateOnOverflow` | F |

Net `+430 / −206` over 15 Scala files; ~197 of the insertions are new test coverage, so production code
shrank net while the duplication was removed.

**As-implemented deltas from the step sketches below (all behavior-preserving):**

- `prependOutput` returns `SignatureLayout` (total: `insert(0) == prepend` for an output field), not
  `Either`; `ChainOfThought.augmentLayout` wraps it in `Right(...)` to keep its signature, so the
  ReAct/CodeAct call sites are untouched. The ReAct/CodeAct extractor signatures were also migrated to
  `appendInput`, so the composites make no direct low-level mutator calls.
- Step 2's `requireString` labels were chosen so the missing-field messages stay byte-identical; only the
  non-String mismatch prose is unified.
- Step 3 unifies the fieldless-output error prose (label-based), an untested edge case (a string-DSL
  signature used with a composite). `decodePrepended` ships the opening-String case only, shaped and named
  as the general `Thought`-form so the generalization stays additive.

**Step 6 (in progress):** 6.1 (`Refine`↔`BestOfN` unification via the shared `AttemptSelection.bestOf`
reducer) and 6.2 (`id`/`>>>`/`parallel` in `Compose.scala`) are landed. Remaining: 6.3 `agentLoop`
(ReAct/CodeAct/RLM) + `retryUntil` (ProgramOfThought), then `augment`/`mode`, with the kyo-compat CIO
substrate migration as a separate non-blocking phase. The authoritative contract is
[algebra-2-program-composition.md](algebra-2-program-composition.md).

## Why

Reading every composite under `modules/programs/.../dspy4s/programs/` side by side, the module zoo is
built from a tiny set of repeated parts plus an orchestration shape. The same code is physically copied
across files. This plan extracts the duplicated parts into named, tested primitives without changing any
observable behavior.

This is deliberately the *pre-work* for the larger vision:

```
Layer 0  Atom            PredictEngine                     (exists)
Layer 1  Signature algebra  prependOutput / appendInput / replaceOutputs   ← step 1
Layer 2  Policy           Predict over a transformed signature   (exists, will be lifted later)
Layer 3  Environment      Effect.step(action) => observation     ← step 6 (substrate: kyo-compat CIO; NOT in this branch)
Layer 4  Combinators      augment (Thought-shaped) | loop | mode-middleware (subsumes select/feedback) ← step 6 (NOT in this branch)
Layer 5  Runtime services requireString / decodePrepended / truncateOnOverflow / isolatedAttempt  ← steps 2–4
```

Two goals converge on the same seam:

1. **De-duplication.** ReAct / CodeAct / RLM / ProgramOfThought are one loop written four times;
   BestOfN / Refine are one select-best loop written twice; CoT / ReAct / CodeAct / MCC share an
   identical "decode-and-prepend" body four times.
2. **A general agent (pi).** The policy-vs-control-vs-effect seam that de-duplicates the library is the
   same seam a pi-style harness needs (reuse the pure policy, swap the loop/environment).

### Effect-system forward-compatibility (Kyo / ZIO / Cats Effect)

The effect systems plug in at Layer 3/4 (step 6), not here. The substrate for that seam has been
evaluated: **kyo-compat** (see [Step 6 substrate](#step-6-substrate-kyo-compat-evaluated-not-yet-adopted)
below). It does not change anything in steps 1–5. The discipline for steps 1–5:

- **Keep everything in `Either[DspyError, A]`.** Do not introduce `F[_]` in this branch.
- **The signature algebra (step 1) and `decodePrepended` (step 3) are pure** (`layout => layout` and
  validation). They never become effectful, so they are the permanently stable base under any effect
  system. Build them first and lean on them.
- **`truncateOnOverflow` (step 3) and `isolatedAttempt` (step 4) carry the effectful seam.** Write them
  so the eventual monad swap is mechanical: the effectful work is a single trailing closure
  (`run: String => Either[DspyError, A]`, `body: RuntimeContext ?=> A`), and the only `Either`-specific
  branch is context-overflow detection; isolate that behind a predicate so an `F`-with-typed-errors can
  supply its own later.

## Confirmed duplication (code-truth)

| # | Pattern | Sites (file:line) | Notes |
|---|---|---|---|
| A | idempotent **prepend output field** | `ChainOfThought.scala:160` (`augmentLayout`, the de-facto shared one), reused by `ReAct.scala:131`, `CodeAct.scala:147`; MCC rolls its own at `MultiChainComparison.scala:87` | all = "insert field at output-cohort head unless name already present" |
| B | **add trajectory input + replace outputs** | `ReAct.scala:76` (`reactSignature`), `CodeAct.scala:95` (`codeActSignature`); extractor variants `ReAct.scala:109`, `CodeAct.scala:127` | hand-rolled `withFields(inputFields ++ Vector(...))` |
| C | **extract required String field** | `ChainOfThought.scala:135`, `ReAct.scala:285`, `CodeAct.scala:292`, `MultiChainComparison.scala:159` | identical bar field name + error prose |
| D | **"product output required" error** | `ChainOfThought.scala:128`, `ReAct.scala:296`, `CodeAct.scala:303`, `MultiChainComparison.scala:170` | `ValidationError`, module-specific message |
| E | **decode base output + prepend** | `ChainOfThought.scala:116`, `ReAct.scala:177`, `CodeAct.scala:191`, `MultiChainComparison.scala:126` | the for-comp that ties C + D + the `prepend` given |
| F | **truncate-trajectory-on-overflow** | `ReAct.scala:230` (`reactWithTruncation`), `ReAct.scala:250` (`extractWithTruncation`), `CodeAct.scala:222` (`extractWithTruncation`) | near-identical tail-recursion |
| G | **isolated attempt context + winner propagation** | `BestOfN.scala:80` (`selectBest`, isolate `:90`, propagate `:118`), `Refine.scala:78` (isolate `:102`, propagate `:153`) | `base.copy(trace=∅, history=∅)` + `withContext` capture |

## Module graph (placement constraints)

```
core            (no deps)        ← signature algebra, requireString, isolatedAttempt
typed  → core                    ← decodePrepended (needs OutputAugmentation + Shape + core errors)
lm     → core
adapters → core, lm
programs → core, lm, adapters, typed   ← truncateOnOverflow (near its only callers)
```

Tests: **munit**, one suite per module under `src/test/scala/dspy4s/<pkg>/`. Per-composite regression
suites already exist (`ChainOfThoughtSuite`, `ReActSuite`, `CodeActSuite`, `MultiChainComparisonSuite`,
`TypedBestOfNSuite`, `RefinePerModuleAdviceSuite`, `ProgramOfThoughtSuite`) and are the safety net; each
step also adds a focused unit suite for the extracted primitive.

---

## Step 1: Signature algebra (covers A, B)

**Where:** `modules/core/src/main/scala/dspy4s/core/contracts/SignatureOps.scala`, `private[dspy4s]`
(consistent with the existing policy: user code mutates at the typed `Signature` surface, not the layout).
The low-level `append`/`prepend`/`insert`/`withFields` mutators stay private; this layer gives them names,
idempotence, and laws.

```scala
private[dspy4s] object SignatureOps:
  extension (layout: SignatureLayout)
    /** Prepend an output field at the output-cohort head, unless one with the same name exists. Idempotent.
      * Exactly today's ChainOfThought.augmentLayout, generalized off the "reasoning" literal. */
    def prependOutput(field: FieldSpec): Either[DspyError, SignatureLayout] =
      if layout.outputFields.exists(_.name == field.name) then Right(layout)
      else layout.insert(index = 0, field = field)

    /** Append an input field, unless one with the same name exists. Idempotent. */
    def appendInput(field: FieldSpec): SignatureLayout =
      if layout.inputFields.exists(_.name == field.name) then layout
      else layout.append(field)

    /** Keep the inputs, replace all output fields with `fields` (loop-step signatures drop the base
      * outputs; the extractor produces them). */
    def replaceOutputs(fields: Vector[FieldSpec]): SignatureLayout =
      layout.withFields(layout.inputFields ++ fields)
```

**Laws (unit-tested):** `prependOutput` idempotent on name; `appendInput` idempotent on name;
`replaceOutputs` preserves `inputFields`; all preserve the `SignatureLayout` invariants (unique,
identifier-shaped names).

**Migrations (no behavior change):**
- `ChainOfThought.augmentLayout(l)` becomes `l.prependOutput(ChainOfThought.reasoningField)` (keep
  `augmentLayout` as the thin shared seam so `ReAct`/`CodeAct` call sites are untouched).
- `ReAct.reactSignature` / `CodeAct.codeActSignature` use `appendInput(trajectory)` + `replaceOutputs(...)`.
- `MultiChainComparison.augmentedSignatureLayout` uses `appendInput` per attempt + `prependOutput(rationale)`.

**Test:** `SignatureOpsSuite` (core) example tests, plus `SignatureOpsLawSuite` (ScalaCheck) for the
algebra's laws (L1–L6); see [algebra.md](algebra.md). Existing composite suites cover the migrations.
**Risk:** very low (pure, total). **Effect note:** permanently pure; no `F[_]` ever.

## Step 2: `requireString` (covers C)

**Where:** add to `DynamicValues` in `modules/core/.../contracts` (already the home of `recordGet`).

```scala
/** Read a required String field from a record with structured errors; the shared body of the
  * extractReasoning / extractRationale helpers. */
def requireString(record: DynamicValue.Record, field: String, label: String): Either[DspyError, String] =
  recordGet(record, field) match
    case Some(DynamicValue.Primitive(PrimitiveValue.String(s))) => Right(s)
    case Some(other) => Left(ValidationError(s"$label field '$field' must be a String, got: $other"))
    case None        => Left(NotFoundError("prediction_field",
                          s"Required field '$field' is missing from the $label prediction"))
```

**Migrations:** the four `extractReasoning`/`extractRationale` privates delegate to this (or are deleted in
step 3, which subsumes them).
**Caveat to verify:** the four current messages differ slightly in prose. Check the suites assert on error
*type* (`ValidationError`/`NotFoundError`), not exact strings, before changing the wording. If any suite
pins prose, either keep that wording via `label` or update the assertion in the same commit.
**Risk:** low. **Effect note:** pure validation; stays `Either`.

## Step 3: `decodePrepended` (covers D, E; subsumes C)

**Where:** `dspy4s.typed.OutputAugmentation` (it already owns the `PrependField` given and can see core's
`Shape`, `DynamicValues`, errors).

```scala
/** The shared "decode the base output and prepend a String field" body used by CoT / ReAct / CodeAct / MCC.
  * Reads `fieldName` as a String, decodes the base `O` via `shape`, prepends it, and maps the
  * fieldless-output case (string-DSL signatures) to a structured error. */
def decodePrepended[O, Name <: String & Singleton, Out](
    raw: DynamicValue.Record,
    shape: Shape[O],
    fieldName: Name,
    label: String,
    signatureName: String
)(using prepend: PrependField.Aux[Name, String, O, Out]): Either[DspyError, Out] =
  for
    value     <- DynamicValues.requireString(raw, fieldName, label)
    baseOut   <- shape.decode(raw)
    augmented <- prepend.prepend(value, baseOut)
                   .toRight(productOutputRequired(label, signatureName, baseOut))
  yield augmented
```

`productOutputRequired(label, signatureName, baseOut)` is the shared form of the four `unsupportedOutputShape`
privates (D).

**Migrations:**
- `ChainOfThought` augmented-shape `decode` → `OutputAugmentation.decodePrepended(raw, signature.outputShape, "reasoning", "ChainOfThought", signature.name)`.
- `ReAct.forward` / `CodeAct.forward` decode trio → one `decodePrepended(...)` call.
- `MultiChainComparison.forward` decode trio → `decodePrepended(raw.values, ..., "rationale", "MultiChainComparison", ...)`.

This is the highest-leverage cleanup: it removes C, D, and E in one move (the `extractReasoning`,
`unsupportedOutputShape`, and the for-comprehension all collapse into the call).
**Test:** extend `OutputAugmentationSuite` (typed) to cover the decode path incl. the fieldless-output
error; existing composite suites cover the migrations.
**Risk:** low-medium (touches the typed decode path of four modules; the per-composite suites guard it).
**Effect note:** pure validation; stays `Either` even under `F[_]`.

### Design ceiling: `Thought`-shaped augmentation (from kyo-ai)

`decodePrepended` above is the *behavior-preserving* dedup of what the four sites do today: prepend at the
**output-cohort head**, a **String** field, with **no post-decode hook**. That is exactly the degenerate
case of kyo-ai's [`Thought`](kyo-ai-comparison.md), whose general form is worth shaping toward so this
helper is not a dead-end:

- **Position.** `opening` (before the result; conditions the answer) or `closing` (after the result; a
  self-check). Today's four sites are all opening. dspy4s cannot express a closing self-check at all; the
  general shape unlocks it for free.
- **Arbitrary typed field**, decoded via its own `Shape`, not pulled as a String by name. `requireString`
  (step 2) exists only because the field is hard-coded to String; under a typed field it is just
  `Shape.decode`, so step 2's helper is the degenerate case and is **retired over time**, not load-bearing.
- **Optional post-decode hook** (kyo-ai's `process`): verify, record a metric, or drive a follow-up.

**What this means for the implementation now (no scope creep):** ship only the opening-String path in this
branch (it is the behavior-preserving dedup), but **name and structure the primitive as the general one**
(position, field type, and hook as parameters with trivial defaults), so generalization is purely
additive later. `MultiChainComparison`'s `rationale` is already a second opening-String instance; a closing
self-check would be the first new case.

**Where we must diverge from kyo-ai:** the augmentation field is learnable surface, so the primitive must
stay introspectable/replaceable by `Predictors` (optimizer-addressable). kyo-ai's `Thought` has no
optimizer dimension; ours does. See [Step 6 design lessons](#step-6-design-lessons-from-kyo-ai).

## Step 4: `isolatedAttempt` + `propagateAttempt` (covers G)

**Where:** `RuntimeEnvironment` (core); it already owns `withContext`/`appendTrace`/`appendHistory`.

```scala
/** Run `body` under an isolated trace/history overlay (optionally swapping the adapter), returning the
  * body's result plus the trace/history it accumulated. Shared by the BestOfN / Refine attempt loops. */
def isolatedAttempt[A](base: RuntimeContext, adapter: Option[AdapterRef] = None)
                      (body: RuntimeContext ?=> A): (A, Vector[TraceEntry], Vector[HistoryEntry]) =
  val isolated = base.copy(trace = Vector.empty, history = Vector.empty,
                           adapter = adapter.orElse(base.adapter))
  withContext(isolated) {
    given RuntimeContext = current
    val r = body
    (r, current.trace, current.history)
  }

/** Replay a winning attempt's captured trace/history into the caller's context. */
def propagateAttempt(trace: Vector[TraceEntry], history: Vector[HistoryEntry]): Unit =
  trace.foreach(appendTrace); history.foreach(appendHistory)
```

**Migrations:** `BestOfN.selectBest` (lines 90–96, 118–119) and `Refine.forward` (102–115, 153–154) call
these. Refine passes its per-attempt `HintInjectingAdapter` as the `adapter` argument.
**Test:** new `IsolatedAttemptSuite` (core) asserting isolation (inner trace/history do not leak unless
propagated) and that `propagateAttempt` replays in order; existing `TypedBestOfNSuite` /
`RefinePerModuleAdviceSuite` cover the migrations.
**Risk:** low-medium (mutable-state plumbing; keep capture order identical). **Effect note:** the
`body: RuntimeContext ?=> A` closure is the seam that later becomes `F[A]`; keep it the single trailing arg.

## Step 5: `truncateOnOverflow` (covers F)

**Where:** `modules/programs/.../runtime/` (near its only callers; depends only on core's
`ContextWindowExceededError`).

```scala
/** Render the view, run it, and on a context-window overflow drop the OLDEST entry and retry, up to
  * `maxAttempts` total. Returns the last attempt's result paired with the view it ran against. Generic
  * over entry type `S` and result `A`. */
@tailrec
def truncateOnOverflow[S, A](view: Vector[S], maxAttempts: Int)(render: Vector[S] => String)
                            (run: String => Either[DspyError, A]): (Either[DspyError, A], Vector[S]) =
  run(render(view)) match
    case Left(_: ContextWindowExceededError) if maxAttempts > 1 && view.nonEmpty =>
      truncateOnOverflow(view.drop(1), maxAttempts - 1)(render)(run)
    case result => (result, view)
```

**Migrations (semantics preserved by how each caller maps the result):**
- `ReAct.reactWithTruncation`: `truncateOnOverflow(view, 3)(ReAct.renderTrajectory)(run)` then map
  `(Right(p), v) => (Some(p), v)`; `(Left(_: CWE), v) => (None, v)` (break the loop);
  `(Left(e), _) => Left(e)` (propagate). Matches lines 239–243.
- `ReAct.extractWithTruncation` / `CodeAct.extractWithTruncation`: take the result Either directly
  (persistent overflow stays a `Left`). Matches `case other => other` at 261 / 233.

The `run` closure is "insert the rendered trajectory at the trajectory key and apply the predict", the one
genuinely per-caller bit. `render` is `ReAct.renderTrajectory` / `CodeAct.renderTrajectory`.
**Test:** new `TruncateOnOverflowSuite` (programs) with a stub `run` that raises CWE for the first k calls,
asserting drop-oldest order and exhaustion behavior; existing `ReActSuite` / `CodeActSuite` cover the
migrations.
**Risk:** medium (the overflow logic is subtle; isolating it in one tested place is the point).
**Effect note:** `run: String => Either[DspyError, A]` is the trailing effectful seam → later `String => F[A]`;
the only `Either`-specific branch is the CWE match; isolate it behind a `isOverflow: E => Boolean` predicate
when the `F[_]` work lands.

---

## Step 6 (in progress on this branch; tracked in the algebra-2 spec)

Step 6 is now underway on this branch. The authoritative contract is
[algebra-2-program-composition.md](algebra-2-program-composition.md); the items below are kept for history,
annotated with their current (post-grill, post-6.1) status.

- **Step 6: the `Effect` interface + the agentic `loop` + the control middleware.** Where Kyo/ZIO/CE plug
  in. **Corrected:** only `ReAct` / `CodeAct` / `RLM` collapse to one `agentLoop`; `ProgramOfThought` does
  NOT (it is a `retryUntil`, see below). The substrate candidate is identified (kyo-compat); the design
  shape is informed by kyo-ai. See the [substrate section](#step-6-substrate-kyo-compat-evaluated-not-yet-adopted).
- **Full `Refine` ↔ `BestOfN` unification. DONE (6.1, commit `96c9072`).** Both reduce to the shared
  `AttemptSelection.bestOf`. **Corrected:** the grill decided against "one mode-style middleware" — they are
  **two combinators sharing one reducer** (`selectBest` = independent / no feedback; `feedback` = sequential,
  the advice→adapter hook), because `selectBest` is permutation-invariant and `feedback` is order-dependent.
  Pinned by `AttemptSelectionLawSuite`.
- **ProgramOfThought / RLM loop migration.** **Corrected:** these do NOT share one loop. `RLM` joins
  `ReAct` / `CodeAct` under `agentLoop` (continue/SUBMIT classify). `ProgramOfThought` is a separate
  `retryUntil` (regenerate-until-execution-succeeds) composed via `>>>` with its answer step — not `feedback`
  and not the agent loop (code-truth; see the algebra-2 spec's correction). Both land in 6.3.

## Step 6 design lessons (from kyo-ai)

Studying kyo-ai surfaced design wins recorded here so step 6 starts shaped right (full comparison:
[kyo-ai-comparison.md](kyo-ai-comparison.md)). Our composites fall into three kyo-ai-shaped buckets, and
for two of them kyo-ai's general abstraction is cleaner than treating each as a bespoke module:

| our composites | kyo-ai general form | our step-6 target |
|---|---|---|
| ChainOfThought, MultiChainComparison, ReAct/CodeAct CoT-extractor | `Thought` (augment the output schema) | the `Thought`-shaped augmentation (see [step 3 ceiling](#design-ceiling-thought-shaped-augmentation-from-kyo-ai)) |
| BestOfN, Refine | `Mode` (run the generation N times, synthesize) | one mode-style middleware |
| ReAct, CodeAct, RLM, ProgramOfThought | the eval loop | the `Effect` + `loop` |

**Lesson 1: control layer is a `Mode`-style middleware over a generation *value*, not separate
`select`/`feedback` combinators.** kyo-ai's `Mode` is `[A] => (gen) => gen'`, where the wrapped generation
is a value run zero/one/many times; BestOfN ("run N, keep best"), Refine ("run N with advice between"),
model-switching, and pre/post all collapse to that one shape, pipelined in registration order. Our
`selectBest(runAttempt: Int => …)` is a lower-altitude API. **Design step 6's control layer as middleware
over a re-runnable generation value**; `isolatedAttempt`, `propagateAttempt`, and `truncateOnOverflow`
(steps 4–5) are the low-level pieces *beneath* it, not the user-facing surface. In the `Either` world the
generation value is a `() => Either[…]` / `RuntimeContext ?=> Either[…]` thunk; it gets cleaner once the
CIO substrate lands (a referentially-transparent `CIO` value is re-runnable for free).

**Lesson 2: a uniform `Enablement`-style binder, but optimizer-addressable.** kyo-ai's `Enablement` is one
trait that tools, prompts, thoughts, and modes all implement, so `enable(tool, prompt, thought, mode)`
attaches any mix uniformly; our composites hand-wire each capability. Adopt the uniform-binder shape as
step 6's composition surface. **The dspy4s-specific requirement kyo-ai does not have:** attaching a
capability can change what the optimizer tunes, so the binder must interplay with the `Predictors`
typeclass (read/replace of learnable predicts). This is the one place copying kyo-ai directly would be
wrong.

**Lesson 3: `Thought`-shaped augmentation.** Covered as the
[step 3 ceiling](#design-ceiling-thought-shaped-augmentation-from-kyo-ai): position (opening/closing),
typed field via `Shape`, optional post-decode hook; ship opening-String now, generalize additively.

### Guardrails: what NOT to copy from kyo-ai

- **Do not weld the effect system into the core.** kyo-ai is `LLM extends ArrowEffect`. Our bet (pure
  `Either` core, effects only at the CIO seam) is deliberate and is what keeps the modules optimizable and
  the multi-effect story open. Keep it.
- **Do not move errors onto the effect channel.** kyo-ai uses `Abort[AIException]`; our errors-as-values
  (`Either[DspyError, A]`) is exactly what makes `CIO[Either[DspyError, A]]` clean. Keep it.
- **Must add beyond kyo-ai: optimizer-addressability.** Every borrowed abstraction (Thought-augmentation,
  mode-middleware, enablement-binder) must remain introspectable/replaceable by `Predictors`. kyo-ai has
  no optimizer, so its versions carry no such constraint; ours must.

**Lesser (only if dspy4s grows stateful):** kyo-ai's `forget` / `fresh` / `snapshot` / `recover` are a more
principled state-isolation vocabulary than a single `isolatedAttempt`. Not a steps-1–5 change; the model to
reach for if the agent direction adds conversation state.

## Step 6 substrate: kyo-compat (evaluated, not yet adopted)

The effect seam (Layer 3/4) needs a substrate that lets dspy4s ship one source tree to Kyo, ZIO, and
Cats Effect. The leading candidate is **kyo-compat** (`io.getkyo` `kyo-compat-*`, github getkyo/kyo,
`kyo-compat/` on `main`). This section records the evaluation so step 6 starts from a decision, not a
blank page. It changes nothing in steps 1–5.

### What it is

A "write once, ship to 6 backends" layer. Library code is written against `kyo.compat.*` (`CIO[+A]`,
`CStream[+A]`, `CFiber`, `CPromise`, `CChannel`, `CAtomic*`, `CLatch`, `CMeter`, `CLocal`, plus
`CIO.zip/race/foreach/timeout/acquireReleaseWith/async`). Properties that matter here:

- **Compile-time, not runtime, polymorphism.** `CIO[+A]` is a per-backend opaque alias
  (`A < (Abort[Throwable] & Async)` on Kyo, `ZIO[Any, Throwable, A]` on ZIO, `cats.effect.IO[A]` on CE).
  Every method is an `inline def` that lowers at the call site to the backend's primitive. No
  typeclass/`F[_]` dispatch, no adapter, zero overhead. The backend is chosen by which jar is on the
  classpath; cross-published via an sbt-projectmatrix plugin.
- **One portable error lane: `Throwable`.** `CIO` carries no typed `E`. The library's own guidance:
  keep domain errors as values in the success type (`CIO[Result[E, A]]`); reserve the failure lane for
  genuine runtime/transport failures. Backend-specific typed recovery is reached via `.lower`.
- **Effect/concurrency/stream layer only.** It is codec-agnostic (says nothing about `Schema`). `CStream`
  gives a portable stream type but not an HTTP client or SSE parser.

### Why it fits dspy4s

The error model is the alignment, not the friction. dspy4s already treats errors as **values**
(`Either[DspyError, A]`) and keeps modules pure with the runtime owning bookkeeping. That is exactly
kyo-compat's recommended shape. So the marriage is literal:

- Pure layer unchanged: `In => Either[DspyError, A]`.
- Effectful layer: `CIO[Either[DspyError, A]]`; `CIO` carries real runtime failures (HTTP, timeout),
  the `Either` carries domain errors as values.
- `ContextWindowExceededError` matching (the `truncateOnOverflow` branch) stays pure `Either`, untouched.

Codec stays **zio-blocks `Schema`**: kyo-compat is codec-agnostic, so adopting it does not pull in
kyo-schema. This is why it beats building on kyo-ai for the cross-effect goal: kyo-ai is Kyo-only and
forces kyo-schema, whereas kyo-compat gives ZIO + CE + Kyo while keeping the existing codec.

Where things land:

| dspy4s layer | substrate |
|---|---|
| signature algebra, decode, Predict logic, **optimizers** (Bootstrap/MIPRO/GEPA) | pure `Either` (no CIO) |
| LM HTTP call, retry/timeout | `CIO` (lift an async client via `CIO.fromCompletionStage`/`fromScalaFuture`) |
| agent loop / step-6 combinators (loop/select/feedback) | `CIO` |
| parallel execution (`Parallel`, BestOfN fan-out) | `CIO.foreach`/`zip`/`CMeter` (bounded concurrency built in) |
| async streaming (currently postponed) | `CStream` |
| `RuntimeContext` propagation | `CLocal` (Kyo `Local` / ZIO `FiberRef` / CE `IOLocal`) |

Consistency check with the purity principle: effects live in the runtime, not the modules. Adopting `CIO`
at the runtime layer keeps every module a pure `In => Either[DspyError, A]`.

### Relationship to kyo-ai

kyo-ai (github getkyo/kyo, `kyo-ai/` on the `kyo-ai` branch) stays the **reference design** for the
runtime/harness primitives (`Enablement`, `Thought`, `Mode`, `Tool`, the native-function-calling eval
loop), to be reimplemented on `CIO` + zio-blocks. It is not a dependency. kyo-compat is the substrate;
kyo-ai is the blueprint for what to build on it. Full feature-by-feature comparison:
[kyo-ai-comparison.md](kyo-ai-comparison.md).

### Costs and risks

- **Young, single-vendor dependency** (`main`, may be pre-release). Mitigate by wrapping it behind a thin
  internal alias (still inline, no overhead) and scoping the dependency to only the effectful modules.
- **Build restructuring.** The effectful modules (`lm`, the runtime/agent-loop, `streaming`) become
  projectMatrix rows × backend × platform. Pure modules (`core`, `typed`, optimizer logic) stay a normal
  build. The matrix is contained to the seam but is real new machinery (sbt-projectmatrix + the plugin +
  per-backend source roots).
- **Compile-time backend selection → N artifacts** (`dspy4s-zio`, `dspy4s-ce`, …); no single all-backends
  jar, no runtime switching. Fine for a library.
- **HTTP layer must become `CIO`-based.** Today `LanguageModel.call` is sync/`Future`;
  `CIO.fromCompletionStage`/`fromScalaFuture` bridge an existing async client, bounding the change to one
  module.
- **Future binding has no cancellation** (timeout leaves orphaned work). Acceptable: ZIO/CE/Kyo cancel
  natively; Future is the deps-free dev anchor.

### De-risking spike (do before committing the effectful layer)

Settle the architecture with evidence, not the README. Scope:

1. A throwaway module written against `kyo.compat.*` containing one thin slice: an LM-style call
   (`CIO.fromCompletionStage` over a stub/echo HTTP client) plus a minimal `gen` + single-tool loop, all
   returning `CIO[Either[DspyError, A]]`.
2. Cross-compile it to **ce + zio + future** via the `compatLibrary` projectmatrix plugin.
3. Run the same source on all three (`sbt <id>Ce/test <id>Zio/test <id>Future/test`).

Success criteria:

- The `CIO[Either[DspyError, A]]` ergonomics are acceptable in a real `for`-comprehension loop (no
  pervasive `.lower`).
- The projectmatrix build produces the per-backend cells and they share one source root.
- `ContextWindowExceededError`-style domain branching reads cleanly as `Either` matching inside `CIO`.

Estimated: a few hours. Outcome decides whether step 6 builds on kyo-compat or falls back to a hand-rolled
seam.

## Sequencing & verification

Order = smallest blast radius first; each step is an independent, behavior-preserving commit:

1. Step 1 (signature algebra): pure, gates the rest.
2. Step 2 (`requireString`): pure.
3. Step 3 (`decodePrepended`): subsumes step 2's helper at the call sites; biggest single cleanup.
4. Step 4 (`isolatedAttempt`): runtime plumbing.
5. Step 5 (`truncateOnOverflow`): quarantines the overflow logic.

Per step: `sbt <module>/test` for the touched module(s) plus the affected composite suites. Before merge:
full `sbt test`. Each commit must be a pure refactor; green suite before and after, no message/contract
changes except where step 2's caveat forces a synchronized assertion update.

## Expected outcome

Net deletion across `ChainOfThought`, `ReAct`, `CodeAct`, `MultiChainComparison`, `BestOfN`, `Refine`:
four copies of extract-string, four of product-output-error, four of decode-and-prepend, three of
truncate-on-overflow, and two of isolated-attempt collapse to one each. The agentic loop bodies shrink to
"build the step signature via the algebra + drive `truncateOnOverflow`", which is the shape step 6 lifts
into a real combinator.
