# Optimization in dspy4s

> Adapted for **dspy4s** from the DSPy docs page
> [`learn/optimization/overview.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/optimization/overview.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

Once you have a program and a metric, an **optimizer** tunes the program (the few-shot demos attached to a
predictor, the predictors' instructions, or both) to improve the metric. Expand your data into a training set and a held-out test set
alongside the dev set you used for exploration. For prompt/demo optimizers, a few dozen training examples can
already help; aim higher when you can. DSPy suggests an unusual split for prompt optimizers — roughly 20%
train / 80% validation — because they overfit small training sets.

In dspy4s, optimizers operate on a typed `RecordProgram`. Ordinary parameter slots receive deterministic ordinal IDs.
A named prediction declaration supplies a stable semantic ID when it is necessary. An optimizer effect returns an
`OptimizationReport` whose `bestProgram` is a new immutable value.

### Runnable example in this folder

| File | Topic |
|---|---|
| [`Optimizers.scala`](./Optimizers.scala) | `BootstrapRandomSearch(student, trainset, config = ...)`. |

What's ported: `LabeledFewShot` / `BootstrapFewShot` / `BootstrapRandomSearch` (few-shot demos),
`COPRO` / `MIPROv2` (instructions, plus demos for MIPROv2), `Ensemble`, `KNNFewShot`, `InferRules`, and `GEPA`
(reflective prompt evolution, in the [`dspy4s-gepa`](../../tutorials/gepa_ai_program/README.md) module). Not yet
ported: `SIMBA`, `BetterTogether`, and finetuning-based optimizers (`BootstrapFinetune` / `GRPO`). Iterative
development is the point — when an
optimization run leaves you unhappy, go back to **Programming** (better task/program) or **Evaluation**
(better data/metric) before reaching for a fancier optimizer.
