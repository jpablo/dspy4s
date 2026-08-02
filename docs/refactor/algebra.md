# dspy4s algebras (algebra-driven design notes)

**Status:** running record. Algebra 1 (signature transforms) is specified and its laws are property-tested;
algebra 2 (program composition) is specified and fully implemented (steps 6.1–6.5 landed: `bestOf`, the
`id`/`>>>`/`parallel` combinators, the `AgentLoop` agentic-iteration core, the typed `augment`, and the `mode`
middleware monoid; see the status section at the bottom).
**Method:** design the algebra first (types, operations, and the equations relating them), read law
complexity as the fitness signal, then derive the implementation. The laws are the deliverable; the code is
downstream. Related: [composite-primitives.md](composite-primitives.md), [kyo-ai-comparison.md](kyo-ai-comparison.md).

## Vocabulary

- **Constructors** build values of the algebra's type (terminal: `create`; inductive / combinators:
  `prependOutput`, `>>>`).
- **Observations** go out of the algebra into another type (`outputFields`, `run`). The only way to extract
  information.
- **Laws** are equations relating them. Equality is **observational**: two terms are equal iff no
  observation distinguishes them (not structural `==`).

dspy4s has at least three nested algebras. The effect layer (`CIO`, kyo-compat) is already a known structure
(a monad) and needs no design. The two that do:

---

## Algebra 1: signature transforms

The value-level algebra over `SignatureLayout` (implemented as `SignatureOps`).

**Purpose, as an observation.** A layout exists to be rendered into a prompt and decoded from a reply, so
the adapters' reads are the observations:

```
in:    Sig => Vector[Field]      out:   Sig => Vector[Field]
instr: Sig => Option[String]     name:  Sig => String
```

A layout *denotes* `(in, out, instr, name)`. Two layouts are equal iff those four agree.

**Constructors.** Terminal: `create` / `derived` / `parse`. Combinators (endomorphisms `Sig => Sig`):
`prependOutput(f)`, `appendInput(f)`, `replaceOutputs(fs)`, `withInstructions(s)`. The low-level
`append` / `prepend` / `insert` / `delete` on `SignatureLayout` are implementation, kept `private[dspy4s]`.

**Laws** (stated ON the structure as `@Law` methods in `SignatureOps.laws` returning `IsEq`; executed over
generated layouts by `SignatureOpsLawSuite` under observational equality / `sameElements`):

```
L1 cohort isolation     in(prependOutput(f)(s)) = in(s)        out(appendInput(g)(s)) = out(s)
L2 idempotent-by-name   prependOutput(f) ∘ prependOutput(f) = prependOutput(f)   (likewise appendInput)
L3 cross-cohort comm.   appendInput(g) ∘ prependOutput(f) = prependOutput(f) ∘ appendInput(g)
L4 replace absorbs/sets replaceOutputs(fs) ∘ prependOutput(g) = replaceOutputs(fs)
                        out(replaceOutputs(fs)(s)) = fs        in(replaceOutputs(fs)(s)) = in(s)
L5 prepend effect       out(prependOutput(f)(s)) = if f.name ∈ names(out s) then out s else f +: out s
L6 instructions         withInstructions(a) ∘ withInstructions(b) = withInstructions(a)   (last write wins)
```

**Structure.** The endomorphisms under `∘` form a monoid (identity = `id`). L3 says the input-combinators
and output-combinators are two **commuting submonoids**, now explicit `given Monoid` instances over the
endomorphism newtypes `InputTransform` / `OutputTransform` (`SignatureOps.scala`; `empty` = the no-op
transform, `combine` = composition, generators `append` / `prepend` / `replace`), with the cross-monoid
commuting law `SignatureTransformLaws.submonoidsCommute`. Their laws hold up to output-observational equality
of the wrapped transform (not `==`), executed in `SignatureOpsLawSuite`. Within a cohort the generators are
idempotent by name but order-sensitive (two `prependOutput`s do not commute), which is the signature of an
insertion-ordered, name-keyed map.

**Carrier note (from the lawfulness review).** The laws quantify over role-correct fields, but `FieldSpec`
itself carries no role restriction: a wrong-role field passed to a cohort op would land in the wrong cohort
and silently violate L1. The ops now `require` the role (an `IllegalArgumentException` at the call site), so
the lawful subset is enforced at the operation rather than assumed by the law suite's generators. A
role-indexed `FieldSpec` would move the guard to compile time, but the ops are `private[dspy4s]` and the
fail-fast check is proportionate to that internal surface.

**Design critique, resolved.** `SignatureLayout` used to enforce uniqueness with a runtime
`require(distinct names)`. In ADD terms an invariant is a feature of the implementation, not the design:
the `require` is the tell that uniqueness should hold by construction. The first instinct was to model the
cohort as a `VectorMap[String, Field]`, but scoping found that a poor fit: field **order is semantically
significant** (adapters render fields top-to-bottom; opening vs closing reasoning depends on it), whereas
`VectorMap`'s equality is order-insensitive (it is a `Map`), so a `VectorMap` representation would give the
wrong default equality. The chosen fix keeps `fields: Vector[FieldSpec]` (order-sensitive equality, the
public read API, and serialization all preserved) and makes uniqueness closed by construction instead: the
primary constructor is `private`, every field mutator routes through `withFields` which dedups by name, and
`create` validates arbitrary input. The unique-name `require` is retired; no operation can throw on a
duplicate and no public path can introduce one.

**Marks check.** Compositional, task-relevant, parsimonious (three combinators plus `withInstructions`;
the mutators are implementation), orthogonal (input vs output cohorts), closed (the public combinators keep
uniqueness; the raw mutators that can break it are private).

---

## Algebra 2: program composition (step-6 frontier)

The algebra over predictive programs (`Module[I, O]`, or a `Program[I, O]`). This is the design target for step 6.

> **Now specified.** The five open forks were resolved by a design grill, and the full operation + law set,
> the per-module reduction recipes (acceptance criteria), and the implementation sequencing live in
> [algebra-2-program-composition.md](algebra-2-program-composition.md). The sketch below is the overview;
> that file is the contract step 6 implements against.

**Purpose, as an observation.** A program exists to be run: `run : Program[I, O] => I => M[O]`, a Kleisli arrow
`I ⇝ O`. `M` involves the LLM, so `run` is not pure and laws do not hold pointwise on outputs. The
denotational move (take the source of nondeterminism as input): a program denotes `LM => I => Result`. Laws
are then checked in one of three honest ways:

- **structurally** (on the program tree the combinator builds; no LM needed): Category, Mode monoid;
- with a **deterministic mock LM**: the augment round-trip;
- **distributionally** (holds for any LM output): selectBest reward-monotonicity.

**Constructors and combinators, with the structure each one is:**

```
predict(sig)  : Program[I, O]                                   -- terminal atom (one LM round-trip)
id[I]         : Program[I, I]                                   -- pure passthrough
p >>> q       : (Program[I, X], Program[X, O]) => Program[I, O]       -- CATEGORY
augment[n, T] : Program[I, O] => Program[I, (n: T) *: O]           -- Thought / CoT
mode(m)       : Program[I, O] => Program[I, O]                     -- MONOID (middleware)
selectBest    : (Program[I, O], n, reward, threshold) => Program[I, O]
parallel      : (Program[I, A], Program[I, B]) => Program[I, (A, B)]  -- ordered fan-out / &&&
lift          : (I => O) => Program[I, O]                              -- parameter-free local transform
mapOutput     : (O => B) => Program[I, O] => Program[I, B]             -- preserves final raw prediction
contramapInput: (J => I) => Program[I, O] => Program[J, O]
dimap         : (J => I, O => B) => Program[I, O] => Program[J, B]
fanout        : (Program[I, A], Program[I, B]) => Program[I, (A, B)]   -- honest name for ordered `parallel`
split         : (Program[I, A], Program[J, B]) => Program[(I,J),(A,B)] -- ordered independent inputs
recover       : (RecoveryPolicy, Program[I,O]) => Program[I,O] => Program[I,O]
loop          : (step, env, done) => Program[I, O]              -- the agentic scheme
```

**Laws the known structures hand you for free:**

```
Category    id >>> p = p = p >>> id        (p >>> q) >>> r = p >>> (q >>> r)
Mode monoid mode(m1 ⊕ m2) = mode(m1) ∘ mode(m2)     mode(idMode) = id     ⊕ associative
Fan-out     parallel is left-to-right and fail-fast; associative on values up to tuple reassociation
augment     base(run(augment[r](p))(i)) = run(p)(i)         -- the prepended field is extra (round-trip)
            augment[r] ∘ augment[r] = augment[r]            -- idempotent (OutputAugmentation.Contains)
argMax      exhaustive selection returns a maximum under a finite score and explicit tie-break
selectBest  ordered search additionally observes rollout controls, early-stop policy, and failures
```

**Symmetry (free features).** `augment` opening (prepend, conditions the answer) has a dual: `augment`
closing (append, a self-check); dspy4s has only opening. `selectBest` (pick-one of N) is the dual of
`ensemble` / majority (reduce N). `>>>` (dependent) is dual to `parallel` (independent).

### The executable program carrier is ordered, not Markov

`ModuleHom[I, O]` contains fail-fast errors, callbacks, tools, LM calls, trace, and usage. Its independent-input
operation runs the left program before the right program, so execution order is observable. Consequently the
carrier does **not** form a symmetric monoidal, CD, or Markov category.

The reusable executable interface is
[`OrderedTensorOps[Hom]`](../../modules/programs/src/main/scala/dspy4s/programs/para/OrderedExecution.scala), with the
`given orderedProgram` instance over `ModuleHom`. It deliberately exposes operations without asserting tensor
interchange, symmetry of effects, or discard naturality:

```
>>>            sequential value composition                         Category / AndThen        ✅
tensor a,b     ordered independent inputs, paired outputs            Tensor / Compose.tensor   ✅ (Module level)
copy           duplicate the input I => (I, I)                       Copy / Compose.copy       ✅
discard        drop a value I => ()                                  Discard / Compose.discard ✅
swap           exchange two value components                         Swap / Compose.swap       ✅
fanout a,b     ordered fan-out: same input = copy then split         Both / Compose.fanout     ✅
```

The former tensor-interchange claim changed execution from `f1, g1, f2, g2` to `f1, f2, g1, g2`. With `g1`
failing `"g1"` and `f2` failing `"f2"`, the two sides return different errors. That counterexample is executable
in `OrderedTensorOpsSuite`. The abstract `CDCategory` law vocabulary remains for a future commutative
denotational carrier such as stochastic kernels, but there is no `CDCategory[ModuleHom]` instance.

Sequential association is made stable by lifecycle-transparent structural nodes: `Identity`, `AndThen`,
`Both`, `Tensor`, `Copy`, `Discard`, `Swap`, and `Moded` add no callbacks, trace, or history of their own. Leaf
modules remain fully instrumented. Raw prediction evidence composes with an associative `followedBy` operation:
rightmost produced values/completions win, usage adds, and `RawPrediction.empty` is the identity. Consequently
both unit laws and associativity hold on the complete `Prediction`, including the public `ProgramRunner`
observation. `ComposeLawSuite` pins this plus a final leaf that reads the live trace.

The load-bearing facts, all executable:

- **`fanout = copy >>> split`.** Fan-out is copy-then-split; `fanout(a, b) = Δ >>> (a ⊗ b)`
  (`ComposeLawSuite`). This is why `fanout` shares one input while `split` takes two.
- **Copy classifies deterministic behavior but is not a general law.** `h >>> fanout(f, g)` runs `h` once (shared);
  `fanout(h >>> f, h >>> g) = Δ >>> (h ⊗ h) >>> (f ⊗ g)` runs it twice. These agree iff
  `h >>> copy = copy >>> split(h, h)`, i.e. iff `h` is deterministic under the chosen observation. Both sides
  are pinned: the positive case for a pure `h` in
  `ComposeLawSuite`, the failure (an effect-observing `h` run once vs twice; params 3 vs 4) in
  `ParaCategoryLawSuite`. This remains useful without claiming a Markov structure for execution.

**Why `split` lives at the Module level, not on `ParaCategory`.** `fanout` lifts into the packaged `Program` /
`ParaCategory` category because both legs share one input, so the pair reuses that input's decoder. The split's
input `(I, J)` has no canonical single-record decoder (two independent inputs, one flat `Example` record), so
`split` stays a `Module`-level combinator. That asymmetry is itself informative: the packaged (optimizable)
category naturally supports fan-out, and the raw split is the structural op beneath it.

**Not adopted (deliberately).** The higher-kinded generalization from `typista.org`'s categories article —
`CategoryTC1[P[F[_]], Hom[F[_], G[_]]]` (objects = endofunctors) and the internal-monoid tower that recovers
`Monad` as a monoid in `End(X)` — does not apply: dspy4s's objects are types, not functors, and its monads
(`Either`, later `CIO`) are ambient and used, never re-derived. The useful generalization here is instead an
ordered Arrow/premonoidal vocabulary for executable effects. A separate commutative denotational carrier would
be required before monoidal or Markov coherence becomes meaningful.

**Ugly laws in the current code = the work to do.**

- ~~No `>>>`: programs are sequenced with hand-written `for`-comprehensions.~~ **Resolved (step 6.2).** `>>>`
  (`AndThen`) + `id` (`Identity`) + `parallel` (`Both`) are first-class in `Compose.scala`; the Category buys
  associativity + identity (on the threaded value) and a real pipe, `parallel` the independent dual.
- ~~`Refine` reimplements `selectBest` inline.~~ **Resolved (step 6.1).** Both now reduce to the shared
  `AttemptSelection.bestOf`: `BestOfN` is the independent instance (no feedback), `Refine` the sequential
  instance (feedback = advice→adapter hook). The law `refine = bestOf + critic-hint` is structural.
- ~~`ReAct` / `CodeAct` / `RLM` are one `loop` written three times.~~ **Resolved (step 6.3).** All three (and
  PoT's `retryUntil`) run on the shared `AgentLoop.run` bounded-iteration primitive; ReAct/CodeAct also share
  `TrajectoryAgent.runAndExtract` (loop + extractor). Code-truth: the `env.step`/`classify`/`render`
  decomposition was rejected (done-detection is entangled with the action); each module keeps its own step
  closure. `ProgramOfThought` is `retryUntil` (regenerate-on-error), not the agent loop and not `feedback`.

The conclusion: the step-6 plan and this algebra are the same object. ADD supplies the vocabulary (Category,
Monoid, ordered fan-out) and the laws or explicit non-laws that govern each carrier.

---

## Already algebraic vs ad-hoc (current state)

- **Clean / law-shaped:** `Either[DspyError, A]` (errors as values, a monad), `CIO` (monad),
  `decodePrepended` (an augment with a round-trip law), `SignatureOps` (algebra 1, laws above),
  `Aggregation.majority` (a semilattice-flavored reduce).
- **Ad-hoc (ADD would refactor):** `BestOfN` / `Refine` / `MultiChainComparison` sharing no middleware (no
  `Mode` monoid). (Resolved already: `Refine` reimplementing the selection loop — now the shared
  `AttemptSelection.bestOf`, step 6.1; sequential composition — now `>>>`/`AndThen`, step 6.2; the three
  hand-written agent loops + PoT's inline retry — now the shared `AgentLoop.run` / `TrajectoryAgent`, step 6.3;
  the `SignatureLayout` unique-name `require` — now closed by construction.)

## Testing discipline (how the laws become properties)

From `SignatureOpsLawSuite` (the template for any further law suite):

- Generate via the **public constructor** (`SignatureLayout.create`), never raw data cases, so only
  buildable terms are tested and invariants are maintained.
- Use **small, overlapping name pools** so the dedup / idempotence branches are actually hit; keep input and
  output pools disjoint so the layout's uniqueness invariant holds by construction.
- Compare with **observational equality** (`in` / `out` / `instr` / `name`), not structural `==`: L3
  reorders the underlying field vector while leaving every observation identical. `sameElements` keeps
  strict-equality off the call site.
- Each law is one `Prop.forAll`; a forgotten constructor would surface as a contradictory law.

## Status and next

- Algebra 1: specified, laws stated as `@Law`/`IsEq` statements on `SignatureOps.laws` and executed by
  `SignatureOpsLawSuite` (10 properties, commit `7004627`), and the unique-name `require` retired (uniqueness
  now closed by construction; see the resolved critique above).
- Algebra 2: specified (grilled). Operation + law set, per-module reduction recipes, and sequencing in
  [algebra-2-program-composition.md](algebra-2-program-composition.md). No pre-implementation spike required;
  the kyo-compat CIO migration is a separate, non-blocking later phase.
  - **6.1 done** (commit `96c9072`): `bestOf` extracted as `AttemptSelection.bestOf`; `BestOfN` + `Refine`
    reduced onto it; `AttemptSelectionLawSuite` pins the reducer laws. Code-truth correction recorded: PoT is
    `retryUntil`, not `feedback`.
  - **6.2 done** (commit `60d2ea5`, later law-audit correction): `id` / `>>>` / `parallel` in `Compose.scala`;
    `ComposeLawSuite` covers value-category laws, lifecycle-transparent association, ordered fan-out, and
    addressability. `fanout` is Arrow-like ordered pairing, not an Applicative or the batch-executor `Parallel`;
    `parallel` remains its compatibility name.
  - **6.3 done** (commit `6faa94e`): `AgentLoop.run` + `TrajectoryAgent.runAndExtract`; ReAct/CodeAct/RLM/PoT
    all reduced onto them; `AgentLoopLawSuite` pins the primitive. Code-truth correction recorded: the
    `env.step`/`classify`/`render` decomposition was rejected; each module keeps its own step closure.
  - **6.4 done** (commit `31aecbd`): `decodeAugmented` (typed field via a pluggable reader + post-decode hook);
    `decodePrepended` is its String/identity instance. Closing position left additive (no consumer).
  - **6.5 done** (commit `dca35e9`): `Mode` (the `Controls => Controls` monoid) + `Moded` + `Compose.mode`;
    `ModeLawSuite` pins the monoid + identity + pass-through. Execution-wrapping modes left additive.
  - **Algebra 2 is complete.** Remaining work is optional/additive: the CIO substrate migration (kyo-compat),
    `augment` closing position, execution-wrapping modes. Usage accumulation on `>>>` is now part of the lawful
    `RawPrediction.followedBy` envelope operation.
  - **Para prototype** (commits `9d4b5cd`, `8d7e009`, `d1d38d0`, `876442a`), functionally complete: the
    optimizer-addressability layer identified as the Para construction (morphism = parameters x shape;
    composition concatenates parameters; `replace` is the reparameterization 2-cell; homogeneous
    homogeneous `OptimizableParameters` values make `Vector` the exact, not approximate, parameter object, while layout/module
    metadata remains read-only). Prototyped as
    `dspy4s.programs.para.ParaCategory[P[_], Hom]` (the CategoryTC constraint-parameterized shape) over packaged
    `Program` morphisms, with objects constrained by `RecordCodec` exactly where evidence is synthesized (`id`);
    `Program` packages addressability while its domain object supplies a sealed canonical codec, giving uniform
    `OptimizableTraversal[Program]` + `ProgramRunner[Program]`, so `new COPRO[Program[I, O]]` works directly, including on upcast
    values, composed pipelines, and id-headed pipelines. Two compile-time gates: no `OptimizableTraversal`, no `Program`;
    no `RecordCodec`, no `id` (a genuine category over codec-equipped objects, a semicategory elsewhere).
    Decoding is OBJECT-side (stage 4, after the lawfulness-review arc that first replaced `unsafeOf` with a
    `ProgramInput` coherence law and then deleted `ProgramInput` outright): `Program.of` and the record-boundary
    runner require `RecordCodec[I]` at the domain, nothing decode-related is packaged, and an
    incoherent per-program decoder is unrepresentable (compile gates pin both former vehicles). Named-tuple
    inputs derive their codec through the same `SchemaTupleShape` path the signature macros use; bare-module
    running is signature-backed `ProgramRunner` instances (no identity in sight, no coherence question);
    runtime-string signatures enter through the `DynamicSignature` bundle, whose parses mint fresh
    codec-equipped types and whose generic `stable` view preserves them across aliases (see
    `algebra-2-program-composition.md`).
    `OptimizableTraversal.derived` now requires evidence for every product field; deliberately parameter-free field types
    opt in with `OptimizableTraversal.empty`, so an omitted learnable subtree can no longer disappear silently.
    Pinned by `ParaCategoryLawSuite` / `ParaCompileSuite`. Adoption as the public optimizer entry-point API is
    deferred to the CIO phase; see the "Para formalization" section of the step-6 spec.
  - **Law-statement adoption** (commits `446ccb6`, `7004627`, `d7ab930`, from jpablo/math-with-scala): laws are
    now stated ON the structures as `@Law` methods returning `IsEq` (`core.algebra.Laws`) and executed by the
    suites under per-law honest observations. Applied to the Para structures (`446ccb6`) and retrofitted onto
    Algebra 1 (`SignatureOps.laws`) and the `Mode` monoid (`7004627`) — the latter adding the raw monoid laws
    (associativity / identity), previously untested (only the mode-action homomorphism law was). Newly named
    structures from the Para pass: the delooping of the parameter monoid as an explicit `Category` instance;
    `ReadFunctor` (`OptimizableTraversal.read` as a functor value; its functor laws — preserves id + composition — are
    carried on the `CategoryFunctor` trait and are exactly the Para projection laws); and
    `fanout` as ordered shared-input pairing, with `parallel` retained as a compatibility name and the copy NON-law
    (sharing vs re-running an effectful `h` differ, in
    behavior and in parameters) pinned as an executable counterexample. The `IsEq`/`@Law` vocabulary is now the
    uniform law-statement style across the codebase; every use must name an observational equality preserved by
    its public combinators.
  - **Abstract-structure traits** (commits `d7ab930`, `d3be8e1`): following the `Category` / `ParaCategory` pattern (an
    abstract trait carrying the laws + `given` instances), monoids get an explicit `core.algebra.Monoid[M]`
    trait (`empty` / `combine`, laws on the trait). Instances: `given Monoid[Mode]` (the endomorphism monoid on
    `Controls`, replacing `Mode`'s loose companion `@Law` methods) and `given Monoid[Vector[OptimizableParameters]]`
    (`d3be8e1`, the parameter monoid — codomain of the `OptimizableTraversal` homomorphism). The delooping is generalized
    to `delooping[M](using Monoid[M]): Category[AnyObject, Delooped[M]]` ("a monoid is a one-object category"), so
    `paramsDeloop` is now literally the parameter monoid delooped rather than an ad-hoc `Category`. Algebra 1's two
    commuting endomorphism submonoids are also explicit `Monoid` instances now (commit `1f837a8`:
    `InputTransform` / `OutputTransform` over layout endomorphisms + the `submonoidsCommute` cross-law) — so
    every monoid in the codebase is a named instance, none left implicit. The same pattern now covers optics:
    `core.algebra.Lens[S, A]` states get-put / put-get / put-put on the trait, and `OptimizableLeaf[P] extends
    Lens[P, OptimizableParameters]` inherits them, adding only the `frame` law (writing parameters never changes the
    read-only `OptimizableMetadata`). The focus had to be carved down to exactly the writable triple
    (instructions / demos / config) before these laws could hold; `OptimizableParametersSuite` executes all four
    statements per leaf instance (`DynamicPredict`, `Predict`, `ChainOfThought`).
  - **Ordered tensor operations** (original commits `508a8e6`, `71c8880`; corrected after an effectful-law
    audit): `split` (`tensor` compatibility name) / `copy` / `discard` / `swap` remain useful `Compose` generators and
    `fanout = copy >>> split`, but unrestricted `ModuleHom` now implements `OrderedTensorOps`, not
    `CDCategory`. `OrderedTensorOpsSuite` pins the fail-fast interchange counterexample (`g1` versus `f2`),
    while `ComposeLawSuite` pins lifecycle-transparent association. The abstract `CDCategory` target remains
    available for a future commutative denotational carrier.
  - **Typed inner predicts** (the class-A conversion, then the class-B bridge): every composite now runs typed
    inner `Predict`s — Refine's critic, ReAct, CodeAct, RLM, ProgramOfThought (statically-shaped layouts kept
    verbatim, `InputAugmentation.appendedStringInput` for appended input fields, lenient hand-written or derived
    shapes on the output side, `OutputAugmentation.prependedStringShape` for the reasoning-prepended extractors),
    and `MultiChainComparison`, whose runtime-arity attempt block rides a per-instance opaque carrier
    (`appendedStringInputs`: validation constructs the path-branded value and the shape total-encodes the `m`
    numbered fields). Only layouts that exist purely as runtime values
    still construct a `DynamicPredict` inside the framework: the optimizers' own helper generations, the
    evaluation judge, and `Predict.erase`. See `algebra-2-program-composition.md` for the recipe.
