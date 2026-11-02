# dspy4s `streaming`

`streaming` gives the output of a program as a stream of events while the program runs. Wrap a program with
`Streamify.streamify`. A call then returns a `ClosableIterator[StreamEvent]` in place of one result. It uses `core`,
`lm`, `adapters`, and [`programs`](../programs/README.md).

## Main concepts

- **A producer thread.** `streamify` runs the program on a daemon thread. The thread puts the events into a bounded
  queue, and the caller reads them from the iterator. When the queue is full, the thread waits.
- **A stream in the context.** For the call, `streamify` puts a `StreamSession` in the context
  (`RuntimeContext.streamSink`). The model of the context does not change. At each model call,
  `LanguageModel.callAndRecordUsage` wraps the model in a `StreamingLanguageModelWrapper`. The wrapper gives each text
  chunk to the adapter, and the adapter divides the text into the output fields.
- **Each model.** Each model of the program streams, also the own model of a predictor. A model that is not a
  `StreamingLanguageModel` gives no `TokenEvent`s. A direct `call` or `stream` of a model by your code gives no
  `TokenEvent`s.

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
| `StreamSession` | The stream of one `streamify` call, in the context. The wrappers of all the models of the call share its queue, its listeners, and its flags. |
| `StreamEvent` | A `TokenEvent` (a text chunk of one output field), a `StatusEvent` (a progress message), a `PredictionEvent` (the final `RawPrediction`), or an `ErrorEvent`. |
| `StreamListener` | Selects the tokens of one output field, and optionally of one predictor. |
| `StatusMessageProvider` | Writes the status text for module, LM, and tool events. The default writes a message only for tool calls. |

## Design notes

- **The tokens of each predictor.** At the start of each LM call, the wrapper reads `ActivePredictContext.current`
  to get the active predictor. Thus each token of `ReAct` has the correct predictor name and field name.
- **Listeners and `allowReuse`.** If there are listeners, only the tokens that match a listener go into the queue. A
  listener with `allowReuse = false` (the default) gets the tokens of one field only, up to the chunk with
  `isLastChunk = true`, also across two models. A listener with `allowReuse = true` gets the tokens of each LM call.
- **A nested `streamify`.** The inner call replaces the stream in the context of its program. Thus the tokens of that
  program go to the inner stream only.
- **The final prediction.** With `includeFinalPrediction = false`, the stream still has the prediction when there are
  no listeners, when a model call was a cache hit, or when no listener got a token.
- **Listener checks.** `streamify` compares each listener with the known signatures of the program. It sends a
  message to `warningSink` for a field name or a predictor name that does not exist, and for a listener with no
  predictor name on a field of more than one predictor.
- **Close.** A caller that stops before the end of the stream must close the iterator. The queue then removes its
  events and refuses new events, and the producer thread gets an interrupt. The program stops at its next wait or at
  its next token, and the wrapper closes the model stream. A consumer that stops and does not close leaves the
  producer thread parked on the full queue.

## Source layout

| Package | Contents |
|---------|----------|
| top level | `Streamify`, `Streamable`, `StreamSession`, `StreamingLanguageModelWrapper`, `StreamingQueue`, `StatusMessageProvider`, and `StatusStreamingCallback` (makes the `StatusEvent`s) |
| `contracts/` | `StreamEvent`, its four event types, and `StreamListener` |

## Relation to dspy

`streaming` is the port of `dspy.streamify` and of its stream listeners. It uses the `StreamingLanguageModel` of
[`lm`](../lm/README.md) and the `AdapterStreamingState` of [`adapters`](../adapters/README.md). The differences are
in [PORT_DIFFERENCES §23](../../docs/port/PORT_DIFFERENCES.md#23-streaming-rules-with-no-upstream-form).
