# Observability

dspy4s emits a typed stream of events as a program runs: modules, language-model
calls, adapters, and tools each report when they start and end. You observe a
program by installing a callback handler, or by inspecting the call history.

## Callbacks

A `CallbackHandler` receives a sealed `CallbackEvent` stream. Match the case you
care about. This handler logs the end of every module:

```scala
--8<-- "tutorials/observability/Observability.scala:callback"
```

Install it for a scope with `RuntimeEnvironment.withCallbacks`:

```scala
--8<-- "tutorials/observability/Observability.scala:callback-run"
```

Because `CallbackEvent` is a sealed type, the compiler tells you every event
kind you could handle: `ModuleStartEvent` / `ModuleEndEvent`, the language-model
and adapter start/end events, and `ToolStartEvent` / `ToolEndEvent`.

## Inspecting history

For a quick look at what was sent to and returned from the model, wrap the model
in a `ManagedLanguageModel` (which records history) and read it with
`RuntimeEnvironment.inspectHistory`:

```scala
--8<-- "learn/programming/Adapters.scala:inspect-history"
```

## Which to use

| You want | Use |
|---|---|
| To react to events as they happen | A `CallbackHandler` |
| A quick after-the-fact look at calls | `RuntimeEnvironment.inspectHistory(n)` |

Next: [Saving & loading](saving-and-loading.md).
