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

It `extends Metric` (its `score` is the program-level feedback's score), so one instance is a plain `Metric`
for `Evaluate` and the source of the reflection feedback. A wrong prediction is a
**low score with feedback**, not a `Left` — GEPA learns from those; `Left` is reserved for unrecoverable
scoring failures.

### Objective scores

A metric can also give scores for named objectives, for example accuracy and cost:

```scala
ScoreWithFeedback(score = 0.75, feedback = "...", objectiveScores = Map("accuracy" -> 1.0, "cost" -> 0.5))
```

GEPA reads the objective scores of the program-level feedback only. A larger objective score is better. GEPA
keeps them for each candidate and each validation example (`GepaState.valObjectiveScores`). The acceptance of a
candidate and the best candidate of a run use `score` only.

`GepaConfig.frontierType` selects the Pareto fronts that candidate selection and merge use:

| Type | One front for each | Value of a candidate |
|------|--------------------|----------------------|
| `Instance` (default) | validation example | its score on the example |
| `Objective` | objective | the mean of its objective score, over the examples that have one |
| `Hybrid` | validation example, and objective | the two values above |
| `Cartesian` | pair of a validation example and an objective | its objective score on the example |

A front is the set of the candidates with the largest value. A candidate that is good on one objective thus
stays a possible parent, also when another candidate has the larger score on each validation example.

Each type other than `Instance` needs objective scores. If the seed candidate has none, `compile` returns a
`Left` after the seed evaluation. A later candidate with no objective score (each of its validation runs
failed) stays in the pool, and it is in no objective front. Upstream stops the run in that case.

`GepaResult.state` gives the data of a run: `objectiveAggregates(i)`, `objectiveBest`, `objectiveFront`, and
`fronts`.

## The mechanics

### Adapter

`GepaAdapter` bridges a dspy4s program into the engine (Python's `DspyAdapter`). It applies a `Candidate` to
the program (rewriting each optimizable leaf's instruction by stable structure ID) and evaluates a batch in one of two
modes:

- **Scores-only** (acceptance / full-eval fast path) — each example runs in an isolated context, and the metric
  gets one call with an empty trace. A run that fails has the failure score, and each run that passes keeps its
  score.
- **With traces** (the reflective path) — each example runs in an *isolated* context with a fresh trace and
  failure-trace capture on, so its trajectory is exactly its own and a parse failure stays in the trace
  (the raw, unparseable response is captured).

The two modes keep the objective scores that the metric gives for each example.

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
   minibatch is sampled from the trainset; the adapter evaluates the parent on it *with traces*; a
   `ComponentSelector` (round-robin) picks which component to evolve; the per-component reflective dataset
   (`ReflectiveRecord`s: inputs / generated outputs / feedback) is built; the instruction proposer asks the
   reflection LM for a new instruction; the new candidate is accepted if it improves on the minibatch and
   then full-evaluated on the valset and added to the pool.

   A component with no reflective record gets no reflection LM call. If no component gets a new instruction, the
   iteration makes no proposal and no acceptance evaluation, as upstream. The metric calls of the minibatch
   evaluation stay counted, so the budget decreases and the loop ends.

   A proposal that fails gets one more call, as upstream. A second failure gives no proposal for the iteration.
   With more than one selected component, the failure of one component drops the new texts of all of them.

   Each candidate has a round-robin pointer: the index of the component that its next mutation changes. The
   pointers are part of `GepaState`. A new candidate (from a mutation or from a merge) takes the largest pointer
   of its parents. The pointer of a parent has already moved at that time, so the child continues with the next
   component.

   The component selector runs after the perfect-score check, as upstream. An iteration that this check skips
   does not move the pointer of the parent. Each other iteration moves it, also when the iteration makes no
   proposal.

2. **A merge (crossover).** When enabled and one is scheduled, `MergeProposer` combines two Pareto-frontier
   descendants of a common ancestor: for each component it takes the version from whichever descendant
   *improved* it, stacking the two lineages' complementary gains. Merge only fires for multi-component programs
   with branching lineage (a single-component program can never satisfy the "desirable predictor" gate, so it's
   a safe no-op there).

   The merge search makes 10 attempts at most, and each attempt draws 10 pairs of candidates at most, as
   upstream. The search continues with the next attempt in these cases:

   - The attempt finds no pair with a valid common ancestor.
   - The merge of the found pair was already made.
   - The two candidates share fewer validation examples than `mergeValOverlapFloor` (default 5, as upstream).

   Each candidate has a score for each validation example. Thus a validation set with fewer than 5 examples
   gives no merge with the default value. Set a lower `mergeValOverlapFloor` for such a set.

The **budget** counts metric (evaluation) calls only — reflection-LM calls are free, matching gepa.

### More than one proposal in an iteration

By default an iteration makes one proposal: one parent, one minibatch, one child. `GepaConfig.proposalSampling`
gives an iteration more than one task. Each task has a parent and a minibatch, and it can give one proposal.

| `proposalSampling` | Tasks of one iteration |
|--------------------|------------------------|
| `Single` (default) | one parent, one minibatch |
| `SameParent(n)` | one parent, `n` minibatches |
| `Independent(n)` | `n` parent draws, each with its own minibatch |
| `PxN(p, n)` | `p` parent draws, `n` minibatches for each |

An iteration then has these stages, as upstream:

1. The engine samples the tasks in order. A later task of an iteration takes the next window of the minibatch
   sampler.
2. It evaluates the parents with traces in one batch. Tasks with the same parent and the same minibatch share one
   evaluation.
3. For each task in order, it applies the perfect-score check, selects the components, and builds the reflective
   records. Each task moves the round-robin pointer of its parent. Two tasks of one parent thus change two
   different components.
4. The proposer writes the new texts for each task, in task order.
5. The engine evaluates the children in one batch, each on the minibatch of its task.
6. `acceptance` and `proposalSelection` select the proposals that continue.
7. The engine removes a selected child that is equal to an earlier one, validates the others in one batch, and
   adds them to the pool in order. Each added candidate schedules one merge.

| `acceptance` | A proposal passes when |
|--------------|------------------------|
| `StrictImprovement` (default) | the minibatch sum of the child is larger than that of the parent |
| `ImprovementOrEqual` | it is larger or equal |

| `proposalSelection` | Selected proposals |
|---------------------|--------------------|
| `AllImprovements` (default) | each proposal that passes, in task order |
| `BestImprovement` | the one with the largest gain; the first one wins a tie |
| `TopK(k)` | the `k` with the largest gains, largest first |

`GepaAdapter.batchEvaluate` runs the evaluations of one batch in parallel. It divides the threads of the context
between the candidates: 8 threads and 3 candidates give 3, 3, and 2 threads. The reflection calls run one after
the other, in task order, so a run stays repeatable for a seed.

The budget stays a hard limit. An iteration starts only when its largest possible cost fits in the budget that
remains: the minibatch evaluations of each parent and each child, and one validation for each proposal that the
selection can take. For `n` tasks, a minibatch of `m`, and a validation set of `V`, that cost is
`2 × n × m + limit × V`, where `limit` is `n` for `AllImprovements`, 1 for `BestImprovement`, and at most `k` for
`TopK(k)`. Upstream checks the budget only at the start of an iteration, so it can go above the budget.

### The instruction proposer

An `InstructionProposer` writes the new instruction text of an iteration. `Gepa` and `GepaEngine` use
`DefaultInstructionProposer()` when you give no proposer. It is the port of dspy's `InstructionProposer`.

For each selected component, the default proposer runs one predictor call on the reflection LM:

- The inputs are the current instruction and the reflective records. The records go to the model as one JSON
  text, with the keys `Inputs`, `Generated Outputs`, and `Feedback` (`ReflectiveRecord.toJson`).
- The output is the field `new_instruction`. The proposer removes the white space at the two ends of the text.
- An empty text is a failure. The proposer does not read a fence, and it does not truncate a text.
- The proposer uses its own adapter, a `JSONAdapter` that rejects a response that is not a JSON object. The
  adapter of the context has no effect on these calls.

The options:

| Option | Effect |
|--------|--------|
| `additionalInstructions` | more requirements for each new instruction, for example a voice or a format |
| `baseInstructions` | replaces the instructions of the proposal call; the fields stay |
| `maxChars` | the largest number of Unicode code points of a new instruction |
| `adapter` | the adapter of the calls of the proposer |

With `maxChars`, a draft that is too long gets one call that asks the model to shorten it. A result that is
empty or still too long is a failure.

```scala
val gepa = new Gepa[MyProgram](
  metric = myFeedbackMetric,
  reflectionLm = strongModel,
  config = GepaConfig(maxMetricCalls = 200),
  instructionProposer = DefaultInstructionProposer(
    additionalInstructions = Some("Write instructions in imperative voice."),
    maxChars = Some(InstructionCharLimit(1500))
  )
)
```

To write a proposer of your own, implement `InstructionProposer.propose`. The engine calls it one time for an
iteration, with the parent candidate, the reflective records, and the selected components. The language model
of the context of that call is the reflection LM, so a predictor in the proposer needs no reference to it. The
trace entries of the call do not reach your context. The history entries do.

A reflective record holds values, not text. An input of the type `History` is under the key `Context`, and
that key is first.

The upstream options `skills` and `truncate_history_outputs` are not ported.

### Supporting pieces

- **`GepaState`** — the evolving pool: candidates, each candidate's per-validation-instance subscores and
  objective scores, lineage (`parents`), and round-robin pointer (`nextComponent`), plus the running metric-call
  meter. The Pareto fronts are derived from the scores (`fronts`, by `frontierType`); `bestIndex` is the candidate
  with the best mean validation score.
- **`MinibatchSampler`** — chooses each iteration's reflective minibatch. Default `EpochShuffled` (gepa's
  default) walks a per-epoch shuffle so every train example is used once per epoch before repeats; `RandomDraw`
  is the independent-draw v0 sampler.
- **`GepaEvalCache`** — memoizes scores-only evaluations by `(candidate, example)`, so a merged candidate's
  subsample eval is reused on its later full-eval, and re-scored pairs are free. Only actual (uncached) evals
  are charged against the budget.
- **`GepaStatePersistence`** — JSON save/load of the search state (pool, subscores, objective scores, frontier
  type, lineage, pointers, meter) for resuming an interrupted run. A resumed run with another `frontierType` is an
  error. The eval cache, RNG position, and merge schedule are not persisted; a resumed
  run keeps every discovered candidate (no budget re-spent) and continues from that pool. A state file that an
  older version wrote has no pointers. It loads with the pointer 0 for each candidate.

## Config highlights (`GepaConfig`)

| Field | Meaning |
|-------|---------|
| `maxMetricCalls` | budget — total metric (eval) calls before stopping (required) |
| `reflectionMinibatchSize` | examples per reflective minibatch (default 3) |
| `candidateSelector` / `componentSelector` | parent selection (Pareto) and component selection (round-robin) |
| `batchSampler` | `EpochShuffled` (default) or `RandomDraw` |
| `frontierType` | the Pareto fronts for selection and merge: `Instance` (default), `Objective`, `Hybrid`, or `Cartesian` |
| `proposalSampling` | the tasks of one iteration: `Single` (default), `SameParent(n)`, `Independent(n)`, or `PxN(p, n)` |
| `proposalSelection` / `acceptance` | which evaluated proposals go to the pool (all strict improvements by default) |
| `useMerge` / `maxMergeInvocations` | enable crossover (default on) and cap accepted merges |
| `mergeValOverlapFloor` | the smallest number of validation examples that the two candidates of a merge share (default 5, as upstream) |
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
| `GepaState.scala` | candidate pool, subscores, objective scores, lineage, Pareto fronts (`FrontierType`, `FrontierKey`) |
| `Candidate.scala` | the genome (component → optional instruction) + seed/apply |
| `InstructionProposer.scala` | the `InstructionProposer` trait and `DefaultInstructionProposer` (reflective mutation) |
| `GepaProposals.scala` | `ProposalSampling`, `ProposalSelection`, `ProposalAcceptance`, `Proposal` |
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
Not ported from upstream: the full resume of a run, the stop reason of a run, and the `auto` budgets of the
facade (PORT_GAPS G-44, G-45). Per the [module-purity principle](../../README.md), the program stays an
immutable pure function — its instructions are the only thing the search rewrites, always into fresh copies.
