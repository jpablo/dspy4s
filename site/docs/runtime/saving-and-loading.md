# Saving & loading

An [optimized program](../optimization/index.md) carries learned state:
demonstrations, instructions, and configuration. `ProgramPersistence` writes that
state to disk as JSON and reads it back, so you optimize once and load the result
at startup.

## Save and load

```scala
--8<-- "learn/optimization/Optimizers.scala:save-load"
```

`load` takes a freshly constructed program of the same shape and returns it with
the saved state written in. This mirrors how the program was built in code: you
recreate the structure, then load the learned state into it.

## What is and is not saved

- **Saved:** each predictor's signature, demonstrations, and configuration. This
  is the learned state, captured as plain JSON.
- **Not saved:** your program's code. dspy4s does not serialize program
  structure. You recreate the program in Scala, then `load` the state.

This split keeps the saved artifact small, readable, and decoupled from the
exact build of your application.

Next: [Streaming](streaming.md).
