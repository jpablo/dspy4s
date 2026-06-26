# Optimizer tracking

This example runs the MIPROv2 optimizer over a small trainset and counts the LM calls it makes along the way. It demonstrates wiring a `CallbackHandler` into the `RuntimeContext` to observe an otherwise-silent compile, and reloading an optimized program from persisted state.

## A tracking callback

```scala
--8<-- "tutorials/optimizer_tracking/OptimizerTracking.scala:tracking-callback"
```

`CompileTrackingCallback` implements `CallbackHandler` and reacts to `CallbackEvent`s. It increments a counter on every `LmEndEvent`, giving a running total of LM calls. The same seam carries a logger, a tracer, or a metrics exporter when you need richer observation.

## Running the optimizer with tracking

```scala
--8<-- "tutorials/optimizer_tracking/OptimizerTracking.scala:optimize-with-tracking"
```

`RuntimeEnvironment.withCallbacks` installs the tracker on top of the existing callbacks for the duration of the block, so it observes every LM call the optimizer makes. `MIPROv2` is constructed with explicit `MIPROv2Config` knobs (candidate count, trials, and demo limits) and `compile` returns an `Either`; the success path pairs the best program with the tracked call count.

## Reloading an optimized program

```scala
--8<-- "tutorials/optimizer_tracking/OptimizerTracking.scala:reload"
```

`ProgramPersistence.load` reads saved state back into a freshly constructed program. Recreate the program, then load the optimized state into it.

## Running it

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.optimizer_tracking.optimizerTrackingMain"
```

## Notes

Integration with an external experiment-tracking server is out of scope; the
callback stream is where logging and metrics export are wired. The example uses a
small hand-built trainset, and reloading a persisted program is done with
`ProgramPersistence.load`.

Full source: [OptimizerTracking.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/optimizer_tracking/OptimizerTracking.scala)
