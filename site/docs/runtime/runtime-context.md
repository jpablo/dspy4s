# Runtime context

A `RuntimeContext` is the ambient configuration a program runs under. It carries
the active language model, the adapter, any callbacks, and execution settings
like thread count. Programs read it implicitly through a `given`, so you
configure once and every program in scope picks it up.

## Setting it

There are two ways to install a context:

- `RuntimeEnvironment.configure(ctx)` sets a global default for the current
  thread.
- `RuntimeEnvironment.withSettings(ctx) { ... }` installs a context just for the
  duration of a block.

The [Quickstart](../get-started/quickstart.md) shows the full wiring of a model
and adapter into a context.

## Scoped overrides

`withSettings` is the tool for changing one setting temporarily. Here a question
is answered under the default model, then again under a different model, scoped
to a block:

```scala
--8<-- "learn/programming/LanguageModels.scala:scoped-override"
```

The override applies only inside the block; outside it, the original context is
unchanged. The same pattern swaps the adapter, adds callbacks, or changes any
other context field. The trace and the history that the block adds stay after
the block, as with DSPy's `dspy.context`.

A field that a context does not set is `None`, and it comes from the installed
context. A set value wins, also an empty one: `callbacks = Some(Vector.empty)`
turns off the callbacks for the block, as `dspy.context(callbacks=[])` does.

## What the context carries

| Field | Purpose |
|---|---|
| `lm` | The active [language model](../language-models/configuring.md). |
| `adapter` | The active [adapter](../language-models/adapters.md). |
| `callbacks` | Handlers for the [observability](observability.md) event stream. |
| `trackUsage` | Whether the usage trackers get the [token usage](../language-models/caching-and-usage.md). `Some(false)` turns them off. The usage of a program result stays. |

Next: [Observability](observability.md).
