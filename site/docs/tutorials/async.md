# Async program execution

This example runs dspy4s programs off the calling thread and returns a `scala.concurrent.Future`. It shows how to await a single `Predict` and how to chain two predictors inside one asynchronous module.

## Running a single Predict asynchronously

```scala
--8<-- "tutorials/async/Async.scala:ask-async"
```

`ContextPropagation.future` runs the synchronous program on a worker thread and gives a `Future`. It captures the installed `RuntimeContext` and the carriers of the calling thread, for example the usage trackers, and it installs them on the worker thread. A plain `Future { ... }` loses the carriers and the scope: a `UsageTracking` tracker around the call gets no usage, and the call has no parent call id. It still finds the model, because `Module.apply` installs its `using RuntimeContext` on the worker thread. The result type stays `Either[DspyError, String]`, and the `Future` only moves the work to another thread.

## Chaining predictors in an async module

```scala
--8<-- "tutorials/async/Async.scala:simplifier-module"
```

`aforward` runs two steps in sequence. `>>>` makes one program from the two `ChainOfThought` predictors, and that program runs in one computation on a worker thread. A `Left` from the first step stops the program, so the second step does not run. The whole sequence gives one `Future`.

## Running it

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.async.asyncMain"
```

## Notes

Async tools are out of scope. `ToolFunction.invoke` is synchronous and there is
no async tool path, so tools run synchronously inside the program whether or not
the program itself is run asynchronously.

Full source: [Async.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/async/Async.scala)
