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

A program denotes a Kleisli arrow `I ⇝ O`: `run : Program[I, O] => I => M[O]`, where `M` carries the LLM
effect. Because `run` is effectful and the model is nondeterministic, laws are never stated on concrete
model outputs. They are stated on **composition** and checked in whichever way is honest for each:

- **structural** (on the program tree the combinator builds; no LLM): Category, Mode monoid, the `OptimizableTraversal`
  homomorphism;
- **mock-LM** (deterministic stub model): the augment round-trip;
- **distributional** (holds for any model output): exhaustive finite-score `argMax` monotonicity under an
  explicit deterministic tie-break. Ordered early-stop search is intentionally not permutation-invariant.

## Carrier (forks 1 and 5)

- **The unit stays `Module[I, O]`.** No parallel executable representation is introduced (this keeps
  the `OptimizableTraversal` optimizer machinery working). `Program[I, O]` below is denotational shorthand for that type.
- **`>>>` threads the plain typed value `O`,** not `Prediction[O]`. Controls (`config`, `traceEnabled`,
  `rolloutId`) ride in `ProgramCall`; the `Prediction` envelope and the effect sit at the edges. Intermediate
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

`Program[I, O]` = `Module[I, O]`. Learnable parts are held as addressable immutable
fields (see Optimizer-addressability); fixed parts (`env.step`, `reward`, `critic`, `classify`) are closures.

```
predict(sig: Signature[I, O]) : Program[I, O]                       // the atom: one LM round-trip (= Predict)
id[I]                          : Program[I, I]                       // pure passthrough           [IMPLEMENTED 6.2]
lift(f: I => O)                : Program[I, O]                       // local, parameter-free transform
liftEither(f: I => Either[E,O]): Program[I, O]                       // explicit local failure

a >>> b                        : (Program[I, X], Program[X, O]) => Program[I, O]      // Category (sequential)  [IMPLEMENTED 6.2]
mapOutput(f)(p)                 : Program[I, O] => Program[I, B]      // covariant output map; preserves raw
contramapInput(f)(p)            : Program[I, O] => Program[J, O]      // contravariant input map
dimap(before)(after)(p)         : Program[I, O] => Program[J, B]      // profunctor-style boundary map
//   runs a, feeds a.output: X into a fresh ProgramCall[X] inheriting the outer call's controls, runs b.
//   IMPLEMENTED as AndThen + the `>>>` extension; threads the plain value (the Prediction envelope of the
//   intermediate goes to the trace, not the result). p >>> id keeps p.output but resets .raw (carrier split).

fanout(a, b)                   : (Program[I, A], Program[I, B]) => Program[I, (A, B)] // ordered fan-out / &&&
split(a, b)                    : (Program[I, A], Program[J, B]) => Program[(I,J), (A,B)] // ordered split / ***
recover(policy, fallback)(p)   : Program[I, O] => Program[I, O]      // selected typed failures only
//   IMPLEMENTED as Both + Compose.fanout. NOTE: this is NOT the existing `Parallel` class — that is a batch
//   executor over Vector[(DynamicModule, ProgramCall)] on a thread pool, a different abstraction. This fan-out
//   runs two typed programs left-to-right on the same input and tuples the outputs. The raw merges both
//   sub-predictions' records (second wins on collision).

augment[Name, T](field)(p) : Program[I, O] => Program[I, Out]                     // Thought / CoT  [IMPLEMENTED 6.4]
//   IMPLEMENTED (opening position) as OutputAugmentation.decodeAugmented[O, Name, T, Out]: an arbitrary typed T
//   read via a pluggable readField, plus an optional post-decode hook. decodePrepended (T=String, identity hook)
//   is the instance ChainOfThought / MultiChainComparison / the agent extractors use. Out =
//   OutputAugmentation.WithField[O, Name, T]. CLOSING position (append, self-check) stays additive (no consumer;
//   needs an AppendField dual).

mode(m: Mode)(p)               : Program[I, O] => Program[I, O]         // Monoid middleware, NON-learnable  [IMPLEMENTED 6.5]
//   IMPLEMENTED as Mode (Controls => Controls, monoid under ++ / Mode.id) + Moded + Compose.mode: model swap /
//   temperature / rolloutId / traceEnabled. m introduces no learnable predict (OptimizableTraversal passes through);
//   trace-transparent. Execution-wrapping modes (retry / pre-post) stay additive (no consumer yet).

bestOf(reward, threshold, failCount)(attempts) : M[Prediction[O]]            // shared reducer  [IMPLEMENTED 6.1]
//   keep the argmax-reward attempt, short-circuit at threshold, tolerate failCount failures;
//   each attempt runs in RuntimeEnvironment.isolatedAttempt; the winner is propagated (step 4 primitives).
//   IMPLEMENTED as programs.runtime.AttemptSelection.bestOf, with an optional inter-attempt feedback hook
//   (A, trace, score) => Either[err, Option[AdapterRef]] returning the NEXT attempt's adapter override.

selectBest(p, n, reward, threshold) : Program[I, O] => Program[I, O]   // bestOf over sampled attempts  [= BestOfN, DONE]
//   n attempts varying rolloutId / temperature; ordered because early stopping and ties select the first match

feedback(p, critic, n, reward, threshold) : Program[I, O] => Program[I, O]         // bestOf over a SEQUENTIAL stream  [= Refine, DONE]
//   the carried hint is realized as the next attempt's adapter override (Refine: OfferFeedback advice routed
//   into each predictor's hint_ via HintInjectingAdapter); attempt k+1 runs under the hint from attempt k.
//   order-dependent (= Refine, critic = OfferFeedback).
//   NOTE: ProgramOfThought is NOT a feedback instance (code-truth, see acceptance table) — its retry is
//   "regenerate-until-execution-succeeds", a distinct primitive, not best-of-n-with-reward.

AgentLoop.run[St, R](state, maxIterations)(onExhausted)(step) : M[R]          // bounded loop  [IMPLEMENTED 6.3]
//   step : (St, Int) => M[Continue(St) | Done(R)] ; onExhausted : St => M[R]. The shared control-flow core of
//   ReAct / CodeAct / RLM / PoT. CODE-TRUTH CORRECTION: the env.step/classify/render decomposition below was
//   NOT adopted — see the correction under the acceptance table. Each module keeps its own `step` closure.

TrajectoryAgent.runAndExtract[S, E](...)(step)(extract) : M[(E, String)]      // ReAct/CodeAct  [IMPLEMENTED 6.3]
//   bounded loop building a Vector[S] trajectory (via AgentLoop.run), then the extract closure with
//   overflow-truncation. E is the extractor's result: since the typed-inner-predict conversion the extractors
//   are typed Predicts, so E = Prediction[WithReasoning[O]] and the reasoning-prepended decode happens INSIDE
//   the extractor (OutputAugmentation.prependedStringShape) rather than in the module. (= ReAct, CodeAct)

// retryUntil = AgentLoop.run with a regenerate-on-error step  [IMPLEMENTED 6.3, = ProgramOfThought]
//   first attempt runs `generator`; each failure feeds (previous_code, error) into `regenerator`; first
//   success wins (no reward, no keep-best); exhausting the budget surfaces the last failure. Not a separate
//   combinator (one consumer) — PoT calls AgentLoop.run directly. PoT = retryUntil(...) then the answer step.
```

## Laws (the contract)

```
Category          id >>> p = p = p >>> id
                  (p >>> q) >>> r = p >>> (q >>> r)

Ordered fan-out   parallel runs left-to-right and fail-fast
                  parallel associates on values up to tuple reassociation

Mode monoid       mode(m1 ⊕ m2) = mode(m1) ∘ mode(m2)        mode(idMode) = id        ⊕ associative

augment           base(run(augment[r](p))(i)) = run(p)(i)    // the added field is extra (round-trip, mock-LM)
                  augment[r] ∘ augment[r] = augment[r]        // idempotent (OutputAugmentation.Contains)

argMax            exhaustive finite-score selection returns a maximum under its tie-break

selectBest        ordered search: early stopping and ties preserve attempt order
                  n = 1 still rewrites rollout controls, so it is not operational identity

feedback          feedback is NOT permutation-invariant       // carried hint = order matters

OptimizableTraversal        inspect(c) = ownViews ++ children.flatMap(inspect)  // metadata + state snapshots
                  read(c) = inspect(c).map(_.parameters)                    // parameter projection
                  replace(p, read(p)) = p                              // Get-Put
                  read(replace(p, states)) = states                    // Put-Get
                  inspect(replace(p, states)).map(_.metadata)
                    = inspect(p).map(_.metadata)                        // frame
```

`selectBest` and `feedback` share attempt execution and selection mechanics, but both are ordered state machines:
`feedback` additionally carries advice between attempts. Exhaustive `argMax` is the smaller pure reducer with
commutative laws.

## Optimizer-addressability (fork 4)

This is a data-shape constraint, satisfied by the existing `OptimizableLeaf` (leaf) / `OptimizableTraversal` (composite)
typeclasses, not new machinery. The rule:

- **Learnable predicts are addressable immutable fields; fixed behavior is closures.** A combinator that
  captures a learnable predict inside a closure is un-addressable and therefore wrong.
- `read` distributes over the algebra (the homomorphism law above). Per combinator:
  - `>>>`, `parallel`: structural (`read(a) ++ read(b)`; Mirror-derivable from the two child fields).
  - `augment(p)`, `selectBest(p)`: pass-through (`read(p)`). DONE (commit `dd2fd4f`): `BestOfN` is now
    parameterized over the concrete inner type (`BestOfN[P, I, O]`, mirroring `Refine`) with a pass-through
    instance in its companion.
  - `loop`: holds `policy` + `extractor` fields (`read = [policy, extractor]`; = ReAct/CodeAct, and now RLM:
    `rlmOptimizableTraversal` reads `[actionPredict, extractPredict]` via the override-field pattern, commit `dd2fd4f`).
  - `feedback`: holds inner `p` + `critic` predict (`read = read(p) ++ [critic]`). DONE (commit `dd2fd4f`):
    the OfferFeedback critic is hoisted to an addressable `criticPredict` field (override pattern) and
    `refineOptimizableTraversal` exposes it last, so optimizers can tune the critic like any other learnable.
    Pinned by `CompositeOptimizableTraversalSuite`.
- **`mode` is restricted to non-learnable transforms** so it can stay closure-shaped and ergonomic. Anything
  with a learnable sub-generation (synthesis, comparison, critique) is a dedicated combinator that holds the
  predict as a field (`selectBest`, `feedback`, `MultiChainComparison`), never a mode. This is the one place
  the design must diverge from kyo-ai, whose closure-captured `Tool`/`Mode` carry no optimizer constraint.

### Para formalization (prototype landed)

The addressability layer is an instance of the **Para construction** from categorical learning theory
("Backprop as Functor", Fong/Spivak/Tuyeras; "Categorical Foundations of Gradient-Based Learning",
Cruttwell et al.): a morphism is a pair (parameters, shape), composition tensors the parameters, and
reparameterization is the 2-cell layer optimizers act on. dspy4s's writable parameters are homogeneous (every
parameter block is an `OptimizableParameters` value containing instructions, demos, and config), so the parameter tensor degenerates
to the free monoid `Vector[OptimizableParameters]`. `OptimizableTraversal.read` / `replace` are exactly Para's projection and
reparameterization; signature structure and module identity remain in the morphism's read-only metadata.

Prototype (commit `9d4b5cd`, encoding inspired by the constraint-parameterized `CategoryTC` in
jpablo/math-with-scala, with the constraint moved from objects to the morphism representation):
`dspy4s.programs.para.ParaCategory` (id / `>>>` / ordered `fanout` / `params` / `reparam` with the Para laws) over
`dspy4s.programs.para.Program` (the packaged Sigma-type morphism bundling a concrete `Rep` with its
`OptimizableTraversal[Rep]` evidence). Packaging is the only constructor, so a program without evidence cannot enter
the category (compile error at `Program.of`, proven by a `compileErrors` test); pinned by `ParaCategoryLawSuite`.
The Mirror-based `OptimizableTraversal.derived` gate is strict: every product field must provide `OptimizableTraversal` evidence.
Intentionally parameter-free field types opt in with `OptimizableTraversal.empty`; missing evidence is a compile error,
so a learnable subtree cannot silently disappear from optimizer addressability.

**Entry-point experiment (commit `8d7e009`), CLOSED (commit `d1d38d0`).** The first round drove COPRO through
a packaged `Program` via the path-dependent instantiation `new COPRO[program.Rep](config)(using program.optimizableParameters,
runnable)` and surfaced the finding: **Para evidence alone is not enough to optimize.** Optimizers also need
`ProgramRunner` (decode a record, run), which was not packaged; it resolved only against the packaging-refined
type, so it died under upcasts and did not exist for composed pipelines (`AndThen`) at all.

The close went through two forms. FIRST (historical): `Program` packaged a per-morphism
`decodeInput`, captured through a `ProgramInput` capability typeclass and threaded through composition, with a
documented coherence law (the packaged decoder must agree with the program's typed input boundary) as the
condition under which the category laws held. SECOND (current, stage 4): decoding is a property of the OBJECT.
`Program` packages nothing decode-related; `Program.of` requires a sealed `RecordCodec[I]` at the domain (the
object gate) alongside `OptimizableTraversal`; the record-boundary runner demands `RecordCodec[I]` at use; composition
threads nothing. `ProgramInput`, the morphism-specific decoder, and the coherence law are GONE: identity, every
program at an object, and the runner all decode through the object's one canonical codec, so the unit laws hold
with no decode-side condition and an incoherent per-morphism decoder is UNREPRESENTABLE (compile gates pin both
former vehicles).
Bare-module running is a separate concern with no coherence question (no identity morphism in sight):
`ProgramRunner` carries signature-backed instances for the framework leaves and composites, plus a
low-priority `RecordCodec` fallback for user composites. Typed named-tuple inputs (`fromString` / `fromType` /
`of[Spec]`) get their codec from a `RecordCodec` derivation over the same `SchemaTupleShape` path those macros
use, so codec and signature decode cohere definitionally; `Record`-input programs no longer package at all
(`DynamicSignature` is the dynamic gate into the category).

**The `DynamicSignature` bundle (prototype): fresh types for runtime signatures.** The reason the coherence law
exists at all is that every `fromStringDynamic` program shares the input type `DynamicValue.Record` while needing
its own field-validating decoder, so the type cannot determine the decoder. `DynamicSignature.parse` removes that
collapse for programs that enter the category: each parse mints fresh abstract `In` / `Out` type members (fresh
per stable path, the path-dependent freshness the compiler enforces) and carries the matching sealed `RecordCodec`s, born from
the same parse behind the abstraction. Identity and any program over the bundle then decode identically as a
consequence of abstraction: the unit laws hold on bundle objects with NO coherence caveat, and re-parsing the
same string mints a distinct object (cross-bundle composition is a compile error; both pinned in
`ParaCategoryLawSuite`). Because Scala widens `val alias = s`, `s.stable` captures the path's types in generic
parameters that survive further aliases; its compile-time contract is pinned too. Cardinality-shaped value dependence uses the same idea:
`MultiChainComparison` owns a path-branded opaque attempt block validated against `m`. Plain `fromStringDynamic`
remains the data-bag surface for consumers that never enter the category (optimizer helper generations, the
evaluation judge). The second step this enabled LANDED as stage 4: with every category-entering program typed
or bundled, `decodeInput` left the `Program` package entirely and the `ProgramInput` law dissolved at the
category level (see "The close" above).

Usability shipped with the prototype (`DynamicSignatureSuite`): `s.predict(...)` is the path-dependent
constructor (the runtime-string counterpart of `Predict(Signature.derived(...))`, outputs read from the raw
envelope as always), and the optimizer surface (`OptimizableTraversal` read/replace + the record-boundary
`ProgramRunner`) holds over a packaged bundle program. Cross-fiber pipelines are expressed through
`DynamicSignature.bridge(from, to): Either[_, Program[from.Out, to.In]]`, the reindexing morphism: it factors
through the wire (encode, then the target's validating entry, a parameter-free `LiftEither`), fails EAGERLY
when the target's input names are not covered by the source's output names (that name-set condition is the
base compatibility arrow the bridge lifts), and contributes nothing to `params`. One correction to the earlier
fibration sketch: the bridge's base arrow crosses cohorts (source OUTPUT fields to target INPUT fields), so
objects properly sit over (signature, cohort) pairs and no identity-lift law (`bridge(s, s) = id`) arises; the
lawful statement is exactly "bridges are lifts of base compatibility arrows".

Stage 3 landed and the prototype label is off: `ParaCompileSuite` drives COPRO through a packaged bundle
program (the runtime-string student finds the winning instruction exactly like the typed one, through the same
`OptimizableTraversal` + `ProgramRunner` entry point, no dynamic-specific plumbing), and the learn/optimization example
runs its main through `DynamicSignature.parse` + `predict()` with the doc snippet generalized to the capability
constraints. The declared stance: `DynamicSignature` is the user path for runtime-string signatures;
`DynamicPredict` is the untyped substrate for framework-internal generations (its scaladoc now points users to
the bundle). Stage 4 then LANDED (the no-users API-break window): `decodeInput` and `ProgramInput` are deleted,
decoding is object-side, and the coherence law is not discharged but DISSOLVED, its counterexample
unrepresentable. Both optimizer capabilities are uniform over the packaged type: `OptimizableTraversal[Program[I, O]]`
(Program companion; read/replace = the Para projection/reparameterization) and `ProgramRunner[Program[I, O]]`
(Program companion; conditional on `RecordCodec[I]`, decode object-side + run). So `Program[I, O]` is a
first-class optimizable program: `new COPRO[Program[I, O]](config)` type-checks directly (any `Teleprompter`
does), upcasts and composed pipelines `a >>> b` optimize end-to-end, and `.copro` demands exactly the runner,
which exists exactly when the pipeline's input object is codec-equipped. Pinned by `ParaCategoryLawSuite`
(object-side decoding + the unrepresentability gates) and `ParaCompileSuite` (upcast + composed-pipeline +
bundle optimization).

**Codec-equipped objects (commit `876442a`), the id wrinkle RESOLVED.** The close left one law wrinkle:
`id[A]` carried a failing decoder (nothing decodes an arbitrary `A` from a record), so the left unit
degraded on the evaluation observation. The fix is the `CategoryTC[P[_], Hom]` object-constraint slot from
jpablo/math-with-scala, applied where it belongs: `ParaCategory` is now `ParaCategory[P[_], Hom[_,_]]`, instantiated
for `Program` at `P = RecordCodec` ("the object decodes from a record", built on the SAME
`Shape.derivedWithRole(Input)` decode path `Signature.derived` uses, so codec- and signature-derived
decoders cohere definitionally). Unlike a blanket Ok-style constrained category, the constraint appears
ONLY where object evidence is required: `id[A: RecordCodec]` is available at codec-equipped objects while
`>>>` stays unconstrained and packages no decoder.
Result, pinned by the suites: the left unit holds on the evaluation observation (after stage 4,
definitionally: one canonical codec per object is the only decode path); an id-headed pipeline optimizes end-to-end
through COPRO; and `id` at a non-codec object is a compile error, the honest statement that over
codec-equipped objects the structure is a genuine category while elsewhere it is a semicategory (morphisms
compose, no unit). After stage 4 the object constraint also gates `Program.of` and the record-boundary runner;
no decoder evidence is stored in a morphism.

**Law statements, the read functor, and fan-out (commit `446ccb6`, adopted from jpablo/math-with-scala).**
Three encodings from the math library, fitted to dspy4s's executable-laws discipline:

- **Laws as statements.** `core.algebra.Laws` adds `IsEq[A]` (an equation as a value, built with `<->`)
  and the `@Law` annotation. The Para structures now state their laws as `@Law` methods ON the traits, and
  `ParaCategoryLawSuite` executes the statements instead of hand-building both sides, each under the honest
  observation (structural `==` for parameter vectors; complete prediction + params + lifecycle for `Program`
  morphisms, decoding having moved to the objects in stage 4). Sequential raw evidence has an associative
  accumulator with the empty envelope as identity, so `p >>> id` is indistinguishable even through `ProgramRunner`.
  The former unlawful-decoder counterexample is UNREPRESENTABLE: `RecordCodec` is sealed and its removal is pinned
  by compile gates. The deliberate split from the
  formalization library: there the equations are the deliverable, here they are executable specifications.
- **`params` as a functor value.** `ParaCategory` splits into a base `Category[P[_], Hom]` so the delooping of the
  parameter monoid is itself a lawful `Category` instance, and `ReadFunctor` (a `CategoryFunctor` from the `Program`
  category to the parameter-monoid delooping) names what `OptimizableTraversal.read` is categorically; its functor laws
  (preserves id + composition), carried on the `CategoryFunctor` trait against the two `Category` instances, are exactly
  the Para projection laws. The
  parameter monoid is now an explicit `given Monoid[Vector[OptimizableParameters]]` and the delooping is generic
  (`delooping[M](using Monoid[M]) : Category[AnyObject, Delooped[M]]`, "a monoid is a one-object category"), so
  `paramsDeloop` is literally that monoid delooped (commit `d3be8e1`).
- **`fanout`, named honestly.** Added to the `Program` layer as the ordered pairing: both legs share the
  input, so it is copy-then-ordered-tensor fused. The copy NON-law is pinned as an executable
  counterexample: `h >>> fanout(f, g)` runs `h` once while `fanout(h >>> f, h >>> g)` runs it twice,
  with visibly different parameters (sizes 3 vs 4); the outputs coincide only for deterministic `h`, which
  is precisely why fan-out naturality cannot be a law for LLM morphisms. This is also the categorical
  restatement of why the spec's fan-out is not independent execution: sharing vs re-running are
  different programs, and the algebra keeps them distinguishable.
  The underlying ordered independent-input operation (`Tensor` / `Compose.tensor`) and `Copy` were later added
  for completeness (commit `508a8e6`), so `fanout = copy >>> split`; deterministic and effect-observing copy
  cases are executable. The corrected framing is in [algebra.md](algebra.md). `tensor` stays at the `Module` level
  as the compatibility name for `split` (its `(I, J)` input has no single-record decoder).

## Acceptance criteria: each composite reduces to a recipe

"Step 6 done" means each existing composite is defined as a combinator expression, and its existing suite
plus the new combinator law suites are green:

| composite | recipe |
|---|---|
| `ChainOfThought` | `augment["reasoning", String]` — DONE: `decodeAugmented` (the `decodePrepended` String instance) |
| `MultiChainComparison` | `augment["rationale", String]` over the attempt-folded signature (holds compare-predict) — DONE: same shared decode |
| `BestOfN` | `selectBest(p, n, reward, threshold)` — DONE (6.1): `AttemptSelection.bestOf`, `feedback = None` |
| `Refine` | `feedback(p, critic = OfferFeedback, n, reward, threshold)` — DONE (6.1): `bestOf` + advice→adapter hook |
| `ProgramOfThought` | `retryUntil(...)` then answer-step — DONE (6.3): `AgentLoop.run` regenerate-on-error. NOT feedback (see below). |
| `ReAct` | DONE (6.3): `TrajectoryAgent.runAndExtract` + `reactStep` (tool dispatch; keeps per-iteration truncation+break) |
| `CodeAct` | DONE (6.3): `TrajectoryAgent.runAndExtract` + `codeActStep` (interpreter) |
| `RLM` | DONE (6.3): `AgentLoop.run` (SUBMIT = Done inside the loop; extract fallback = onExhausted) |
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

### Code-truth correction: `agentLoop`'s env/classify/render decomposition does not fit

The grilled `agentLoop(policy, extractor, env, classify, render)` with `env.step: Action => M[Observation]` and
`classify : RawPrediction => Continue(Action) | Done(Result)` was too decomposed for the actual code:

- **Done-detection is entangled with the action, not separable into `classify`.** CodeAct runs the *finishing*
  code (the `finished` flag is read alongside the executed snippet); RLM detects SUBMIT only *after* executing
  the code (from the interpreter's `finalOutput`); ReAct's `finish` is itself a tool that runs. In all three
  the "done" signal arrives during/after the action, so a pre-action `classify` cannot produce it.
- **The three classify/terminal shapes genuinely differ.** ReAct/CodeAct produce `WithReasoning[O]` via a
  *separate* extractor that always runs; RLM's output is carried *inside* the loop by SUBMIT (extractor only as
  a fallback). One `Action`/`Observation` vocabulary across tool-call vs code-string vs SUBMIT-record would be
  indirection, not dedup.

So 6.3 extracted the part that IS genuinely shared — the bounded `Continue | Done | exhausted` iteration
([`AgentLoop.run`](../../modules/programs/src/main/scala/dspy4s/programs/runtime/AgentLoop.scala)) plus the
ReAct/CodeAct loop+extract postlude ([`TrajectoryAgent`](../../modules/programs/src/main/scala/dspy4s/programs/runtime/TrajectoryAgent.scala))
— and left each module's per-step semantics in its own `step` closure. Same discipline as the PoT and
`parallel` corrections: extract the real shared core, do not force a decomposition the code rejects.

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
   (`parallel`) in `Compose.scala`, with hand-written `OptimizableTraversal` instances (concretely typed children, so
   pipelines stay addressable) and `ComposeLawSuite` covering value-category and ordered-fan-out semantics.
   **Code-truth correction:** `parallel` did NOT "largely exist as `Parallel`" — `Parallel` is a thread-pool
   batch executor over `Vector[(DynamicModule, ProgramCall)]`, an unrelated abstraction; the typed fan-out
   `parallel(a, b)` is new. Category laws are stated on the threaded `.output` value (the carrier decision),
   not the full `Prediction` envelope.
3. **`agentLoop` (+ `retryUntil`). DONE (commit `6faa94e`).** Extracted `AgentLoop.run` (bounded
   `Continue | Done | exhausted` iteration) + `TrajectoryAgent.runAndExtract` (ReAct/CodeAct loop+extract);
   ported ReAct, CodeAct, RLM onto them and recast `ProgramOfThought`'s retry as `AgentLoop.run`
   (regenerate-on-error). `AgentLoopLawSuite` pins the primitive; the four module suites are green unchanged.
   **Code-truth correction:** the `env.step`/`classify`/`render` decomposition was NOT adopted (see above) —
   the shared core is the bounded loop, each module keeps its own step closure.
4. **`augment` generalization. DONE (commit `31aecbd`).** Raised `decodePrepended` to `decodeAugmented`
   (arbitrary typed field via a pluggable reader + optional post-decode hook); `decodePrepended` is its
   String/identity instance, so the five call sites are unchanged. Closing position stays additive (no
   consumer). `OutputAugmentationSuite` adds a typed-field test, the round-trip law, and a hook accept/reject.
5. **`mode`. DONE (commit `dca35e9`).** Introduced `Mode` (the `Controls => Controls` monoid) + `Moded` +
   `Compose.mode` as the home for model-swap / temperature / rolloutId; trace-transparent, OptimizableTraversal
   pass-through, `ModeLawSuite` pins the monoid + identity. Execution-wrapping modes (retry / pre-post) stay
   additive until a consumer needs them.

## Status: all six steps landed

Steps 1–5 (the behavior-preserving primitive extractions) and step 6.1–6.5 (this algebra) are implemented and
law-tested on the branch; every composite reduces to a combinator expression and the full suite is green. The
recurring discipline was to extract the genuinely shared core and correct the spec against code-truth where the
grilled design was over-decomposed (PoT is `retryUntil` not `feedback`; `parallel` is new, not the batch
`Parallel`; `agentLoop`'s env/classify/render seam does not fit).

**Typed inner predicts (the class-A conversion).** Every composite whose internal signatures are statically
shaped now runs TYPED inner `Predict`s instead of `DynamicPredict`s: Refine's OfferFeedback critic, ReAct's
loop + extractor, CodeAct's generator + extractor, RLM's action + extract, and ProgramOfThought's
generator / regenerator / answerer. The recipe is uniform: keep the hand-built layout verbatim (prompt
rendering unchanged), pair it with `InputAugmentation.appendedStringInput` on the input side (the pair carrier
`(I, String)` delegates base encoding to the base shape, appending the declared field — apply twice for two
fields) and either a derived shape (fully synthetic inputs: RLM, the critic) or a hand-written explicit output
shape that models tolerant reads algebraically (`ProgramOfThought.CodeOut` uses `Option[String]`; `finished` coerces
bool-or-"true", tool args accept record / JSON-string / nothing); reasoning-prepended extractors decode inside
the predict via `OutputAugmentation.prependedStringShape` (extracted from ChainOfThought's hand-written shape).
**Runtime arity rides a static carrier (the class-B bridge).** `MultiChainComparison` — whose field COUNT is the
constructor parameter `m` — converts too: `InputAugmentation.appendedStringInputs` returns a bundle with a
path-dependent opaque `Values` carrier and `Shape[(I, Values)]`. Its validating constructor is the only way to
obtain `Values`, so the per-instance shape total-encodes exactly the `m` numbered `reasoning_attempt_i` fields
without truncation or a representable wrong-length typed value. The wire format is unchanged. The remaining
`DynamicPredict` constructors in the framework are exactly the
principled residue: layouts that exist only as runtime VALUES (COPRO / GroundedProposer / InferRules attempt
injection over optimizer-assembled layouts, the evaluation judge), `Predict.erase`, and user
`fromStringDynamic` signatures.

## Deferred items (recorded, not lost — additive, no consumer yet)

- ~~**Usage-merge on `>>>`.**~~ Resolved by `RawPrediction.followedBy`: usage combines pointwise while the
  rightmost produced values/completions win and the empty envelope is identity.
- **`augment` closing position**: append a self-check field (the dual of opening); needs an `AppendField`
  dual. The typed-field + post-decode-hook parts of the `Thought` form shipped in 6.4.
- **Execution-wrapping `mode`s**: retry / pre-post hooks (6.5 shipped the pure control-transform monoid).
- **Commutative denotational carrier**: the abstract `CDCategory[Hom]` law target remains, but unrestricted
  `ModuleHom` implements only `OrderedTensorOps`; fail-fast interchange is false. A future stochastic-kernel or
  other commutative carrier could implement CD/Markov laws. A pair-input decoder would still be needed to lift
  ordered tensor into `ParaCategory`/`Program`.
- **Full Para adoption**: promote the packaged `Program` (see the Para formalization above; the entry-point
  loop is closed, decoding is object-side with codec-equipped objects gating `of` / `id` / the runner, the
  signature-backed `ProgramRunner` instances cover the framework leaves and composites for bare-module
  running, and the BestOfN / Refine / RLM `OptimizableTraversal` instances are in place, so the layer and its instance
  coverage are functionally complete) to the DOCUMENTED public optimizer entry-point API (docs-site guide +
  README surface). The former Mirror silent-drop is already closed: structural derivation now requires field
  evidence, with `OptimizableTraversal.empty` as an explicit parameter-free opt-in. The stage-4 API break already
  happened in the no-users window, so what remains is documentation surface, not code.
- **CIO substrate migration**: the deferred kyo-compat phase described under fork 5 — a mechanical rewrite of
  the combinator bodies (`Either`-flatMap → `CIO[Either]`-flatMap), guarded by the law suites.
