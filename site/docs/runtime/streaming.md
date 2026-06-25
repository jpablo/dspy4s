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
- Streaming is synchronous, so there is no async iteration to manage; the
  consumer is a plain `while` loop over the iterator.

Next: [Coming from DSPy](../coming-from-dspy.md).
