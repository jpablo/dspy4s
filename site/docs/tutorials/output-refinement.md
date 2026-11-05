# Output Refinement with BestOfN and Refine

This example wraps a `ChainOfThought` program in `BestOfN` and `Refine` to score its
outputs against a reward function and keep the best result. It covers reward
functions over predictions, the failure limit `failCount`, and a reward that
calls a language model to judge the output.

For a short introduction to these two modules, see [Modules](../programs/modules.md).

## Scoring with a reward function

```scala
--8<-- "tutorials/output_refinement/BestOfNAndRefine.scala:best-of-n"
```

`BestOfN` samples up to `n` completions from the inner program, one after the other, and
returns the one with the highest reward. The reward is `(input, prediction) => Double`. Because the
inner program is a `ChainOfThought("question -> answer")`, its prediction carries the
augmented output `(reasoning, answer)`, so the reward reads `pred.output.answer`. The
`threshold` lets sampling stop early: once a completion scores at or above it, that
completion is returned without drawing the rest. Here `oneWord` returns `1.0` only when
the answer is a single word.

## Stopping early on failures

```scala
--8<-- "tutorials/output_refinement/BestOfNAndRefine.scala:fail-count"
```

`failCount` sets the number of failures that the search can take. A failure is an error
of the inner program or an exception of the reward function. A low reward is not a failure:
the search goes on with the next attempt, up to `n` attempts. With
`failCount = Some(FailureCount(1))`, the search takes one failure, and the second failure
gives a `Left`, also when an earlier attempt has a reward. With no `failCount`, the limit is
`n`, so each attempt runs. When no attempt succeeds, the result is a `Left` with the last error.

`Refine` takes the same parameter. It also runs the attempts in sequence, and it gives
advice from each attempt to the next attempt.

## Judging with a language model

```scala
--8<-- "tutorials/output_refinement/BestOfNAndRefine.scala:llm-judge"
```

A reward function can call a language model. Here `judge` is a `ChainOfThought`
over a `FactualityJudge` signature that maps a statement to a boolean `is_factual`. The
`instructions` argument gives the task text that the model gets. The reward runs the judge
on the candidate answer and gives `1.0` when the judge says that the answer is factual. The
reward signature is `(input, prediction)` only, so the judge call uses the `RuntimeContext`
of the enclosing `call`. `Refine` runs the inner program until the reward reaches the threshold.

An error of the judge is not a reward of 0. The reward throws an exception, and `Refine`
counts it as a failure of the attempt. When the judge fails on each attempt, the result is
a `Left`.

## Running it

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.output_refinement.bestOfNAndRefineMain"
```

## Notes

Full source: [BestOfNAndRefine.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/output_refinement/BestOfNAndRefine.scala)
