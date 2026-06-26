# Refactor plan: composite primitives (steps 1–5)

**Branch:** `refactor/composite-primitives`
**Status:** draft / plan only (no implementation yet)
**Scope:** behavior-preserving extraction of the shared primitives hiding inside the composite
program modules. Steps 1–5 of a longer arc.

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
Layer 3  Environment      Effect.step(action) => observation     ← step 6 (NOT in this branch)
Layer 4  Combinators      augment | loop | selectBest | feedback ← step 6 (NOT in this branch)
Layer 5  Runtime services requireString / decodePrepended / truncateOnOverflow / isolatedAttempt  ← steps 2–4
```

Two goals converge on the same seam:

1. **De-duplication.** ReAct / CodeAct / RLM / ProgramOfThought are one loop written four times;
   BestOfN / Refine are one select-best loop written twice; CoT / ReAct / CodeAct / MCC share an
   identical "decode-and-prepend" body four times.
2. **A general agent (pi).** The policy-vs-control-vs-effect seam that de-duplicates the library is the
   same seam a pi-style harness needs (reuse the pure policy, swap the loop/environment).

### Effect-system forward-compatibility (Kyo / ZIO / Cats Effect)

The effect systems plug in at Layer 3/4 (step 6), not here. The discipline for steps 1–5:

- **Keep everything in `Either[DspyError, A]`.** Do not introduce `F[_]` in this branch.
- **The signature algebra (step 1) and `decodePrepended` (step 3) are pure** (`layout => layout` and
  validation). They never become effectful, so they are the permanently stable base under any effect
  system. Build them first and lean on them.
- **`truncateOnOverflow` (step 3) and `isolatedAttempt` (step 4) carry the effectful seam.** Write them
  so the eventual monad swap is mechanical: the effectful work is a single trailing closure
  (`run: String => Either[DspyError, A]`, `body: RuntimeContext ?=> A`), and the only `Either`-specific
  branch is context-overflow detection — isolate that behind a predicate so an `F`-with-typed-errors can
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

## Step 1 — Signature algebra (covers A, B)

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
      * outputs — the extractor produces them). */
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

**Test:** new `SignatureOpsSuite` (core) for the laws; existing composite suites cover the migrations.
**Risk:** very low (pure, total). **Effect note:** permanently pure — no `F[_]` ever.

## Step 2 — `requireString` (covers C)

**Where:** add to `DynamicValues` in `modules/core/.../contracts` (already the home of `recordGet`).

```scala
/** Read a required String field from a record with structured errors — the shared body of the
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
**Risk:** low. **Effect note:** pure validation — stays `Either`.

## Step 3 — `decodePrepended` (covers D, E; subsumes C)

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
**Effect note:** pure validation — stays `Either` even under `F[_]`.

## Step 4 — `isolatedAttempt` + `propagateAttempt` (covers G)

**Where:** `RuntimeEnvironment` (core) — it already owns `withContext`/`appendTrace`/`appendHistory`.

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

## Step 5 — `truncateOnOverflow` (covers F)

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

The `run` closure is "insert the rendered trajectory at the trajectory key and apply the predict" — the one
genuinely per-caller bit. `render` is `ReAct.renderTrajectory` / `CodeAct.renderTrajectory`.
**Test:** new `TruncateOnOverflowSuite` (programs) with a stub `run` that raises CWE for the first k calls,
asserting drop-oldest order and exhaustion behavior; existing `ReActSuite` / `CodeActSuite` cover the
migrations.
**Risk:** medium (the overflow logic is subtle; isolating it in one tested place is the point).
**Effect note:** `run: String => Either[DspyError, A]` is the trailing effectful seam → later `String => F[A]`;
the only `Either`-specific branch is the CWE match — isolate it behind a `isOverflow: E => Boolean` predicate
when the `F[_]` work lands.

---

## Out of scope for this branch (named so we don't drift)

- **Step 6: the `Effect` interface + `loop`/`select`/`feedback` combinators.** This is where Kyo/ZIO/CE
  plug in and where ReAct/CodeAct/RLM/ProgramOfThought collapse to one loop. Designed separately, after
  1–5 prove the seams.
- **Full `Refine = selectBest + hint` unification.** `BestOfN.selectBest` currently has no inter-attempt
  hook (Refine generates advice between attempts and swaps the adapter per attempt). Forcing selectBest to
  serve both right before the combinator redesign would over-fit it to two callers. **Recommendation:** in
  this branch, Refine and BestOfN share only `isolatedAttempt`/`propagateAttempt` (step 4); the deeper
  unification waits for step 6's `feedback` combinator. (Step 5 here is `truncateOnOverflow`, not the Refine
  unification — the original numbering folded these together; this is the honest split.)
- **ProgramOfThought / RLM loop migration.** They share the loop+execute shape but have distinct
  result-classification (PoT regenerate-on-error; RLM continue/SUBMIT/llm_query). Unify under step 6 once
  the `Effect` model is validated against all four agentic loops; do not force them now.

## Sequencing & verification

Order = smallest blast radius first; each step is an independent, behavior-preserving commit:

1. Step 1 (signature algebra) — pure, gates the rest.
2. Step 2 (`requireString`) — pure.
3. Step 3 (`decodePrepended`) — subsumes step 2's helper at the call sites; biggest single cleanup.
4. Step 4 (`isolatedAttempt`) — runtime plumbing.
5. Step 5 (`truncateOnOverflow`) — quarantines the overflow logic.

Per step: `sbt <module>/test` for the touched module(s) plus the affected composite suites. Before merge:
full `sbt test`. Each commit must be a pure refactor — green suite before and after, no message/contract
changes except where step 2's caveat forces a synchronized assertion update.

## Expected outcome

Net deletion across `ChainOfThought`, `ReAct`, `CodeAct`, `MultiChainComparison`, `BestOfN`, `Refine`:
four copies of extract-string, four of product-output-error, four of decode-and-prepend, three of
truncate-on-overflow, and two of isolated-attempt collapse to one each. The agentic loop bodies shrink to
"build the step signature via the algebra + drive `truncateOnOverflow`", which is the shape step 6 lifts
into a real combinator.
