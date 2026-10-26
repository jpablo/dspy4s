# Caching & usage

Caching is a per-model concern. You wrap a language model in a
`ManagedLanguageModel` that adds a cache. There is no global cache switch: you
choose the cache for each model that you build. Usage tracking needs no
wrapper: a usage tracker gets each model call of a program.

## Caching

`ManagedLanguageModel` takes a cache implementation. Pick one to match how long
results should live:

```scala
--8<-- "tutorials/cache/Cache.scala:cache-variants"
```

- `NoopLmCache` disables caching.
- `InMemoryLmCache` caches for the life of the process.
- `DiskLmCache(dir)` persists results to a directory.

You can also give `ManagedLanguageModel` a `cacheKey` function to control the
cache key, for example to key only on the messages and ignore the model name.
To keep the results in another store, implement `LmCache` yourself: it gets and
puts a response for a `CacheKey`.

## Tracking usage

Wrap a block in `UsageTracking.withNewTracker`. The tracker gets the token
usage of each model call that a program makes in the block, for each model
name. It also gets the calls on the worker threads that the block starts. A
cache hit adds no usage:

```scala
--8<-- "tutorials/cache/Cache.scala:cache-usage"
```

The framework records the usage when a program calls a model
(`LanguageModel.callAndRecordUsage`). Thus a model with no
`ManagedLanguageModel` wrapper counts too. A direct `lm.call(...)` or
`lm.stream(...)` in your code counts in no tracker. To count such a call, use
`LanguageModel.callAndRecordUsage(lm, request)`.

The LM usage of a program result (`raw.lmUsage`) comes from the same records.
`trackUsage` is on by default. With `trackUsage = Some(false)` in the context,
the trackers get nothing, but each result still has its usage.

## When to use it

| You want | Approach |
|---|---|
| Avoid repeat model calls in a process | `InMemoryLmCache` |
| Persist results across runs | `DiskLmCache(dir)` |
| Measure token cost of a workload | `UsageTracking.withNewTracker` |

Next: [Evaluation](../evaluation/examples-and-data.md).
