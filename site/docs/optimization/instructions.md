# Instruction optimization

Instead of choosing demonstrations, these optimizers rewrite the instructions in
a program's signatures, searching for wording that scores better on your
[metric](../evaluation/metrics.md).

## COPRO

`COPRO` proposes candidate instructions and refines them over several rounds
(breadth and depth), keeping the best:

```scala
--8<-- "Cheatsheet.scala:opt-copro"
```

## MIPROv2

`MIPROv2` optimizes instructions and demonstrations together, guided by the
metric. It takes a validation set alongside the training set:

```scala
--8<-- "Cheatsheet.scala:opt-miprov2"
```

## GEPA

`GEPA` is a reflective, genetic-Pareto optimizer. Rather than sampling
instructions blindly, it reflects on where the program failed and evolves a
Pareto front of candidates, trading off competing objectives. It is a
self-contained implementation plus a dspy4s adapter, and it follows the same
`compile(student, trainset)` shape as the optimizers above.

A reflection model writes each new instruction. `Gepa` takes that model, and
an optional `instructionProposer`. The default is `DefaultInstructionProposer()`.
Its options add requirements for each new instruction
(`additionalInstructions`), replace the instructions of the proposal call
(`baseInstructions`), and set a length limit (`maxChars`). To control the
proposal fully, implement the `InstructionProposer` trait.

A metric can also give scores for named objectives
(`ScoreWithFeedback.objectiveScores`), for example accuracy and cost.
`GepaConfig.frontierType` then selects how GEPA keeps candidates that are good
on one objective: `Objective`, `Hybrid`, or `Cartesian`. The default is
`Instance`, which uses the score of each validation example only.

An iteration makes one proposal by default. `GepaConfig.proposalSampling`
gives it more: for example `SameParent(4)` proposes four children of one
parent, each from its own minibatch, and evaluates them in parallel.

## Choosing one

| Optimizer | Strategy | Use when |
|---|---|---|
| `COPRO` | Iterative instruction proposals | You want better wording, cheaply. |
| `MIPROv2` | Joint instructions + demos | You want the strongest single optimizer. |
| `GEPA` | Reflective genetic-Pareto search | You can describe failures and want it to learn from them. |

Next: [Runtime context](../runtime/runtime-context.md).
