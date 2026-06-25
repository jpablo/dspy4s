# Few-shot demonstrations

Few-shot optimizers improve a program by choosing good in-context
demonstrations to put in front of the model. They differ in how they pick those
demonstrations. All share the [`compile` shape](index.md) and return an improved
program.

## LabeledFewShot

The simplest: select `k` demonstrations directly from a labeled training set. It
makes no model calls.

```scala
--8<-- "Cheatsheet.scala:opt-labeled"
```

## BootstrapFewShot

Runs the program over the training set, keeps the examples it answers well
(scored by the metric), and uses those as demonstrations:

```scala
--8<-- "Cheatsheet.scala:opt-bootstrap"
```

`BootstrapFewShotWithRandomSearch` (shown on the [overview](index.md)) goes
further, generating several candidate demonstration sets and keeping the best.

## KNNFewShot

Selects demonstrations nearest to each input, using embeddings. It needs an
`Embedder` over the training set:

```scala
--8<-- "Cheatsheet.scala:opt-knn"
```

## Choosing one

| Optimizer | How it picks demos | Cost |
|---|---|---|
| `LabeledFewShot` | Straight from the trainset | No model calls |
| `BootstrapFewShot` | Self-generated, metric-filtered | Some model calls |
| `BootstrapFewShotWithRandomSearch` | Searched candidate sets | More model calls |
| `KNNFewShot` | Nearest neighbors per input | Needs an embedder |

Next: [Instruction optimization](instructions.md).
