# Output Refinement with BestOfN and Refine

This example wraps a `ChainOfThought` program in `BestOfN` and `Refine` to score its
outputs against a typed reward function and keep the best result. It covers reward
functions over typed predictions, early stopping with `failCount`, and a reward that
calls a language model to judge the output.

For a short introduction to these two modules, see [Modules](../programs/modules.md).

## Scoring with a typed reward

```scala
--8<-- "tutorials/output_refinement/BestOfNAndRefine.scala:best-of-n"
```

`BestOfN` samples `n` completions from the inner program in parallel and returns the
one with the highest reward. The reward is `(input, prediction) => Double`. Because the
inner program is a `ChainOfThought("question -> answer")`, its prediction carries the
augmented output `(reasoning, answer)`, so the reward reads `pred.output.answer`. The
`threshold` lets sampling stop early: once a completion scores at or above it, that
completion is returned without drawing the rest. Here `oneWord` returns `1.0` only when
the answer is a single word.

## Stopping early on failures

```scala
--8<-- "tutorials/output_refinement/BestOfNAndRefine.scala:fail-count"
```

`failCount` caps how many completions may score below the threshold before sampling
gives up and returns the best result seen so far. With `failCount = Some(1)`, a single
sub-threshold completion ends the search. This bounds the number of model calls when the
program is unlikely to reach the threshold. `Refine` accepts the same parameter and
refines sequentially, feeding each attempt's score into the next try.

## Judging with a language model

```scala
--8<-- "tutorials/output_refinement/BestOfNAndRefine.scala:llm-judge"
```

A reward function can itself call a language model. Here `judge` is a `ChainOfThought`
over a `FactualityJudge` signature that maps a statement to a boolean `is_factual`. The
reward runs the judge on the candidate answer and returns `1.0` when it is judged
factual. The reward signature is `(input, prediction)` only, with no implicit context
threaded in, so the judge call captures the ambient `RuntimeContext` from the enclosing
`call`. `Refine` then drives the inner program until the judged reward reaches the
threshold.

## Running it

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.output_refinement.bestOfNAndRefineMain"
```

## Notes

Full source: [BestOfNAndRefine.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/output_refinement/BestOfNAndRefine.scala)
