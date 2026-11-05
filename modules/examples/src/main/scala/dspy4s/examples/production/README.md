# dspy4s in Production

> Adapted for **dspy4s** from the DSPy docs page
> [`production/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/production/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port — and honest about what the
> port does and doesn't yet provide.

The production concerns are the same as DSPy's; the dspy4s story for each:

- **Monitoring & observability.** dspy4s emits a structured `CallbackEvent` stream (module/LM/adapter/tool
  start+end). Implement a `CallbackHandler` and install it with `RuntimeEnvironment.withCallbacks` to log,
  trace, or measure. There is no MLflow/OpenTelemetry integration out of the box — you bridge the event stream
  to your sink. See [`tutorials/observability`](../tutorials/observability/Observability.scala).
- **Token & cost accounting.** Per-call usage is on `LmResponse.usage`, and the usage of a program call is on
  `raw.lmUsage`. `UsageTracking.withNewTracker` gives the usage of each model call of the programs in a block, for
  each model. A direct `lm.call` counts only through `LanguageModel.callAndRecordUsage`. See
  [`tutorials/cache`](../tutorials/cache/Cache.scala).
- **Caching.** Wrap a `LanguageModel` in `ManagedLanguageModel(delegate, cache = Some(…))` — `InMemoryLmCache`,
  `DiskLmCache(dir)`, `NoopLmCache`, or your own `LmCache`. See [`tutorials/cache`](../tutorials/cache/Cache.scala).
- **Scalability.** `RuntimeEnvironment` is thread-safe; programs run asynchronously via `Module.applyAsync`
  (`ContextPropagation.future`) and in parallel via `Parallel`. See [`tutorials/async`](../tutorials/async/Async.scala).
- **Reproducibility / deployment.** No MLflow integration or program `.save`/`.load` yet, and no built-in
  serving layer — programs are plain immutable values, so wrap one in your own HTTP layer to deploy.
