# Composing programs

Programs are ordinary Scala values, so you compose them the way you compose any
other code: call one program inside another. To package a multi-step pipeline as
a single reusable program, extend `DynamicModule` and chain predictors in its
`forward` method.

This module answers a question, then rewrites the answer in simpler terms, using
two predictors in sequence:

```scala
--8<-- "tutorials/streaming/Streaming.scala:compose-module"
```

The result is itself a program: you call it with inputs, it returns a
prediction, and it can be evaluated, optimized, saved, or nested inside a still
larger module, exactly like a single `Predict`.

## What to notice

- Each step is a named `DynamicPredict`. Naming matters when you later want to
  optimize or stream a specific step.
- `forward` returns `Either[DspyError, DynamicPrediction]`, so a failure in any
  step short-circuits the `for` comprehension.
- Nothing about composition is special-cased. A composite module is the same
  kind of value as the modules it contains.

## When to use it

| You want | Approach |
|---|---|
| A one-off pipeline | Call programs in sequence in plain code. |
| A reusable multi-step program | Extend `DynamicModule`, chain predictors in `forward`. |
| The model to choose the steps | Use [`ReAct`](tools-and-react.md) instead of fixing them. |

Next: [Language models](../language-models/configuring.md).
