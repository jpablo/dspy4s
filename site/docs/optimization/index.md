# Optimization

Optimization improves a program without changing its code. You give an optimizer
a program, a set of [`Example`s](../evaluation/examples-and-data.md), and a
[metric](../evaluation/metrics.md), and it searches for better few-shot
demonstrations and instructions, then hands back a new program with the same
type.

## The common shape

Every optimizer follows the same pattern: construct it with its configuration,
then call `compile`. The result is an `OptimizationReport` whose `bestProgram`
is the improved program:

```scala
--8<-- "learn/optimization/Optimizers.scala:optimize-bootstrap"
```

Optimizers operate on the untyped `DynamicPredict`, which carries the predictor
state they read and rewrite. The returned program can be
[saved and loaded](../runtime/saving-and-loading.md), so optimization runs once
and the result ships with your application.

## The optimizers

dspy4s groups them by what they tune:

| Optimizer | Tunes | Page |
|---|---|---|
| `LabeledFewShot` | Demonstrations (no model calls) | [Few-shot](few-shot.md) |
| `BootstrapFewShot` | Demonstrations (self-generated) | [Few-shot](few-shot.md) |
| `BootstrapFewShotWithRandomSearch` | Demonstrations (searched) | [Few-shot](few-shot.md) |
| `KNNFewShot` | Demonstrations (nearest-neighbor) | [Few-shot](few-shot.md) |
| `COPRO` | Instructions | [Instructions](instructions.md) |
| `MIPROv2` | Instructions and demonstrations | [Instructions](instructions.md) |
| `GEPA` | Instructions (reflective) | [Instructions](instructions.md) |
| `Ensemble` | Combines several programs | below |

`Ensemble` is the odd one out: instead of tuning one program, it combines
several into one by majority vote or a custom reduce function.

Next: [Few-shot demonstrations](few-shot.md).
