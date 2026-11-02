# Streaming

Streaming lets you observe a program's output as it is produced, rather than
waiting for the final result. dspy4s streaming is synchronous: `streamify` turns
a program into a function that returns a `ClosableIterator[StreamEvent]`, which
you consume with an ordinary loop.

## Consuming a stream

Every stream is a sequence of `StreamEvent`s. The type is sealed, so a single
match handles every case: tokens as they arrive, status messages, the final
prediction, and errors:

```scala
--8<-- "tutorials/streaming/Streaming.scala:stream-consume"
```

## Streaming a program

Wrap a program with `Streamify.streamify` and declare which output fields to
stream with a `StreamListener`. Calling the result returns the event iterator:

```scala
--8<-- "tutorials/streaming/Streaming.scala:stream-basic"
```

The listener names the field (`answer`) you want streamed token by token. The
same approach works on a [composite program](../programs/composing.md): add a
listener per field, and disambiguate predictors that emit the same field name
with `predictName`.

## Notes

- The language model must support streaming. The bundled OpenAI provider does.
- Each model of the program streams, also the own model of a predictor.
- Streaming is synchronous, so there is no async iteration to manage; the
  consumer is a plain `while` loop over the iterator.
- A streamed call goes through the cache, the retry policy, and the history
  of `ManagedLanguageModel`, as a call does. Its token usage goes to the usage
  trackers and to the result, as the usage of a call does. When the cache has
  the answer, the stream gives no token: you get the status messages and the
  final prediction.
- A stream that stops before its end is an error (`LmStreamAssemblyError`).
  The retry policy can start a new attempt only before the first token.
- By default, a listener stops after one field. Pass `allowReuse = true` to get
  the field from each model call, for example `next_thought` from each step of
  `ReAct`.
- Close the iterator if you stop before the end of the stream. The close stops
  the program and the model stream. If you stop and do not close, the producer
  thread stays parked on the full queue.

Next: [Coming from DSPy](../coming-from-dspy.md).
