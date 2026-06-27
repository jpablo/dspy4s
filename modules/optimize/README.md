# dspy4s `optimize`

The teleprompter (optimizer) family. Given a **student program**, a **trainset**, and a **metric**, an
optimizer searches for a better version of the program — better few-shot demos, better instructions, or both —
and returns the best one it found. This is the dspy4s port of `dspy.teleprompt`.

## The idea

dspy programs have two learnable knobs that an optimizer can turn without you editing any prompt by hand:

- **Demos** — few-shot examples attached to a predictor.
- **Instructions** — the natural-language instruction string on a predictor's signature.

An optimizer takes the program, proposes variations of those knobs (by bootstrapping demos from the trainset,
or asking an LM to rewrite instructions), scores each variation with your metric, and keeps the winner. You
get back the same program type you put in, with its predictors' demos and/or instructions improved.

```
 student ─┐
 trainset ─┼─► optimizer.compile ─► OptimizationReport(bestProgram, candidates…)
 metric  ─┘        (propose → score → select)
```

## The contract

Every demo/instruction optimizer implements `Teleprompter[P]`:

```scala
trait Teleprompter[P]:
  def name: String
  def compile(
      student: P,
      trainset: Vector[Example],
      teacher: Option[P] = None,
      valset: Option[Vector[Example]] = None
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]]
```

`compile` returns an `OptimizationReport[P]` carrying the `bestProgram` plus every scored `CandidateProgram[P]`
(program + score + optional `EvaluationResult` + metadata). When no `valset` is supplied, optimizers score on
the trainset.

### Two type-class spines

Optimizers are generic over the program type `P`, working through two given instances rather than a fixed
program class:

- **`Predictors[P]`** — introspection: read the program's learnable predictors (`read` / `readNamed`) and write
  edited ones back (`replace`). One `Predict` is a length-1 list; a composite exposes all its leaves. This is
  what lets a single code path optimize both a standalone predictor and an arbitrary composite.
- **`Runnable[P]`** — run `P` on a record of inputs, yielding the untyped `DynamicPrediction` that `Evaluate`
  consumes. This is the "spine unification": it lets the optimizers target **typed** programs (`Predict[I, O]`,
  `ChainOfThought[I, O]`, …) as well as the untyped `DynamicModule` spine, with no `asInstanceOf`.

Scoring across the family goes through `Evaluate` + the metric via shared helpers in `OptimizerSupport` (the
`seed → rolloutId` mapping and the eval wiring are kept in one place so every optimizer behaves identically).

## The optimizers

| Optimizer | What it tunes | How |
|-----------|---------------|-----|
| `LabeledFewShot` | demos | attach up to `k` labeled trainset examples directly as demos (sampled or first-k) |
| `BootstrapFewShot` | demos | run a teacher over the trainset, keep traces the metric accepts as demos |
| `BootstrapFewShotWithRandomSearch` | demos | bootstrap `numCandidates` demo sets with different seeds, score each, keep the best |
| `KNNFewShot` | demos (per query) | at each call, retrieve the query's nearest trainset neighbors and bootstrap *those* as demos |
| `COPRO` | instructions | coordinate-ascent: per predictor, an LM proposes `breadth` instructions over `depth` refinement rounds, greedily keeping the best |
| `MIPROv2` | demos + instructions | bootstrap demo-set candidates, propose instruction candidates (`GroundedProposer`), then random-search over the joint space |
| `InferRules` | instructions (+ demos) | bootstrap demos, then induce natural-language rules from labeled examples and append the best-scoring rule set |
| `Ensemble` | — | combine several already-compiled programs into one that runs all (or a sampled subset) and reduces by majority vote |

A few that warrant detail:

### `BootstrapFewShot`

The workhorse demo optimizer. A teacher program runs over the trainset; for each example where the metric
accepts the prediction (optionally above `metricThreshold`), the successful trace becomes a demo. Caps:
`maxBootstrappedDemos` (bootstrapped) and `maxLabeledDemos` (raw labeled). Many other optimizers compose it.

### `COPRO` — Coordinate-ascent Prompt Optimizer

Instruction-only. For each predictor: seed `breadth - 1` LM-proposed instructions (plus the current one),
score the whole program with each applied to that predictor, keep the best, and run `depth - 1` further rounds
that refine using the accumulated `(instruction, score)` attempts. Greedy/sequential across predictors. Deltas
vs Python (interleaved joint search, output-prefix mutation, `track_stats`) are documented in `COPRO.scala`.

### `MIPROv2` — Multiprompt Instruction PRoposal Optimizer (v2)

Three phases, each reusing an existing component: (1) bootstrap `numCandidates` demo-set candidates (plus a
zero-shot one); (2) propose instruction candidates per predictor via `GroundedProposer`; (3) random-search
`numTrials` over (demo-assignment, per-predictor instruction) choices, scoring each trial, returning the best.
The main delta from Python is **uniform random search instead of Optuna TPE** (no surrogate model, no
minibatch/full-eval split, no successive halving); see `MIPROv2.scala`.

### `GroundedProposer`

Not a teleprompter itself — a reusable component (`propose/`) that proposes candidate instructions per
predictor, grounded in a dataset summary and (optionally) bootstrapped demos, with rotating "tips". MIPROv2's
instruction-proposal phase.

### `Ensemble`

Not a `Teleprompter` — it `compile`s a `Vector[DynamicModule]` into a single `DynamicModule` that runs all
members (or a random `size`-sized sample) per call and folds their outputs through a `reduceFn` (majority vote
by default). Built on the untyped spine because an ensemble is inherently heterogeneous; it can nest anywhere a
program is expected.

## Persistence

`ProgramPersistence` saves/loads a program's learnable state as JSON — the analogue of Python's
`dump_state` / `load_state`. Built on `Predictors`, so one path covers a single `Predict` and an arbitrary
composite: `dumpState` serializes every predictor `read` exposes, `loadState` rebuilds each and writes it back
via `replace`. What round-trips depends on the leaf's `set`: a `DynamicPredict` leaf restores everything
(layout, demos, config); a typed `Predict`/`ChainOfThought` restores demos and instructions but keeps its own
field *structure* (writing it back would desync `outputShape` from `layout`). This covers the
"optimize once, deploy the artifact" workflow.

## Source layout

| File | Contents |
|------|----------|
| `contracts/OptimizeContracts.scala` | `Teleprompter`, `OptimizationReport`, `CandidateProgram` |
| `Runnable.scala` | the `Runnable[P]` spine (typed + untyped run) |
| `OptimizerSupport.scala` | shared instruction-edit, seed→rolloutId, and scoring helpers |
| `LabeledFewShot.scala`, `BootstrapFewShot*.scala`, `KNNFewShot.scala` | demo optimizers |
| `COPRO.scala`, `MIPROv2.scala`, `InferRules.scala` | instruction (and demo) optimizers |
| `propose/GroundedProposer.scala` | MIPROv2's instruction proposer |
| `Ensemble.scala` | program ensembling |
| `ProgramPersistence.scala` | program state save / load |

## Relation to dspy

This module ports `dspy/teleprompt/` and `dspy/propose/`. Following the [module-purity
principle](../../README.md), programs stay immutable pure functions; optimizers never mutate a student in
place (Python monkey-patches `forward`) — they read the predictor genome through `Predictors`, build edited
copies, and return a new program. `KNNFewShot` is the one case that can't be a `Teleprompter` (its behavior is
per-call), so it returns a wrapper `DynamicModule` instead. The reflective genetic optimizer **GEPA** lives in
its own [`gepa` module](../gepa/README.md) because it needs a richer feedback metric and a reflection LM.
