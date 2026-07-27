# Saving an optimized program

This example walks the full optimize-then-reuse cycle: build a `question -> answer` predictor, compile it into demonstrations with an optimizer, write that state to disk, then recreate the program and load the state back. It demonstrates that a program's learned state survives a round-trip through JSON.

## Build the program

```scala
--8<-- "tutorials/saving/Saving.scala:program"
```

The example uses a single dynamic `question -> answer` predictor for brevity. Optimizers and `ProgramPersistence`
are generic over any program with `PredictorTraversal` evidence, including typed `Predict` and `ChainOfThought` programs.

## Compile it

```scala
--8<-- "tutorials/saving/Saving.scala:compile"
```

`BootstrapFewShot` runs the program over an LM against a trainset to collect demonstration traces, and returns the best program it found. The result is the same predictor with demos attached. This step needs `OPENAI_API_KEY` because it calls the LM. Bring your own `Example`s for the trainset.

## Save the state

```scala
--8<-- "tutorials/saving/Saving.scala:save"
```

`ProgramPersistence.save` writes each leaf's `OptimizableParameters`: instructions, demos, and module-level config. It
does not write signature field structure, module names, runtime bindings, tools, or program code.

## Recreate and load

```scala
--8<-- "tutorials/saving/Saving.scala:load"
```

`load` takes a freshly built program with the same predictor traversal/order, then returns a new immutable program
with the saved instructions, demos, and config written into it. The fresh value keeps its signature structure,
name, runtime, output schema, bound LM, and tools. For the complete contract and ordinal-ID caveat, see
[Saving and loading](../runtime/saving-and-loading.md).

## Running it

```bash
sbt "examples/runMain dspy4s.examples.tutorials.saving.savingMain"
```

The runnable `savingMain` performs the round-trip offline. It hand-attaches a couple of demos in place of `compile`, saves to a temp file, loads into a fresh program, and asserts the demo count is preserved, so no LM is required to run it.

## Notes

The whole-program save form (Python's `save_program=True`, which serializes the program's architecture into a
directory) is out of scope. There is no code or pickle serialization, hence no `.pkl` variant and no
`modules_to_serialize` option. The former layout-bearing predictor-state format is unsupported; regenerate saved
artifacts with the current version.

Full source: [Saving.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/saving/Saving.scala)
