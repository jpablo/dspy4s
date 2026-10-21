# dspy4s `gepa`

`gepa` contains GEPA (Genetic-Pareto), an optimizer that improves the instructions of a program. GEPA runs the
program on a few examples, and a metric gives a score and a feedback text for each example. A reflection LM reads the
inputs, the outputs, and the feedback, and writes a new instruction. GEPA keeps the good candidates on Pareto fronts,
and it can merge two candidates. It uses the `core`, `programs`, `evaluate`, and `optimize` modules.

## Main concepts

- **Instructions only.** Each predictor of the program is one component. GEPA changes only the instruction of each
  component. The fields, the demos, and the structure of the program stay the same. A `Candidate` is a
  `Map[OptimizableId, Option[String]]`: `None` is no instruction, and `Some("")` is an empty instruction.
- **A feedback metric.** The optimizers of [`optimize`](../optimize/README.md) use a `Metric`, which gives a `Double`.
  GEPA uses a `FeedbackMetric`, which also gives a feedback text. The reflection LM uses this text to write the new
  instruction.
- **A reflection LM.** This model writes the new instructions. It is usually stronger than the model of the task.
- **Pareto fronts.** GEPA keeps a pool of candidates. By default, a front is the set of the candidates with the best
  score on one validation example. GEPA selects a parent from the fronts, so a candidate that is the best on one
  example only can be a parent.
- **A budget of metric calls.** One metric call is the evaluation of one example. The calls of the reflection LM do
  not count. The budget is a hard limit.

## Use

```scala
val gepa = new Gepa[MyProgram](
  metric = myFeedbackMetric,      // FeedbackMetric: score + feedback
  reflectionLm = strongModel,     // rewrites instructions from feedback
  config = GepaConfig(budget = GepaBudget.Auto(AutoBudget.Light))
)

gepa.compile(student, trainset, valset) match  // Either[DspyError, GepaResult[MyProgram]]
  case Right(result) =>
    result.bestProgram   // the evolved program
    result.bestScore     // its mean validation score
    result.stopReason    // why the run stopped
  case Left(error) =>
    error.message        // GEPA cannot find the calls of each predictor (see "Reflective records")
```

With no validation set, or with an empty one, GEPA uses the trainset for the Pareto scores.

`GepaConfig.budget` has one of three forms:

| `budget` | Metric calls |
|----------|--------------|
| `GepaBudget.MetricCalls(n)` | `n` |
| `GepaBudget.FullEvals(n)` | `n` × (the trainset size + the size of the validation set that you give) |
| `GepaBudget.Auto(level)` | the formula of `GepaBudget.auto`, for `Light`, `Medium`, or `Heavy` |

`Auto` uses the number of components, the number of candidates of the level (6, 12, or 18), and the size of the
validation set. With no validation set, it uses the size of the trainset. For one component, `Light`, and 4
validation examples, it gives 396. `GepaResult.maxMetricCalls` gives the budget in metric calls, and
`GepaResult.totalMetricCalls` gives the calls that the run used.

## The feedback metric

```scala
trait FeedbackMetric extends Metric:
  def feedback(
      example: Example, prediction: RawPrediction, trace: Vector[TraceEntry],
      component: Option[String], componentTrace: Vector[TraceEntry]
  )(using RuntimeContext): Either[DspyError, ScoreWithFeedback]
```

GEPA asks for feedback at two levels:

- **Program level** (`component = None`): the score and the feedback of the full program on one example. GEPA uses
  this score to compare candidates.
- **Component level** (`component = Some(name)`): the feedback for one component. `componentTrace` holds the one call
  of that component that GEPA selected. GEPA makes the reflective records from this feedback.

A `FeedbackMetric` is also a `Metric`: its `score` is the score of the program-level feedback. Thus you can also use
it with `Evaluate`. Write feedback that tells what is wrong, for example the correct answer or the format error. For a
wrong prediction, give a low score and a feedback text. Return a `Left` only when the metric cannot score the example.

### Objective scores

A metric can also give scores for named objectives, for example accuracy and cost:

```scala
ScoreWithFeedback(score = 0.75, feedback = "...", objectiveScores = Map("accuracy" -> 1.0, "cost" -> 0.5))
```

GEPA reads the objective scores of the program-level feedback only, and a larger objective score is better. The
acceptance of a candidate and the best candidate of a run use `score` only. `GepaConfig.frontierType` selects the
fronts for the parent selection and for the merge:

| Type | One front for each | Value of a candidate |
|------|--------------------|----------------------|
| `Instance` (default) | validation example | its score on the example |
| `Objective` | objective | the mean of its objective score, over the examples that have one |
| `Hybrid` | validation example, and objective | the two values above |
| `Cartesian` | pair of a validation example and an objective | its objective score on the example |

Each type other than `Instance` needs objective scores. If the seed candidate has none, `compile` returns a `Left`
after the seed evaluation. `GepaResult.state` gives the objective data of a run, for example
`objectiveAggregates(i)`, `objectiveFront`, and `fronts`.

## How a run works

`GepaEngine` first evaluates the seed candidate (the current instructions) on the validation set. Then each
iteration makes a reflective mutation or a merge.

A reflective mutation has these steps:

1. The candidate selector (`Pareto` by default) selects a parent from the fronts.
2. The minibatch sampler takes a minibatch from the trainset (3 examples by default).
3. `GepaAdapter` runs the parent on each example of the minibatch in an isolated context, and records the trace.
4. The component selector (`RoundRobin` by default) selects the component to change.
5. The adapter makes the reflective records of that component.
6. The instruction proposer gives the records to the reflection LM, and gets a new instruction.
7. The engine runs the child on the same minibatch. If the sum of its scores is larger than that of the parent, the
   engine evaluates the child on the validation set and adds it to the pool.

If the parent has the perfect score on each example of the minibatch, the iteration makes no proposal
(`skipPerfectScore`). An iteration with no new instruction also makes no proposal. Its minibatch evaluation still
uses the budget. If the proposer returns a `Left`, the engine calls it one more time. After a second `Left`, the
iteration makes no proposal, and `GepaState.failedProposals` counts it.

`GepaEvalCache` keeps the score of each pair of a candidate and an example. A pair in the cache uses no more metric
calls.

### Reflective records

A `ReflectiveRecord` holds the inputs of one call of a component, its outputs, and the feedback. Each run of an
example gives one record for each component at most:

- A predictor writes its key (its module name and its signature structure) into each trace entry. GEPA uses the key
  to find the calls of each component, also in a loop (`ReAct`, `RLM`).
- From a run that passed, GEPA selects one call at random, with the seed. The metric gets this call as
  `componentTrace`.
- By default, a call that failed and a run that failed give no record.
- With `addFormatFailureAsFeedback = true`, the first call that failed gives the record: the raw response of the
  model and the output structure to follow. The metric gets no call for it.

`compile` returns a `Left(ValidationError)` when GEPA cannot find the calls of each predictor:

- Two predictors have the same module name and the same signature structure. `compile` finds this before any LM call.
  Give each predictor a name, for example `Predict(signature, name = Some("second_pass"))`.
- A predictor type records no key in its trace entries. Its module must use `ModuleLifecycle.predictor` or
  `ModuleLifecycle.dynamicPredictor`.

### Merge

A merge (`MergeProposer`) takes two candidates of the fronts that have a common ancestor. For each component, the
merged candidate takes the instruction of the candidate that changed it. If the two candidates changed it, the
merged candidate takes the instruction of the candidate with the larger mean validation score.

Each candidate that a reflective mutation adds schedules one merge, and the engine tries it in the next iteration.
The engine evaluates the merged candidate on 5 validation examples. If its sum is at least the sum of the better
parent, the engine evaluates it on the validation set and adds it to the pool.

A merge needs a program with two components or more. The two candidates must also share at least `mergeValOverlapFloor`
validation examples (5 by default). Set a lower value for a validation set with fewer than 5 examples.

### More than one proposal in an iteration

By default, an iteration has one task: one parent and one minibatch. `GepaConfig.proposalSampling` gives an iteration
more tasks, and each task can give one proposal.

| `proposalSampling` | Tasks of one iteration |
|--------------------|------------------------|
| `Single` (default) | one parent, one minibatch |
| `SameParent(n)` | one parent, `n` minibatches |
| `Independent(n)` | `n` parent draws, each with its own minibatch |
| `PxN(p, n)` | `p` parent draws, `n` minibatches for each |

`acceptance` decides when a proposal passes. `proposalSelection` selects the proposals that go to the pool.

| `acceptance` | A proposal passes when |
|--------------|------------------------|
| `StrictImprovement` (default) | the minibatch sum of the child is larger than that of the parent |
| `ImprovementOrEqual` | it is larger or equal |

| `proposalSelection` | Selected proposals |
|---------------------|--------------------|
| `AllImprovements` (default) | each proposal that passes, in task order |
| `BestImprovement` | the one with the largest gain; the first one wins a tie |
| `TopK(k)` | the `k` with the largest gains, largest first |

The engine evaluates the parents, the children, and the selected candidates in parallel batches
(`GepaAdapter.batchEvaluate`). An iteration starts only when its largest possible cost fits in the budget that
remains.

## The instruction proposer

`DefaultInstructionProposer` is the default proposer. For each selected component, it runs one predictor call on the
reflection LM. The inputs are the current instruction and the reflective records as one JSON text
(`ReflectiveRecord.toJson`). The output field is `new_instruction`, and an empty instruction is a failure. The
proposer uses its own `JSONAdapter`, so the adapter of the context has no effect.

| Option | Effect |
|--------|--------|
| `additionalInstructions` | more requirements for each new instruction, for example a voice or a format |
| `baseInstructions` | replaces the instructions of the proposal call; the fields stay |
| `maxChars` | the largest number of Unicode code points of a new instruction |
| `adapter` | the adapter of the calls of the proposer |

With `maxChars`, a draft that is too long gets one call that asks the model to shorten it. A result that is empty or
still too long is a failure.

```scala
val gepa = new Gepa[MyProgram](
  metric = myFeedbackMetric,
  reflectionLm = strongModel,
  config = GepaConfig(budget = GepaBudget.MetricCalls(MetricCallCount(200))),
  instructionProposer = DefaultInstructionProposer(
    additionalInstructions = Some("Write instructions in imperative voice."),
    maxChars = Some(InstructionCharLimit(1500))
  )
)
```

To write your own proposer, implement `InstructionProposer.propose`. The engine calls it one time for each task,
with the parent candidate, the reflective records, and the selected components. The language model of the context
of that call is the reflection LM. The trace entries of the call do not go to your context. The history entries go
to it.

## Stop and resume

| `stopReason` | Cause |
|--------------|-------|
| `BudgetExhausted` | the run used each metric call of its budget |
| `IterationDoesNotFit(required, remaining)` | the next iteration needs more calls than remain, so it does not start |
| `PerfectScore` | `stopOnPerfectScore` is on, and the best candidate has the perfect score |

`GepaResult.iterations` gives the number of iterations. `GepaResult.state.failedProposals` gives the number of tasks
for which the proposer failed two times.

Set `GepaConfig.runDir` to save a checkpoint after the seed evaluation and after each iteration. A run with a
checkpoint in that directory continues it. Each random draw comes from the seed and the iteration index, so a resumed
run makes the same draws as a run with no stop. A checkpoint that cannot be read, or that does not agree with the
program or the configuration, gives a `Left`. The file then stays unchanged.

## Configuration (`GepaConfig`)

| Field | Description |
|-------|-------------|
| `budget` | the budget in metric calls (required) |
| `runDir` | the directory of the checkpoint (default none) |
| `reflectionMinibatchSize` | the number of examples in a minibatch (default 3) |
| `candidateSelector` | the parent selection: `Pareto` (default) or `CurrentBest` |
| `componentSelector` | the components to change: `RoundRobin` (default, one for each iteration) or `All` |
| `batchSampler` | `EpochShuffled` (default, each example one time before a repeat) or `RandomDraw` |
| `frontierType` | the Pareto fronts: `Instance` (default), `Objective`, `Hybrid`, or `Cartesian` |
| `proposalSampling` / `proposalSelection` / `acceptance` | the tasks of an iteration, and the proposals that go to the pool |
| `useMerge` / `maxMergeInvocations` | merge on or off (default on), and the number of accepted merges after which the engine schedules no new merge (default 5) |
| `mergeValOverlapFloor` | the smallest number of validation examples that the two candidates of a merge share (default 5) |
| `skipPerfectScore` / `perfectScore` | no reflection on a minibatch with the perfect score (default on, and 1.0) |
| `stopOnPerfectScore` | stop when the best candidate has the perfect score (default off) |
| `failureScore` | the score of an example when the run or the metric fails (default 0.0) |
| `addFormatFailureAsFeedback` | a call that failed gives a reflective record (default off) |
| `seed` | the seed of the random sources (default 0) |

## Source layout

| Files | Contents |
|-------|----------|
| `Gepa.scala`, `GepaEngine.scala` | the optimizer, the search loop, `GepaConfig`, and `GepaResult` |
| `GepaBudget.scala`, `GepaLimits.scala` | the budget, `AutoBudget`, `GepaStopReason`, and the refined number types, for example `MetricCallCount` |
| `contracts/FeedbackMetric.scala` | `FeedbackMetric` and `ScoreWithFeedback` |
| `GepaAdapter.scala`, `EvaluationBatch.scala`, `ReflectiveRecord.scala` | the runs of a candidate on examples, and the reflective records |
| `Candidate.scala`, `GepaState.scala` | the candidates, the pool, the Pareto fronts, `CandidateSelector`, and `ComponentSelector` |
| `InstructionProposer.scala`, `GepaProposals.scala`, `MergeProposer.scala` | the proposer, the options for more than one proposal, and the merge |
| `MinibatchSampler.scala`, `GepaRandom.scala` | the minibatches and the random sources |
| `GepaEvalCache.scala`, `GepaStatePersistence.scala` | the cache of scores, and the checkpoint in JSON |

## Relation to dspy

`gepa` is a port of the dspy `GEPA` optimizer and of the Python `gepa` engine. G-12 and G-45 in
[PORT_GAPS](../../docs/port/PORT_GAPS.md) record the port.

- `Gepa` uses the program capabilities `OptimizableStructure[P]` and `ProgramRunner[P]`, as `COPRO` and `MIPROv2` do.
  It is not a `Teleprompter`: `compile` returns a `GepaResult[P]` in place of an `OptimizationReport[P]`.
- The program has no state that changes. Each candidate gives a new copy of the program.
- The budget is a hard limit. Upstream can use more metric calls than its budget.
- A checkpoint keeps the merge schedule, and the random draws come from the seed. Upstream loses the merge schedule
  and the random state on a resume.
- Not ported: the `skills` and `truncate_history_outputs` options of the proposer, `track_best_outputs`, and the
  callbacks of the engine.
