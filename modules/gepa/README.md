# dspy4s `gepa`

GEPA — **Ge**netic-**Pa**reto reflective prompt optimization. Instead of bootstrapping demos or sampling
instruction rewrites blindly, GEPA *reflects*: it reads what a program actually did on a few examples — the
inputs, the generated outputs, and natural-language feedback on what went wrong — and asks a strong "reflection
LM" to rewrite the failing instruction to fix it. Over many such mutations, guided by a Pareto frontier and
occasional crossover between lineages, the program's instructions evolve.

This is a self-contained port of the external Python `gepa` engine plus the dspy4s adapter that bridges
programs into it (PORT_GAPS G-12).

## What makes GEPA different

The other optimizers in [`optimize`](../optimize/README.md) take a plain `Metric` (a `Double`). GEPA needs
two extra things:

- A **`FeedbackMetric`** — a score *plus* natural-language feedback. The feedback is the "gradient" of the
  search: concrete, actionable text ("the answer was wrong, the correct one is X", "the format was invalid")
  is what the reflection LM rewrites instructions against. A bare score teaches it little.
- A **reflection LM** — usually a stronger model than the one running the task — that does the rewriting.

```scala
val gepa = new Gepa[MyProgram](
  metric = myFeedbackMetric,      // FeedbackMetric: score + feedback
  reflectionLm = strongModel,     // rewrites instructions from feedback
  config = GepaConfig(maxMetricCalls = 200)
)

gepa.compile(student, trainset, valset) match  // Either[DspyError, GepaResult[MyProgram]]
  case Right(result) =>
    result.bestProgram   // the evolved program
    result.bestScore     // its mean validation score
  case Left(error) =>
    error.message        // GEPA cannot find the calls of each predictor (see "Reflective records")
```

GEPA evolves **instructions only** — one per predictor (component). The program's structure (fields, demos,
wiring) is fixed. A `Candidate` is therefore a `Map[OptimizableId, Option[String]]` — the genome — and
the seed candidate is the program's current instructions. The option preserves the distinction between no
instruction (`None`) and an explicitly empty instruction (`Some("")`).

## The signal: `FeedbackMetric`

```scala
trait FeedbackMetric extends Metric:
  def feedback(
      example: Example, prediction: RawPrediction, trace: Vector[TraceEntry],
      component: Option[String], componentTrace: Vector[TraceEntry]
  )(using RuntimeContext): Either[DspyError, ScoreWithFeedback]
```

Feedback is requested at two granularities:

- **Program level** (`component = None`) — overall score + program-wide feedback for one example. Drives
  candidate scoring and acceptance.
- **OptimizableLeaf level** (`component = Some(name)`, with the one call of that predictor that GEPA selected) —
  feedback aimed at one named predictor. The reflection loop assembles each component's reflective dataset from these.

It `extends Metric` (its `score` is the program-level feedback's score) so a single instance is both the
scoring metric the adapter feeds to `Evaluate` and the source of reflection feedback. A wrong prediction is a
**low score with feedback**, not a `Left` — GEPA learns from those; `Left` is reserved for unrecoverable
scoring failures.

## The mechanics

### Adapter

`GepaAdapter` bridges a dspy4s program into the engine (Python's `DspyAdapter`). It applies a `Candidate` to
the program (rewriting each optimizable leaf's instruction by stable structure ID) and evaluates a batch in one of two
modes:

- **Scores-only** (acceptance / full-eval fast path) — runs through `Evaluate` for scores.
- **With traces** (the reflective path) — each example runs in an *isolated* context with a fresh trace and
  failure-trace capture on, so its trajectory is exactly its own and a parse failure stays in the trace
  (the raw, unparseable response is captured).

### Reflective records

`makeReflectiveDataset` gives each component one record for each trajectory at most. It is a port of the
selection in upstream `make_reflective_dataset`:

1. It takes the calls of the component from the trace. A predictor writes its key (its module name and its
   signature structure) into each trace entry that it records, so the position of an entry is not important. A
   predictor that runs in a loop (ReAct, RLM) gets its own calls.
2. With `addFormatFailureAsFeedback = false` (the default, as upstream), the calls that failed do not count.
3. If a call failed, the first such call gives the record: the raw response of the model and the output
   structure to follow. The user metric gets no call for it.
4. Otherwise, a run that failed gives no record. A run that passed gives the record of one call, selected at
   random with the seed. The metric gets that call as `componentTrace`.

`compile` returns a `Left(ValidationError)` for a program in which GEPA cannot find the calls of each predictor:

- Two predictors have the same module name and the same signature structure. `compile` rejects them before any
  LM call. Give each predictor a name, for example `Predict(signature, name = Some("second_pass"))`.
- A predictor type records no key in its trace entries. Its module must use `ModuleLifecycle.predictor`.

### The search loop (`GepaEngine`)

`optimize` seeds the state by full-evaluating the starting candidate on the valset, then loops until the
metric-call budget (`maxMetricCalls`) is exhausted. Each iteration is either:

1. **A reflective mutation.** A `CandidateSelector` (Pareto by default) picks a parent from the pool; a
   `ComponentSelector` (round-robin) picks which component to evolve; a minibatch is sampled from the
   trainset; the adapter evaluates the parent on it *with traces*; the per-component reflective dataset
   (`ReflectiveRecord`s: inputs / generated outputs / feedback) is built; `InstructionProposer` asks the
   reflection LM for a rewritten instruction; the new candidate is accepted if it improves on the minibatch and
   then full-evaluated on the valset and added to the pool.

   A component with no reflective record gets no reflection LM call. If no component gets a new instruction, the
   iteration makes no proposal and no acceptance evaluation, as upstream. The metric calls of the minibatch
   evaluation stay counted, so the budget decreases and the loop ends.

2. **A merge (crossover).** When enabled and one is scheduled, `MergeProposer` combines two Pareto-frontier
   descendants of a common ancestor: for each component it takes the version from whichever descendant
   *improved* it, stacking the two lineages' complementary gains. Merge only fires for multi-component programs
   with branching lineage (a single-component program can never satisfy the "desirable predictor" gate, so it's
   a safe no-op there).

The **budget** counts metric (evaluation) calls only — reflection-LM calls are free, matching gepa.

### Supporting pieces

- **`GepaState`** — the evolving pool: candidates, each candidate's per-validation-instance subscores, and
  lineage (`parents`), plus the running metric-call meter. The Pareto frontier is derived from the subscores;
  `bestIndex` is the candidate with the best mean validation score.
- **`MinibatchSampler`** — chooses each iteration's reflective minibatch. Default `EpochShuffled` (gepa's
  default) walks a per-epoch shuffle so every train example is used once per epoch before repeats; `RandomDraw`
  is the independent-draw v0 sampler.
- **`GepaEvalCache`** — memoizes scores-only evaluations by `(candidate, example)`, so a merged candidate's
  subsample eval is reused on its later full-eval, and re-scored pairs are free. Only actual (uncached) evals
  are charged against the budget.
- **`GepaStatePersistence`** — JSON save/load of the search state (pool, subscores, lineage, meter) for
  resuming an interrupted run. The eval cache, RNG position, and merge schedule are not persisted; a resumed
  run keeps every discovered candidate (no budget re-spent) and continues from that pool.

## Config highlights (`GepaConfig`)

| Field | Meaning |
|-------|---------|
| `maxMetricCalls` | budget — total metric (eval) calls before stopping (required) |
| `reflectionMinibatchSize` | examples per reflective minibatch (default 3) |
| `candidateSelector` / `componentSelector` | parent selection (Pareto) and component selection (round-robin) |
| `batchSampler` | `EpochShuffled` (default) or `RandomDraw` |
| `useMerge` / `maxMergeInvocations` | enable crossover (default on) and cap accepted merges |
| `skipPerfectScore` / `perfectScore` | skip reflecting on already-perfect minibatches |
| `stopOnPerfectScore` | opt-in early stop when the best candidate is perfect (off, for gepa parity) |
| `addFormatFailureAsFeedback` | a predictor call that failed gives the reflection LM a record (off, as upstream) |
| `seed` | deterministic for a fixed seed |

## Source layout

| File | Contents |
|------|----------|
| `Gepa.scala` | user-facing optimizer; wires adapter + engine |
| `contracts/FeedbackMetric.scala` | `FeedbackMetric`, `ScoreWithFeedback` |
| `GepaAdapter.scala` | program ↔ engine bridge (apply candidate, evaluate, capture traces) |
| `GepaEngine.scala` | the search loop + `GepaConfig` / `GepaResult` |
| `GepaState.scala` | candidate pool, subscores, lineage, Pareto frontier |
| `Candidate.scala` | the genome (component → optional instruction) + seed/apply |
| `InstructionProposer.scala` | reflective mutation (prompt the reflection LM) |
| `MergeProposer.scala` | crossover over common ancestors |
| `MinibatchSampler.scala` | minibatch sampling strategies |
| `ReflectiveRecord.scala`, `EvaluationBatch.scala` | reflective dataset record + batch/trajectory results |
| `GepaEvalCache.scala` | scores-only memoization |
| `GepaStatePersistence.scala` | run-state JSON save / load |

## Relation to dspy

GEPA uses the shared `OptimizableStructure` / `ProgramRunner` introspection and execution spines also used by
COPRO/MIPROv2, and depends on `evaluate` for scoring. It is *not* a `Teleprompter` — its `compile` takes a
`FeedbackMetric` and a reflection LM and returns an `Either[DspyError, GepaResult[P]]` rather than an
`OptimizationReport[P]`.
Deferred relative to upstream: multi-objective frontiers and run-dir resume beyond the single-objective
instance frontier (PORT_GAPS G-12). Per the [module-purity principle](../../README.md), the program stays an
immutable pure function — its instructions are the only thing the search rewrites, always into fresh copies.
