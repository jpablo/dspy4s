# dspy4s examples

These examples show the functional API. A `Program` is immutable syntax. `ProgramRunner` interprets it with explicit
services. Optimizers return a new program value.

Run an offline example with:

```bash
sbt "examples/runMain dspy4s.examples.functionalQuickstart"
sbt "examples/runMain dspy4s.examples.functionalOptimization"
sbt "examples/runMain dspy4s.examples.functionalTools"
```

- `FunctionalQuickstart.scala` shows typed prediction and program composition.
- `FunctionalOptimization.scala` shows stable parameter IDs and immutable optimization.
- `FunctionalTools.scala` shows an explicit effectful tool backend.
