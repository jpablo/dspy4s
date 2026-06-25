# Adapters

An **adapter** sits between a module and the language model. It turns a
signature plus inputs into prompt messages, and parses the model's reply back
into the signature's typed output. You rarely call an adapter directly; you
choose which one is active.

dspy4s ships two:

- `ChatAdapter` formats the task as text and parses a text reply. It is the
  default and works with any model.
- `JSONAdapter` asks the model for structured JSON output, which is more
  reliable for rich output types when the model supports it.

## Choosing an adapter

The adapter is taken from the [runtime context](../runtime/runtime-context.md).
These two functions run the same structured-output program under each adapter:

```scala
--8<-- "learn/programming/Adapters.scala:adapter-select"
```

The program and signature are identical. Only the active adapter changes, which
changes how the prompt is built and how the reply is decoded.

## Which to use

| Adapter | Best for |
|---|---|
| `ChatAdapter` | The default. Any model, any signature. |
| `JSONAdapter` | Rich output types (case classes, lists) on models with good JSON support. |

For structured outputs, the output type is just a `case class` or `enum` that
`derives Schema`, exactly as on the [Signatures](../programs/signatures.md) page.
The adapter handles turning it into a prompt and back.

Next: [Caching & usage](caching-and-usage.md).
