# Running evaluations

`Evaluate` runs a program over a dev set and scores each result with a
[metric](metrics.md). It is the batch counterpart to calling a program once: you
get an aggregate score across many examples.

## Building an evaluator

`Evaluate` is configured with the dev set, the metric, and a few display and
concurrency options:

```scala
--8<-- "learn/evaluation/Metrics.scala:metric-evaluate"
```

You launch it by applying it to a program inside a runtime context:

```scala
evaluator(devset, metric).apply()(program)
```

The `program` is a function `Example => Either[DspyError, DynamicPrediction]`,
and the result carries the aggregate score plus the per-example outcomes.

## The shape of evaluation

1. Build a dev set of [`Example`s](examples-and-data.md).
2. Choose a [metric](metrics.md).
3. Run `Evaluate` to get a score.
4. Feed the same dev set and metric into an
   [optimizer](../optimization/index.md) to improve the program, then evaluate
   again to confirm the gain.

Next: [Optimization](../optimization/index.md).
