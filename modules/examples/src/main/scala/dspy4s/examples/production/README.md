# dspy4s in Production

> Adapted for **dspy4s** from the DSPy docs page
> [`production/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/production/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port — and honest about what the
> port does and doesn't yet provide.

The production concerns are the same as DSPy's; the dspy4s story for each:

- **Monitoring & observability.** `ProgramRunner` emits typed `ProgramEvent` values. Supply a `ProgramObserver` for
  live events or use `runJournaled` to retain the full execution journal. There is no MLflow/OpenTelemetry integration
  out of the box. Bridge the event stream
  to your sink. See [`tutorials/observability`](../tutorials/observability/Observability.scala).
- **Token & cost accounting.** Per-call usage is on `LmResponse.usage`; aggregate usage across a scope is via
  `ManagedLanguageModel` + `UsageTracking.withNewTracker`. See [`tutorials/cache`](../tutorials/cache/Cache.scala).
- **Caching.** Wrap a `LanguageModel` in `ManagedLanguageModel(delegate, cache = Some(…))` — `InMemoryLmCache`,
  `DiskLmCache(dir)`, `NoopLmCache`, or your own `LmCache`. See [`tutorials/cache`](../tutorials/cache/Cache.scala).
- **Scalability.** `ProgramRunner` returns ZIO effects. Run them as futures, and use `Program.collectAllPar` for bounded
  parallel program structure. See [`tutorials/async`](../tutorials/async/Async.scala).
- **Reproducibility / deployment.** `ProgramPersistence` saves immutable optimizer parameter state. There is no
  built-in serving layer. Wrap the route effect in your HTTP framework. See
  [`tutorials/deployment`](../tutorials/deployment/Deployment.scala).
