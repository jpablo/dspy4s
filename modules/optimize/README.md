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

## Functional migration

The replacement API uses `dspy4s.programs.RecordProgram[I, O]`. It keeps effects and parameter identity explicit:

- `LabeledFewShot` edits immutable `ProgramParameters` through stable `ParameterId` values.
- `ProgramBootstrapFewShot` runs the teacher through `ProgramRunner` in ZIO. It uses `ProgramMetric` for effectful
  scoring and returns an `OptimizationReport[RecordProgram[I, O]]`.
- `ProgramBootstrapRandomSearch` builds immutable bootstrap candidates and scores them with `ProgramEvaluate`. Candidate
  order and early stopping are deterministic.
- `ProgramCOPRO` accepts a typed instruction-proposal `Program`, updates the student through stable `ParameterId`
  values, and scores each candidate with `ProgramEvaluate`. It records and skips proposal failures.
- `ProgramMIPROv2` composes effectful demo bootstrapping with an injected typed instruction-proposal `Program`. A
  seeded planner builds joint demo and instruction trials, and all edits use stable `ParameterId` values. Bootstrap and
  proposal failures are report data, and score ties keep the current program.
- `ProgramKNNFewShot` accepts a typed nearest-neighbor selector and uses `localParametersWith` to attach its labeled
  examples for one run. Retrieval policy stays visible and injectable; the student and its static parameters do not
  change.
- `ProgramInferRules` composes effectful bootstrap, an injected typed rule-induction `Program`, stable-ID updates, and
  `ProgramEvaluate`. It narrows the example set only after a typed context-window failure.
- Bootstrap state is immutable and run-local. It does not use a thread-local runtime or mutable demo buffers.

The contracts below describe the legacy optimizer path that remains during migration.

## The legacy contract

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

- **`OptimizableStructure[P]`** — introspection: `inspect` exposes non-executable `OptimizableView`s (read-only signature/module
  metadata plus optimizable parameters), `read` projects the `OptimizableParameters` values, and `replace` writes an edited parameter vector
  back. One `Predict` is a length-1 list; a composite exposes all its leaves. This is what lets a single code path
  optimize both a standalone predictor and an arbitrary composite.
- **`LegacyProgramRunner[P]`** — run a legacy `P` on a record-valued `ProgramCall`, yielding the `RawPrediction` evidence that `Evaluate`
  consumes. This is the "spine unification": it lets the optimizers target domain-valued programs (`Predict[I, O]`,
  `ChainOfThought[I, O]`, `ProgramOfThought[I, O]`, …) as well as the record-valued `DynamicModule` spine, with no
  `asInstanceOf`.

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
by default). Built on the dynamic spine because an ensemble is inherently heterogeneous; it can nest anywhere a
program is expected.

## Persistence

`ProgramPersistence` saves/loads a program's `OptimizableParameters` as JSON — the analogue of Python's
`dump_state` / `load_state`. Every supported leaf exposes the same parameters: instructions, demos, and module-level config.
Loading applies those values to a freshly constructed program while preserving signature structure, module names,
runtimes, output schemas, bound LMs, and tools. See the authoritative
[state contract](../../site/docs/runtime/saving-and-loading.md) for the lens laws, ordinal-ID limitation, and format
compatibility policy.

## Source layout

| File | Contents |
|------|----------|
| `contracts/OptimizeContracts.scala` | `Teleprompter`, `OptimizationReport`, `CandidateProgram` |
| `programs/ProgramRunner.scala` | the old `LegacyProgramRunner[P]` spine for domain- and record-valued programs |
| `OptimizerSupport.scala` | shared instruction-edit, seed→rolloutId, and scoring helpers |
| `LabeledFewShot.scala`, `BootstrapFewShot*.scala`, `KNNFewShot.scala` | demo optimizers |
| `COPRO.scala`, `MIPROv2.scala`, `InferRules.scala` | legacy instruction (and demo) optimizers |
| `ProgramCOPRO.scala`, `ProgramMIPROv2.scala` | effectful instruction and joint-search optimizers with injected typed proposal programs |
| `ProgramKNNFewShot.scala` | run-local stable-ID demos from an injected typed neighbor selector |
| `ProgramInferRules.scala` | effectful rule induction with an injected typed induction program |
| `propose/GroundedProposer.scala` | MIPROv2's instruction proposer |
| `Ensemble.scala` | program ensembling |
| `ProgramPersistence.scala` | program state save / load |

## Relation to dspy

This module ports `dspy/teleprompt/` and `dspy/propose/`. Following the [module-purity
principle](../../README.md), programs stay immutable pure functions; optimizers never mutate a student in
place (Python monkey-patches `forward`) — they read the predictor genome through `OptimizableStructure`, build edited
copies, and return a new program. `KNNFewShot` is the one case that can't be a `Teleprompter` (its behavior is
per-call), so it returns a wrapper `DynamicModule` instead. The reflective genetic optimizer **GEPA** lives in
its own [`gepa` module](../gepa/README.md) because it needs a richer feedback metric and a reflection LM.
