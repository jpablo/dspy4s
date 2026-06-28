# Algebra 2: program composition (step-6 spec)

**Status:** spec. Operations and laws hardened by a design grill (the five forks below are resolved); this
is what step 6 implements against. No pre-implementation spike is required.
**Date:** 2026-06-27
**Relation:** the sketch and the broader ADD context live in [algebra.md](algebra.md); the step-by-step
extraction work that precedes this is [composite-primitives.md](composite-primitives.md); the kyo-compat
substrate evaluation (the deferred phase) is in that file's step-6-substrate section.

This is the algebra of **programs** (composing modules), the layer above Algebra 1 (composing signatures).
Algebra 1 was pure, so its laws were specified directly. Algebra 2's observation runs an LLM, so the design
needed interrogation first; the resolutions are recorded here as the contract.

## Denotation and how effectful laws are tested

A program denotes a Kleisli arrow `I ⇝ O`: `run : Prog[I, O] => I => M[O]`, where `M` carries the LLM
effect. Because `run` is effectful and the model is nondeterministic, laws are never stated on concrete
model outputs. They are stated on **composition** and checked in whichever way is honest for each:

- **structural** (on the program tree the combinator builds; no LLM): Category, Mode monoid, the `Predictors`
  homomorphism;
- **mock-LM** (deterministic stub model): the augment round-trip;
- **distributional** (holds for any model output): `bestOf` monotonicity, `selectBest` permutation-invariance.

## Carrier (forks 1 and 5)

- **The unit stays `Module[TypedCall[I], Prediction[O]]`.** No parallel `Prog` type is introduced (this keeps
  the `Predictors` optimizer machinery working). `Prog[I, O]` below is denotational shorthand for that type.
- **`>>>` threads the plain typed value `O`,** not `Prediction[O]`. Controls (`config`, `traceEnabled`,
  `rolloutId`) ride in `TypedCall`; the `Prediction` envelope and the effect sit at the edges. Intermediate
  `Prediction.raw` (reasoning, completions, per-step usage) goes to the `RuntimeContext` trace, not onto the
  composite result. (Usage-merge onto the result is a deferrable, non-breaking enhancement: usage is a
  monoid, so accumulating it preserves every law.)
- **`M` is a concrete, build-swapped carrier, not a tagless `F[_]`.** Today `M = Either[DspyError, _]`
  (errors as values, synchronous; the async sibling is `Future[Either[DspyError, _]]`). The kyo-compat
  future is `CIO[Either[DspyError, _]]` (same two-level shape as `Future[Either]`), swapped at the
  build/seam level per kyo-compat's compile-time model. Combinators are written against the concrete carrier;
  the laws below are stated substrate-agnostically (they hold for any monad, plus an applicative for
  `parallel`), which is what makes the later swap a mechanical body-rewrite rather than a redesign.

## Operations

`Prog[I, O]` = `Module[TypedCall[I], Prediction[O]]`. Learnable parts are held as addressable immutable
fields (see Optimizer-addressability); fixed parts (`env.step`, `reward`, `critic`, `classify`) are closures.

```
predict(sig: Signature[I, O]) : Prog[I, O]                       // the atom: one LM round-trip (= Predict)
id[I]                          : Prog[I, I]                       // pure passthrough           [IMPLEMENTED 6.2]

a >>> b                        : (Prog[I, X], Prog[X, O]) => Prog[I, O]      // Category (sequential)  [IMPLEMENTED 6.2]
//   runs a, feeds a.output: X into a fresh TypedCall[X] inheriting the outer call's controls, runs b.
//   IMPLEMENTED as AndThen + the `>>>` extension; threads the plain value (the Prediction envelope of the
//   intermediate goes to the trace, not the result). p >>> id keeps p.output but resets .raw (carrier split).

parallel(a, b)                 : (Prog[I, A], Prog[I, B]) => Prog[I, (A, B)] // Applicative (independent)  [IMPLEMENTED 6.2]
//   IMPLEMENTED as Both + Compose.parallel. NOTE: this is NOT the existing `Parallel` class — that is a batch
//   executor over Vector[(DynamicModule, ProgramCall)] on a thread pool, a different abstraction. The applicative
//   `parallel(a, b)` runs two typed programs on the same input and tuples the outputs (sequential on Either;
//   concurrency is the later CIO concern). The raw merges both sub-predictions' records (second wins on collision).

augment[Name, T](field, position)(p) : Prog[I, O] => Prog[I, Out]           // Thought / CoT
//   position = opening (prepend, conditions the answer) | closing (append, self-check); opening shipped today
//   Out = OutputAugmentation.WithField[O, Name, T]; the String/opening/hook-less case is ChainOfThought

mode(m: Mode)(p)               : Prog[I, O] => Prog[I, O]         // Monoid middleware, NON-learnable only
//   model swap / temperature / retry / pre-post; m introduces no learnable predict of its own

bestOf(reward, threshold, failCount)(attempts) : M[Prediction[O]]            // shared reducer  [IMPLEMENTED 6.1]
//   keep the argmax-reward attempt, short-circuit at threshold, tolerate failCount failures;
//   each attempt runs in RuntimeEnvironment.isolatedAttempt; the winner is propagated (step 4 primitives).
//   IMPLEMENTED as programs.runtime.AttemptSelection.bestOf, with an optional inter-attempt feedback hook
//   (A, trace, score) => Either[err, Option[AdapterRef]] returning the NEXT attempt's adapter override.

selectBest(p, n, reward, threshold) : Prog[I, O] => Prog[I, O]   // bestOf over INDEPENDENT samples  [= BestOfN, DONE]
//   n attempts varying rolloutId / temperature; order-independent (feedback = None)

feedback(p, critic, n, reward, threshold) : Prog[I, O] => Prog[I, O]         // bestOf over a SEQUENTIAL stream  [= Refine, DONE]
//   the carried hint is realized as the next attempt's adapter override (Refine: OfferFeedback advice routed
//   into each predictor's hint_ via HintInjectingAdapter); attempt k+1 runs under the hint from attempt k.
//   order-dependent (= Refine, critic = OfferFeedback).
//   NOTE: ProgramOfThought is NOT a feedback instance (code-truth, see acceptance table) — its retry is
//   "regenerate-until-execution-succeeds", a distinct primitive, not best-of-n-with-reward.

agentLoop(policy, extractor, env, classify, render, maxIterations) : Prog[I, WithReasoning[O]]
//   policy, extractor : DynamicPredict (addressable)
//   env.step : Action => M[Observation]      // tool dispatch | interpreter | sandbox + sub-LM (RLM)
//   classify : DynamicPrediction => Continue(Action) | Done(Result)
//   Done(Result): either "run extractor over the trajectory" (ReAct/CodeAct) or "output carried in the
//   action" (RLM SUBMIT); the 3-way RLM case folds into this 2-constructor classify
//   (= ReAct, CodeAct, RLM).

retryUntil(gen, regen, run, ok, maxIterations) : ...        // regenerate-on-error loop  [NOT YET; = PoT inner loop]
//   run attempt; on failure feed (previous, error) into a DIFFERENT predictor (regen) and retry until `ok`
//   or maxIterations; first success wins (no reward, no keep-best). PoT = retryUntil(...) >>> answer-step.
//   This is the home ProgramOfThought needs — see acceptance table; it is NOT feedback.
```

## Laws (the contract)

```
Category          id >>> p = p = p >>> id
                  (p >>> q) >>> r = p >>> (q >>> r)

Applicative       parallel(pure(a), p) ≅ map(p)(a, _)
(parallel)        parallel associates up to tuple reassociation

Mode monoid       mode(m1 ⊕ m2) = mode(m1) ∘ mode(m2)        mode(idMode) = id        ⊕ associative

augment           base(run(augment[r](p))(i)) = run(p)(i)    // the added field is extra (round-trip, mock-LM)
                  augment[r] ∘ augment[r] = augment[r]        // idempotent (OutputAugmentation.Contains)

bestOf            reward(bestOf(...)(attempts)) ≥ reward(a)   for every successful attempt a   // monotonicity

selectBest        selectBest(p, 1, _, _) = p                  // n = 1 is identity
                  selectBest is invariant under attempt permutation (modulo tie-break)         // independent

feedback          feedback is NOT permutation-invariant       // carried hint = order matters

Predictors        read(c) = ownPredicts ++ children.flatMap(read)     // concatenation homomorphism
                  replace(p, read(p)) = p                              // round-trip
```

The `selectBest` permutation-invariance vs `feedback` order-dependence is the algebraic statement of why they
are two combinators sharing `bestOf`, not one combinator (fork 3).

## Optimizer-addressability (fork 4)

This is a data-shape constraint, satisfied by the existing `Predictor` (leaf) / `Predictors` (composite)
typeclasses, not new machinery. The rule:

- **Learnable predicts are addressable immutable fields; fixed behavior is closures.** A combinator that
  captures a learnable predict inside a closure is un-addressable and therefore wrong.
- `read` distributes over the algebra (the homomorphism law above). Per combinator:
  - `>>>`, `parallel`: structural (`read(a) ++ read(b)`; Mirror-derivable from the two child fields).
  - `augment(p)`, `selectBest(p)`: pass-through (`read(p)`).
  - `loop`: holds `policy` + `extractor` fields (`read = [policy, extractor]`; = ReAct/CodeAct today).
  - `feedback`: holds inner `p` + `critic` predict (`read = read(p) ++ [critic]`; = Refine today).
- **`mode` is restricted to non-learnable transforms** so it can stay closure-shaped and ergonomic. Anything
  with a learnable sub-generation (synthesis, comparison, critique) is a dedicated combinator that holds the
  predict as a field (`selectBest`, `feedback`, `MultiChainComparison`), never a mode. This is the one place
  the design must diverge from kyo-ai, whose closure-captured `Tool`/`Mode` carry no optimizer constraint.

## Acceptance criteria: each composite reduces to a recipe

"Step 6 done" means each existing composite is defined as a combinator expression, and its existing suite
plus the new combinator law suites are green:

| composite | recipe |
|---|---|
| `ChainOfThought` | `augment["reasoning", String, opening](predict)` |
| `MultiChainComparison` | `augment["rationale", …]` over the attempt-folded signature (holds compare-predict) |
| `BestOfN` | `selectBest(p, n, reward, threshold)` — DONE (6.1): `AttemptSelection.bestOf`, `feedback = None` |
| `Refine` | `feedback(p, critic = OfferFeedback, n, reward, threshold)` — DONE (6.1): `bestOf` + advice→adapter hook |
| `ProgramOfThought` | `retryUntil(...) >>> answer-step` — NOT feedback (corrected; see below). Deferred. |
| `ReAct` | `agentLoop(policy, extractor, env = tools, …)` |
| `CodeAct` | `agentLoop(policy, extractor, env = interpreter, …)` |
| `RLM` | `agentLoop(policy, extractor, env = sandbox + sub-LM, …)` |
| user pipelines | `a >>> b >>> c` (replacing hand-written `for`-comprehensions) — DONE (6.2): `AndThen` + `>>>`, plus `parallel` |

### Code-truth correction: ProgramOfThought is not `feedback`

The grill's spec claimed `ProgramOfThought = feedback(critic = regenerate-on-error)`. Reading the actual
[`ProgramOfThought`](../../modules/programs/src/main/scala/dspy4s/programs/ProgramOfThought.scala) during 6.1
showed that does not hold. PoT's inner loop (`tryIteration`) differs from `feedback`/`bestOf` on every axis:

- **No reward, no keep-best.** It retries on *execution failure* (parse error or non-zero exit), not on a
  sub-threshold reward; it accepts the FIRST successful execution rather than the argmax of `n`.
- **Fail, not best-so-far, on exhaustion.** If no attempt executes, it returns `Left`; `bestOf` returns the
  best attempt seen. (With a binary reward + threshold = 1 the *selection* coincides, but this divergence and
  the next one do not.)
- **Structured regenerate, not adapter-hint.** The retry runs a *different* predictor (`regenerator`) with
  `previous_code` + `error` as typed input fields, not the same predictor under a hint-injecting adapter.
- **Loop is a sub-step.** The retry wraps only code-gen; a separate `answer` step runs afterward, i.e.
  PoT ≈ `retryUntil(generate, regenerate, execute) >>> answer`, a `>>>` of a different primitive.

So PoT belongs to a `retryUntil` primitive (regenerate-until-ok), composed via `>>>` — not `feedback`. It is
deferred out of 6.1; the natural home is alongside `agentLoop` (6.3) or its own small combinator. Folding it
into `feedback` would have been the category error the grill set out to avoid.

## Resolved on paper vs deferred (fork 5)

- **All structural decisions are resolved; no pre-spec spike.** The whole algebra is specifiable and
  implementable on the current `Either` substrate now, and law-tested now.
- **kyo-compat CIO migration is a separate, non-blocking phase.** It is a mechanical rewrite of combinator
  bodies (`Either`-flatMap → `CIO[Either]`-flatMap, the shape `Future[Either]` already needs), guarded by the
  law suite. Combinator signatures and laws are invariant across the swap. It does not gate step 6.
- **One thing to validate during implementation (not before):** the `agentLoop` unification across ReAct +
  CodeAct + RLM (RLM's 3-way classify folding into `Continue | Done`, `SUBMIT`-carries-output). Validate by
  porting ReAct + CodeAct first, then RLM, against the existing suites. This is implementation, not a spike.

## Implementation sequencing

Smallest blast radius and highest dedup first; each step law-tested against this spec, existing composite
suites as the regression net:

1. **`bestOf` + `selectBest` + `feedback`. DONE (commit `96c9072`).** Extracted `bestOf` into
   `programs.runtime.AttemptSelection` (generalizing `BestOfN.selectBest` with an optional inter-attempt
   feedback hook); `BestOfN` is the `feedback = None` instance and `Refine` is the feedback (advice→adapter)
   instance. `ProgramOfThought` was NOT migrated — code-truth showed it is `retryUntil`, not `feedback` (see
   the correction above). Pinned by `AttemptSelectionLawSuite`; `TypedBestOfNSuite` / `RefinePerModuleAdviceSuite`
   green unchanged. Built on the step-4 `isolatedAttempt`/`propagateAttempt` primitives.
2. **`>>>` (Category) and `parallel`. DONE (commit `60d2ea5`).** Added `id` / `AndThen` (`>>>`) / `Both`
   (`parallel`) in `Compose.scala`, with hand-written `Predictors` instances (concretely typed children, so
   pipelines stay addressable) and `ComposeLawSuite` covering the Category + Applicative laws.
   **Code-truth correction:** `parallel` did NOT "largely exist as `Parallel`" — `Parallel` is a thread-pool
   batch executor over `Vector[(DynamicModule, ProgramCall)]`, an unrelated abstraction; the applicative
   `parallel(a, b)` is new. Category laws are stated on the threaded `.output` value (the carrier decision),
   not the full `Prediction` envelope.
3. **`agentLoop` (+ `retryUntil`).** Port ReAct + CodeAct to the shared loop with `env.step : Action =>
   M[Observation]`, then RLM. Extract `retryUntil` (the PoT inner loop) here and recast `ProgramOfThought` as
   `retryUntil(...) >>> answer`. Highest dedup, highest risk; the place to go carefully.
4. **`augment` generalization.** Raise `decodePrepended` to the `Thought`-shaped form (position, typed field,
   optional hook), with the current opening-String case as the trivial instance (the step-3 design ceiling).
5. **`mode`.** Introduce the non-learnable middleware monoid as the home for model-swap / temperature /
   retry, once a second consumer beyond the per-call config exists.

## Deferred items (recorded, not lost)

- **Usage-merge on `>>>`** (monoidal accumulation of per-step `lmUsage` onto the composite result): deferred;
  non-breaking when wanted.
- **`augment` closing position + arbitrary typed field + post-decode hook**: the full `Thought` form; opening
  String ships first (step 4 above).
- **CIO substrate migration**: the deferred kyo-compat phase described under fork 5.
