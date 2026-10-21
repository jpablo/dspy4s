# dspy4s `streaming`

`streaming` gives the output of a program as a stream of events while the program runs. Wrap a program with
`Streamify.streamify`. A call then returns a `ClosableIterator[StreamEvent]` in place of one result. It uses `core`,
`lm`, `adapters`, and [`programs`](../programs/README.md).

## Main concepts

- **A producer thread.** `streamify` runs the program on a daemon thread. The thread puts the events into a bounded
  queue, and the caller reads them from the iterator. When the queue is full, the thread waits.
- **A wrapped model.** For the call, a `StreamingLanguageModelWrapper` replaces the configured LM. The wrapper gives
  each text chunk to the adapter, and the adapter divides the text into the output fields. The configured LM must be
  a `StreamingLanguageModel`, or the stream has no `TokenEvent`s.

```scala
val stream = Streamify.streamify(program)
val events = stream(inputs)
events.foreach {
  case TokenEvent(predict, field, chunk, isLast, _) => print(chunk)
  case StatusEvent(msg, _)                          => log(msg)
  case PredictionEvent(pred, _)                     => done(pred)
  case ErrorEvent(err, _)                           => fail(err)
}
```

## Main types

| Type | Description |
|------|-------------|
| `Streamify.streamify[P]` | The entry point. It returns a function from an input record to a `ClosableIterator[StreamEvent]`. Other parameters set the status messages, the listeners, and the queue capacity. |
| `Streamable[P]` | The type class that runs a program and gives the signatures of its predictors. There are instances for each `DynamicModule`, and for `Predict`, `ChainOfThought`, and `ReAct`. |
| `StreamEvent` | A `TokenEvent` (a text chunk of one output field), a `StatusEvent` (a progress message), a `PredictionEvent` (the final `RawPrediction`), or an `ErrorEvent`. |
| `StreamListener` | Selects the tokens of one output field, and optionally of one predictor. |
| `StatusMessageProvider` | Writes the status text for module, LM, and tool events. The default writes a message only for tool calls. |

## Design notes

- **The tokens of each predictor.** At the start of each LM call, the wrapper reads `ActivePredictContext.current`
  to get the active predictor. Thus each token of `ReAct` has the correct predictor name and field name.
- **Listeners and `allowReuse`.** If there are listeners, only the tokens that match a listener go into the queue. A
  listener with `allowReuse = false` gets the tokens of one field only, up to the chunk with `isLastChunk = true`. A
  listener with `allowReuse = true` (the default) gets the tokens of each LM call.
- **Listener checks.** `streamify` compares each listener with the known signatures of the program. It sends a
  message to `warningSink` for a field name or a predictor name that does not exist.
- **Close.** When the consumer closes the iterator, the queue removes its events and refuses new events. Thus a
  producer thread that waits on a full queue can continue.

## Source layout

| Package | Contents |
|---------|----------|
| top level | `Streamify`, `Streamable`, `StreamingLanguageModelWrapper`, `StreamingQueue`, `StatusMessageProvider`, and `StatusStreamingCallback` (makes the `StatusEvent`s) |
| `contracts/` | `StreamEvent`, its four event types, and `StreamListener` |

## Relation to dspy

`streaming` is the port of `dspy.streamify` and of its stream listeners. It uses the `StreamingLanguageModel` of
[`lm`](../lm/README.md) and the `AdapterStreamingState` of [`adapters`](../adapters/README.md). One difference:
`allowReuse` is `true` by default, but `allow_reuse` in Python DSPy is `False` by default.
