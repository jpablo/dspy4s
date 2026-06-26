# Async program execution

This example runs dspy4s programs off the calling thread and returns a `scala.concurrent.Future`. It shows how to await a single `Predict` and how to chain two predictors inside one asynchronous module.

## Running a single Predict asynchronously

```scala
--8<-- "tutorials/async/Async.scala:ask-async"
```

`ContextPropagation.future` captures the active `RuntimeContext` (LM, adapter, callbacks) and runs the synchronous program on a context-propagating `ExecutionContext`, returning a `Future`. A plain `Future { ... }` would run on a thread that has no `RuntimeContext`, so the program could not resolve its model. The result type stays `Either[DspyError, String]`, with the `Future` carrying only the off-thread scheduling.

## Chaining predictors in an async module

```scala
--8<-- "tutorials/async/Async.scala:simplifier-module"
```

`aforward` mirrors a module that runs two steps in sequence. Both `ChainOfThought` predicts execute in order inside a single off-thread computation. The for-comprehension threads the `Either`, so a failure in the first step short-circuits and the second step is skipped. The whole sequence is returned as one `Future`.

## Running it

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.async.asyncMain"
```

## Notes

Async tools are out of scope. `ToolFunction.invoke` is synchronous and there is
no async tool path, so tools run synchronously inside the program whether or not
the program itself is run asynchronously.

Full source: [Async.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/async/Async.scala)
