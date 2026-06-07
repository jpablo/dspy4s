# Evaluation in dspy4s

> Adapted for **dspy4s** from the DSPy docs page
> [`learn/evaluation/overview.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/evaluation/overview.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

Once you have an initial system, evaluate it systematically instead of by eyeballing outputs.

1. **Collect a dev set.** Even ~20 examples help; ~200 goes a long way. Depending on your metric you may need
   only inputs, or inputs plus final outputs — rarely labels for intermediate steps. In dspy4s a data point is
   an immutable `Example` (`Example("question" := …, "answer" := …).withInputs(Set("question"))`).
2. **Define a metric.** A metric scores a prediction against an example. In dspy4s a metric implements the
   `Metric` trait — `score(example, prediction, trace): Either[DspyError, Double]` — and `FunctionMetric(name) { (ex, pred) => … }`
   wraps a plain function. For long-form outputs, a good metric is itself a small program checking several
   properties; start simple and iterate.
3. **Run development evaluations.** `Evaluate(EvaluateConfig(devset, metric, …))` runs your program over the
   dev set and reports scores, giving you a baseline and surfacing major issues.

### Runnable examples in this folder

| File | Topic |
|---|---|
| [`Data.scala`](./Data.scala) | Building `Example`s; inputs/labels partitioning. |
| [`Metrics.scala`](./Metrics.scala) | `FunctionMetric` + running `Evaluate`. |

Note on differences from DSPy: a dspy4s `Metric.score` does not receive a `RuntimeContext`, so a metric cannot
itself call an LM — LLM-as-judge metrics aren't expressible at the metric layer (use a judge *program* and
score its output). There is also no built-in retriever, so retrieval-trace metrics don't apply.
