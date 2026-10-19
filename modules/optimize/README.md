# dspy4s `optimize`

`optimize` contains the optimizers (teleprompters). An optimizer gets a student program, a trainset, and a metric. It
tries different demos, different instructions, or both, and returns the best program that it finds. It uses the
`core`, `programs`, and `evaluate` modules.

## Main concepts

- **Demos and instructions.** An optimizer changes two parameters of each predictor: the demos (the few-shot
  examples) and the instructions.
- **Propose, score, select.** An optimizer makes candidate programs, scores each one with `Evaluate` and the metric,
  and returns the best one. The student does not change.
- **Two program capabilities.** An optimizer works on each program type `P` that has two given instances from the
  `programs` module:
  - `OptimizableStructure[P]` reads the `OptimizableParameters` of each predictor (`read`), and puts new parameters
    into a copy of the program (`replace`). A composite program has all the predictors of its parts.
  - `ProgramRunner[P]` runs `P` on a record input and returns a `RawPrediction` for `Evaluate`.

  Thus the optimizers work on domain-valued programs (`Predict[I, O]`, `ReAct[I, O]`, …) and on record-valued
  programs (`DynamicModule`).
- **Events.** Each `compile` call sends a compile start event and an end event.

## The contract

Each demo optimizer and each instruction optimizer implements `Teleprompter[P]`:

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

`compile` returns an `OptimizationReport[P]` with the `bestProgram` and each scored `CandidateProgram[P]`. An
optimizer that scores candidates uses the `valset`, else the trainset. With no `valset`, `InferRules` uses the second
half of the trainset.

## The optimizers

| Optimizer | It changes | How |
|-----------|------------|-----|
| `LabeledFewShot` | demos | adds up to `k` trainset examples as demos |
| `BootstrapFewShot` | demos | runs a teacher on the trainset, and keeps the runs that the metric accepts as demos |
| `BootstrapFewShotWithRandomSearch` | demos | bootstraps `numCandidates` demo sets with different seeds, and keeps the best set |
| `KNNFewShot` | demos, for each call | bootstraps the nearest trainset examples to the input as demos |
| `COPRO` | instructions | for one predictor at a time, an LM proposes `breadth` instructions in `depth` rounds, and COPRO keeps the best |
| `MIPROv2` | demos and instructions | bootstraps demo sets, proposes instructions with `GroundedProposer`, and does a random search of the combinations |
| `InferRules` | instructions and demos | bootstraps demos, gets rules from an LM, and adds the best set of rules to the instructions |
| `Ensemble` | (none) | makes one program that runs the given programs (or a sample of `size`), and selects the majority answer |

`KNNFewShot` and `Ensemble` do not implement `Teleprompter`. Each one returns a `DynamicModule`.

### `BootstrapFewShot`

A teacher program runs on the trainset. With no teacher, the student is the teacher. An example passes when the
metric score is more than 0, or at least `metricThreshold` if you set it. Each predictor that ran gets a demo from its
trace of the passed example. `maxBootstrappedDemos` and `maxLabeledDemos` limit the demos. Many other optimizers use
`BootstrapFewShot`.

### `MIPROv2`

MIPROv2 first bootstraps `numCandidates` demo sets, and adds one set with no demos. Then `GroundedProposer` proposes
instructions for each predictor from a summary of the trainset, the demos, and a tip. Then MIPROv2 runs `numTrials`
trials. Each trial selects a demo set and one instruction for each predictor at random. MIPROv2 returns the candidate
with the best score.

## Save and load a program

`ProgramPersistence` saves the instructions, the demos, and the configuration of each predictor as JSON. To load a
state, give a new program with the same structure. The program keeps its signatures, LMs, and tools, and gets the
saved values. The [state contract](../../site/docs/runtime/saving-and-loading.md) gives the rules and the limits.

## Source layout

| Package | Contents |
|---------|----------|
| `optimize/` | the optimizers, `OptimizerSupport`, the count types such as `DemoCount`, and `ProgramPersistence` |
| `contracts/` | `Teleprompter`, `OptimizationReport`, and `CandidateProgram` |
| `propose/` | `GroundedProposer` |
| `para/` | `ParaCompile`, which runs `COPRO` on a `Program[I, O, N]` |

## Relation to dspy

`optimize` ports `dspy/teleprompt/` and `dspy/propose/`.

- An optimizer does not change the student. Thus `KNNFewShot` returns a new `DynamicModule`. Python DSPy changes
  the `forward` method of the student.
- `MIPROv2` uses a uniform random search in place of Optuna TPE.
- `COPRO` optimizes one predictor at a time. Python DSPy optimizes all predictors in each round.
- These optimizers are not ported yet: `SIMBA`, `BetterTogether`, `BootstrapFinetune`, `GRPO`,
  `BootstrapFewShotWithOptuna`, and `ReAnchor`. `AvatarOptimizer` is not ported, by design. See
  [PORT_GAPS](../../docs/port/PORT_GAPS.md).

GEPA is in the [`gepa` module](../gepa/README.md), because it needs a metric that gives feedback and a reflection LM.
