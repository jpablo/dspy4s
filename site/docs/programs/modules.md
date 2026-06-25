# Modules

A [signature](signatures.md) says *what* a task is. A **module** decides *how* a
language model answers it. You wrap a signature in a module to get a runnable
program.

dspy4s ships a few modules. They all share the same shape: construct one from a
signature, then call it with typed inputs.

## Predict

`Predict` answers the signature directly, in a single model call. You have
already used it in the [Quickstart](../get-started/quickstart.md) and on the
[Signatures](signatures.md) page:

```scala
val classify = Predict(Signature.fromType[(sentence: String) => (sentiment: Boolean)])
```

It is the module to reach for when the task is a direct mapping from inputs to
outputs.

## ChainOfThought

`ChainOfThought` asks the model to reason before it answers. It adds a
`reasoning: String` field to the front of the output, so you get both the
explanation and the answer with typed dot-access:

```scala
--8<-- "learn/programming/Modules.scala:chain-of-thought"
```

The signature did not change. Swapping `Predict` for `ChainOfThought` is the
only edit, and the extra `reasoning` field appears on the output.

!!! tip "Verified snippet"
    This example is extracted from
    [`Modules.scala`](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/learn/programming/Modules.scala).

## ReAct

`ReAct` answers by calling tools in a loop: the model thinks, picks a tool, sees
the result, and repeats until it has an answer. It has its own page,
[Tools & ReAct](tools-and-react.md).

## Refining outputs

Two modules wrap another module to improve its output against a reward function:

- `BestOfN` samples several completions in parallel and keeps the best one.
- `Refine` does the same sequentially, feeding each attempt's score back in.

Both take a typed reward function `(input, prediction) => Double`:

```scala
--8<-- "Cheatsheet.scala:best-of-n"
```

## Choosing a module

| Module | Strategy | Use when |
|---|---|---|
| `Predict` | One call, direct answer. | The task maps inputs to outputs. |
| `ChainOfThought` | Reason, then answer. | The task benefits from step-by-step thinking. |
| `ReAct` | Call tools in a loop. | The model needs external information or actions. |
| `BestOfN` / `Refine` | Sample and rank against a reward. | You can score outputs and want the best. |

Modules are ordinary Scala values, so you can also compose them into larger
programs. That is the next step.

Next: [Tools & ReAct](tools-and-react.md).
