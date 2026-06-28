# dspy4s algebras (algebra-driven design notes)

**Status:** running record. Algebra 1 (signature transforms) is specified and its laws are property-tested;
algebra 2 (program composition) is the step-6 design frontier, sketched here.
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

**Laws** (`SignatureOps.scala`; property-tested in `SignatureOpsLawSuite`):

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
and output-combinators are two **commuting submonoids**. Within a cohort the generators are idempotent by
name but order-sensitive (two `prependOutput`s do not commute), which is the signature of an
insertion-ordered, name-keyed map.

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

The algebra over programs (`Module[I, O]`, or a `Prog[I, O]`). This is the design target for step 6.

> **Now specified.** The five open forks were resolved by a design grill, and the full operation + law set,
> the per-module reduction recipes (acceptance criteria), and the implementation sequencing live in
> [algebra-2-program-composition.md](algebra-2-program-composition.md). The sketch below is the overview;
> that file is the contract step 6 implements against.

**Purpose, as an observation.** A program exists to be run: `run : Prog[I, O] => I => M[O]`, a Kleisli arrow
`I ⇝ O`. `M` involves the LLM, so `run` is not pure and laws do not hold pointwise on outputs. The
denotational move (take the source of nondeterminism as input): a program denotes `LM => I => Result`. Laws
are then checked in one of three honest ways:

- **structurally** (on the program tree the combinator builds; no LM needed): Category, Mode monoid;
- with a **deterministic mock LM**: the augment round-trip;
- **distributionally** (holds for any LM output): selectBest reward-monotonicity.

**Constructors and combinators, with the structure each one is:**

```
predict(sig)  : Prog[I, O]                                   -- terminal atom (one LM round-trip)
id[I]         : Prog[I, I]                                   -- pure passthrough
p >>> q       : (Prog[I, X], Prog[X, O]) => Prog[I, O]       -- CATEGORY
augment[n, T] : Prog[I, O] => Prog[I, (n: T) *: O]           -- Thought / CoT
mode(m)       : Prog[I, O] => Prog[I, O]                     -- MONOID (middleware)
selectBest    : (Prog[I, O], n, reward, threshold) => Prog[I, O]
parallel      : (Prog[I, A], Prog[I, B]) => Prog[I, (A, B)]  -- APPLICATIVE
loop          : (step, env, done) => Prog[I, O]              -- the agentic scheme
```

**Laws the known structures hand you for free:**

```
Category    id >>> p = p = p >>> id        (p >>> q) >>> r = p >>> (q >>> r)
Mode monoid mode(m1 ⊕ m2) = mode(m1) ∘ mode(m2)     mode(idMode) = id     ⊕ associative
Applicative parallel(pure a, p) ≅ map(p)(a, _)      parallel associative up to reassociation
augment     base(run(augment[r](p))(i)) = run(p)(i)         -- the prepended field is extra (round-trip)
            augment[r] ∘ augment[r] = augment[r]            -- idempotent (OutputAugmentation.Contains)
selectBest  selectBest(p, 1, reward, threshold) = p         -- n = 1 is identity
            selectBest returns argmax reward                -- monotone in reward
```

**Symmetry (free features).** `augment` opening (prepend, conditions the answer) has a dual: `augment`
closing (append, a self-check); dspy4s has only opening. `selectBest` (pick-one of N) is the dual of
`ensemble` / majority (reduce N). `>>>` (dependent) is dual to `parallel` (independent).

**Ugly laws in the current code = the work to do.**

- No `>>>`: programs are sequenced with hand-written `for`-comprehensions. The Category is hiding; making
  it first-class buys associativity + identity and a real pipe.
- `Refine` reimplements `selectBest` inline. The law `refine(p, critic, n) = selectBest(hintInject(critic)(p), n, reward)`
  is not structural today; that failure is the step-6 unification signal.
- `ReAct` / `CodeAct` / `RLM` / `ProgramOfThought` are one `loop` written four times (fails
  parsimony/orthogonality). The fix is `loop` as a combinator parameterized by an environment (the step-6
  `Effect`).

The conclusion: the step-6 plan and this algebra are the same object. ADD supplies the vocabulary (Category,
Monoid, Applicative) and the laws that turn a set of combinators into a law-governed algebra.

---

## Already algebraic vs ad-hoc (current state)

- **Clean / law-shaped:** `Either[DspyError, A]` (errors as values, a monad), `CIO` (monad),
  `decodePrepended` (an augment with a round-trip law), `SignatureOps` (algebra 1, laws above),
  `Aggregation.majority` (a semilattice-flavored reduce).
- **Ad-hoc (ADD would refactor):** sequential composition (no Category), the four hand-written agent loops
  (no `loop`), `Refine` reimplementing `selectBest` (a broken law), `BestOfN` / `Refine` sharing no
  middleware (no `Mode` monoid), the `SignatureLayout` unique-name `require` (an invariant that should be
  structural).

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

- Algebra 1: specified, laws property-tested (`SignatureOpsLawSuite`, 9 properties), and the unique-name
  `require` retired (uniqueness now closed by construction; see the resolved critique above).
- Algebra 2: specified (grilled). Operation + law set, per-module reduction recipes, and sequencing in
  [algebra-2-program-composition.md](algebra-2-program-composition.md). No pre-implementation spike required;
  the kyo-compat CIO migration is a separate, non-blocking later phase. Ready to implement step 6 against it.
