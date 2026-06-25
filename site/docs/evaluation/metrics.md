# Metrics

A **metric** scores a program's output against an [`Example`](examples-and-data.md).
It is a function `(example, prediction) => score` wrapped in the `Metric` type.
Metrics are used both to measure quality and to drive [optimization](../optimization/index.md).

## Function metrics

The simplest metric wraps a plain function. `FunctionMetric.bool` returns a
pass/fail comparison:

```scala
--8<-- "learn/evaluation/Metrics.scala:metric-function"
```

`FunctionMetric(name) { (example, pred) => ... }` is the general form for a
numeric score.

## Model-judged metrics

Some qualities are hard to check with string comparison. A metric can run a
judge program over a language model, because `Metric.score` carries a
`RuntimeContext`. dspy4s ships `SemanticF1`, which asks a model to judge the
recall and precision of a response against the ground truth:

```scala
--8<-- "learn/evaluation/Metrics.scala:metric-judge"
```

`CompleteAndGrounded` is another built-in judge metric, and you can write your
own by implementing `Metric`.

## When to use which

| Metric kind | Use when |
|---|---|
| `FunctionMetric` / `FunctionMetric.bool` | The check is exact or rule-based. |
| `SemanticF1`, `CompleteAndGrounded` | Quality is semantic and needs a model to judge. |

Next: [Running evaluations](running-evaluations.md).
