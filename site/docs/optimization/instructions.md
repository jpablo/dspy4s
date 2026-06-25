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

## Choosing one

| Optimizer | Strategy | Use when |
|---|---|---|
| `COPRO` | Iterative instruction proposals | You want better wording, cheaply. |
| `MIPROv2` | Joint instructions + demos | You want the strongest single optimizer. |
| `GEPA` | Reflective genetic-Pareto search | You can describe failures and want it to learn from them. |

Next: [Runtime context](../runtime/runtime-context.md).
