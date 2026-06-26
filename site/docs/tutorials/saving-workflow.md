# Saving an optimized program

This example walks the full optimize-then-reuse cycle: build a `question -> answer` predictor, compile it into demonstrations with an optimizer, write that state to disk, then recreate the program and load the state back. It demonstrates that a program's learned state survives a round-trip through JSON.

## Build the program

```scala
--8<-- "tutorials/saving/Saving.scala:program"
```

The program is a single `question -> answer` predictor. `DynamicPredict` carries the `Predictors` instance that the optimizers and `ProgramPersistence` rely on. A typed `ChainOfThought` over the same signature round-trips the same way.

## Compile it

```scala
--8<-- "tutorials/saving/Saving.scala:compile"
```

`BootstrapFewShot` runs the program over an LM against a trainset to collect demonstration traces, and returns the best program it found. The result is the same predictor with demos attached. This step needs `OPENAI_API_KEY` because it calls the LM. Bring your own `Example`s for the trainset.

## Save the state

```scala
--8<-- "tutorials/saving/Saving.scala:save"
```

`ProgramPersistence.save` writes each predictor's signature, demos, and config as JSON. Only the learned state is written, not the program's code.

## Recreate and load

```scala
--8<-- "tutorials/saving/Saving.scala:load"
```

`load` takes a freshly built program of the same shape so it knows the predictor layout, then returns a new immutable program with the saved demos, config, and instructions written into it. The pattern is: construct the structure in Scala with `program()`, then load the state into it. For more on the save and load API, see [Saving and loading](../runtime/saving-and-loading.md).

## Running it

```bash
sbt "examples/runMain dspy4s.examples.tutorials.saving.savingMain"
```

The runnable `savingMain` performs the round-trip offline. It hand-attaches a couple of demos in place of `compile`, saves to a temp file, loads into a fresh program, and asserts the demo count is preserved, so no LM is required to run it.

## Notes

The whole-program save form (Python's `save_program=True`, which serializes the program's architecture into a directory) is out of scope. dspy4s programs are immutable Scala values, so you recreate the program in code and reload its state. There is no code or pickle serialization, hence no `.pkl` variant and no `modules_to_serialize` option; the library has one JSON state format.

Full source: [Saving.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/saving/Saving.scala)
