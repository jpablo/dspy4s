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
id[I]                          : Prog[I, I]                       // pure passthrough

a >>> b                        : (Prog[I, X], Prog[X, O]) => Prog[I, O]      // Category (sequential)
//   runs a, feeds a.output: X into a fresh TypedCall[X] inheriting the outer call's controls, runs b

parallel(a, b)                 : (Prog[I, A], Prog[I, B]) => Prog[I, (A, B)] // Applicative (independent)

augment[Name, T](field, position)(p) : Prog[I, O] => Prog[I, Out]           // Thought / CoT
//   position = opening (prepend, conditions the answer) | closing (append, self-check); opening shipped today
//   Out = OutputAugmentation.WithField[O, Name, T]; the String/opening/hook-less case is ChainOfThought

mode(m: Mode)(p)               : Prog[I, O] => Prog[I, O]         // Monoid middleware, NON-learnable only
//   model swap / temperature / retry / pre-post; m introduces no learnable predict of its own

bestOf(reward, threshold, failCount)(attempts) : M[Prediction[O]]            // shared reducer
//   keep the argmax-reward attempt, short-circuit at threshold, tolerate failCount failures;
//   each attempt runs in RuntimeEnvironment.isolatedAttempt; the winner is propagated (step 4 primitives)

selectBest(p, n, reward, threshold) : Prog[I, O] => Prog[I, O]   // bestOf over INDEPENDENT samples
//   n attempts varying rolloutId / temperature; order-independent (= BestOfN)

feedback(p, critic, n, reward, threshold) : Prog[I, O] => Prog[I, O]         // bestOf over a SEQUENTIAL stream
//   critic : (Prediction[O], trace, reward) => Hint ; attempt k+1 carries the hint from attempt k
//   order-dependent (= Refine, critic = OfferFeedback; = ProgramOfThought, critic = regenerate-on-error)

agentLoop(policy, extractor, env, classify, render, maxIterations) : Prog[I, WithReasoning[O]]
//   policy, extractor : DynamicPredict (addressable)
//   env.step : Action => M[Observation]      // tool dispatch | interpreter | sandbox + sub-LM (RLM)
//   classify : DynamicPrediction => Continue(Action) | Done(Result)
//   Done(Result): either "run extractor over the trajectory" (ReAct/CodeAct) or "output carried in the
//   action" (RLM SUBMIT); the 3-way RLM case folds into this 2-constructor classify
//   (= ReAct, CodeAct, RLM). ProgramOfThought is NOT here; it is feedback.
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
| `BestOfN` | `selectBest(p, n, reward, threshold)` |
| `Refine` | `feedback(p, critic = OfferFeedback, n, reward, threshold)` |
| `ProgramOfThought` | `feedback(p, critic = regenerate-on-error, …)` |
| `ReAct` | `agentLoop(policy, extractor, env = tools, …)` |
| `CodeAct` | `agentLoop(policy, extractor, env = interpreter, …)` |
| `RLM` | `agentLoop(policy, extractor, env = sandbox + sub-LM, …)` |
| user pipelines | `a >>> b >>> c` (replacing hand-written `for`-comprehensions) |

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

1. **`bestOf` + `selectBest` + `feedback`.** Extract `bestOf` from `BestOfN.selectBest`; express `selectBest`
   (independent) and `feedback` (sequential, critic hint) on it; migrate `Refine` and `ProgramOfThought` onto
   `feedback`. Highest dedup, lowest risk (builds on the step-4 `isolatedAttempt`/`propagateAttempt`).
2. **`>>>` (Category) and `parallel`.** `>>>` is new (replaces user for-comprehensions); `parallel` largely
   exists as `Parallel`. Add the Category law suite.
3. **`agentLoop`.** Port ReAct + CodeAct to the shared loop with `env.step : Action => M[Observation]`, then
   RLM. Highest dedup, highest risk; the place to go carefully.
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
