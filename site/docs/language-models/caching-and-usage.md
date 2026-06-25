# Caching & usage

Caching and usage tracking are per-model concerns. You wrap a language model in
a `ManagedLanguageModel` that adds a cache, a usage tracker, or both. There is no
global cache switch; you choose the behavior for each model you build.

## Caching

`ManagedLanguageModel` takes a cache implementation. Pick one to match how long
results should live:

```scala
--8<-- "tutorials/cache/Cache.scala:cache-variants"
```

- `NoopLmCache` disables caching.
- `InMemoryLmCache` caches for the life of the process.
- `DiskLmCache(dir)` persists results to a directory.

You can also implement `LmCache` yourself to control the cache key, for example
to key only on the messages and ignore the model name.

## Tracking usage

Wrap a block in `UsageTracking.withNewTracker` and set `trackUsage` on the
context. The tracker accumulates token usage across the calls inside the block. A
cache hit contributes no new usage:

```scala
--8<-- "tutorials/cache/Cache.scala:cache-usage"
```

## When to use it

| You want | Approach |
|---|---|
| Avoid repeat model calls in a process | `InMemoryLmCache` |
| Persist results across runs | `DiskLmCache(dir)` |
| Measure token cost of a workload | `UsageTracking.withNewTracker` + `trackUsage = Some(true)` |

Next: [Evaluation](../evaluation/examples-and-data.md).
