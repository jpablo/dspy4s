# dspy4s `streaming`

Token-level streaming on top of the blocking program surface. Wrap a program with `Streamify.streamify` and
calling it returns a `ClosableIterator[StreamEvent]` that yields field-routed tokens, status messages, and the
final prediction as they happen, instead of one blocking result. Depends on `core`, `lm`, `adapters`, and
[`programs`](../programs/README.md).

## The core idea

`streamify` runs the program on a daemon producer thread, injecting a wrapped `StreamingLanguageModel` into the
runtime. As the underlying LM generates, the wrapper siphons each chunk through the active adapter (parsing
tokens into output fields per signature) and pushes events onto a bounded queue; the caller consumes them off
the queue as a lazy iterator, with backpressure when the buffer fills. Four event types flow: token, status,
prediction, and error.

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

## Key types

| Type | Role |
|------|------|
| `Streamify.streamify[P](program, …)` | Entry point: wraps a program (via the `Streamable` type-class) into a function returning `ClosableIterator[StreamEvent]`. Optional status-message provider, stream listeners, final-prediction toggle, queue capacity. |
| `Streamable[P]` | Type-class that knows how to `run` a program from inputs and report its `knownSignatures` (for listener validation). Instances for `DynamicModule`, `ReAct`, `CodeAct`, `ProgramOfThought`. |
| `StreamEvent` | Sealed root: `TokenEvent` (field-routed text), `StatusEvent` (callback-derived progress), `PredictionEvent` (final `DynamicPrediction`), `ErrorEvent` (`DspyError`). |
| `StreamListener` | A subscription filter: a specific output field, optionally scoped to a named predictor, with `allowReuse` semantics (fire once per field cycle, or every time). |
| `StatusMessageProvider` | Hooks (`moduleStart`/`lmStart`/`toolStart`/…) to customize status text; a default is provided. |
| `StreamingLanguageModelWrapper` | Wraps a `StreamingLanguageModel`: feeds chunks into the queue while assembling a complete `LmResponse` for non-streaming callers, routing per-signature. |
| `StreamingQueue[A]` | The bounded producer-consumer queue: backpressure on full, consumer-abandon detection to unblock the producer, `asIterator` → `ClosableIterator[A]`. |

## Design notes

- **Per-signature routing.** The wrapper reads `ActivePredictContext.current` at the start of each LM call to
  bind the predict name and signature, so a multi-predictor program (e.g. `ReAct`'s per-step predictor plus its
  extractor) emits chunks under each predictor's correct field names.
- **Adapter-driven field parsing.** With an adapter configured, raw text is fed to `adapter.streamingState`,
  which emits `FieldChunk`s per field; without one, tokens emit with an empty `fieldName`.
- **Listener filtering with `allowReuse`.** When listeners are present, only matching chunks enqueue. A
  non-reusable listener fires for one field cycle (through `isLastChunk`) then mutes for the rest of the run;
  reusable listeners fire on every matching call. Matches Python dspy semantics.
- **Backpressure and clean teardown.** The producer is a daemon thread; if the consumer `close`s the iterator,
  a `consumerAbandoned` flag is set and the queue cleared, unblocking a producer parked in `put`.
- **Static listener validation.** On construction, `streamify` walks the program's `knownSignatures` and warns
  about listeners aimed at field/predictor names that don't exist; validation is skipped for opaque composites.
- **Dual-mode wrapper.** `call` consumes the whole stream and returns an assembled `LmResponse` to
  non-streaming callers; `stream` hands back the chunk iterator directly.

## Source layout

| File | Contents |
|------|----------|
| `Streamify.scala` | the `streamify` entry point, producer-thread coordination, listener validation |
| `Streamable.scala` | the `Streamable[P]` type-class and its instances |
| `StreamingLanguageModelWrapper.scala` | the per-signature chunk-siphoning LM wrapper |
| `StreamingQueue.scala` | the bounded backpressure queue + closable iterator |
| `StatusStreamingCallback.scala` | a `CallbackHandler` turning runtime events into `StatusEvent`s |
| `StatusMessageProvider.scala` | the status-text customization trait + default |
| `contracts/StreamingContracts.scala` | the `StreamEvent` hierarchy and `StreamListener` |

## Relation to dspy

This ports `dspy.streamify` and the streaming listener model. It builds on the `StreamingLanguageModel` from
[`lm`](../lm/README.md) and the per-field `AdapterStreamingState` from [`adapters`](../adapters/README.md), so
streaming is layered on the existing blocking surface rather than a separate execution path.
