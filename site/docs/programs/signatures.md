# Signatures

A signature declares what a program takes in and what it
produces. Instead of hand-writing a prompt, you describe the shape of the task
and dspy4s builds the prompt from it.

A signature is an ordinary Scala type, so the compiler checks every field.

!!! tip "Every snippet here is verified"
    The Scala blocks on this page are extracted from
    [`Signatures.scala`](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/learn/programming/Signatures.scala),
    which builds under `-Werror -Wunused:all`. They stay in sync with the library.

## Inline signatures

The shortest form is `Signature.fromType`, applied to a function type whose
parameters and result are **named tuples**:

```scala
--8<-- "learn/programming/Signatures.scala:sentiment"
```

`classify.apply((sentence = ...))` is checked at compile time, and
`_.output.sentiment` is a `Boolean`.

The same signature can also be written as a string with
`Signature.fromString("sentence -> sentiment: bool")`. Both forms are parsed at
compile time into the same `Signature`, so pick whichever reads better.

### Adding instructions

`fromType` takes an optional `instructions` string to steer the model:

```scala
--8<-- "learn/programming/Signatures.scala:toxicity"
```

## Class-based signatures

When you want named fields or a constrained output type, declare a **`Spec`
trait** with `InputField` / `OutputField` members. A fixed set of output values
is a Scala `enum`:

```scala
--8<-- "learn/programming/Signatures.scala:emotion"
```

`derives Schema` gives the enum a flat-string wire form (the case name) at the
output boundary, so the model's answer decodes straight into an `Emotion`.

## Case-class signatures

When you already model inputs and outputs as case classes, `Signature.derived`
builds a signature from an input type and an output type directly. The output is
a Scala value, so `_.output.sentiment` has type `Emotion` with no cast:

```scala
--8<-- "signatures/CaseClassExample.scala:derived-types"
```

```scala
--8<-- "signatures/CaseClassExample.scala:derived-sig"
```

### Optional outputs

An output field of type `Option[T]` is optional. When the response of the model
does not have the field, the value is `None`, and the call does not fail. In an
inline signature, write the type as `Optional[T]` or `T | None`, for example
`"question -> answer, note: Optional[str]"`.

A required field that the response does not have is a parse error. With the
default `ChatAdapter`, a parse error gives one more call in the JSON format
(`useJsonAdapterFallback`).

## Building a signature programmatically

When a case class per signature is overkill (exploration, shapes assembled from
config, tests), `Signature.builder` constructs one fluently. Each `input`/
`output` call summons the field's `Schema`:

```scala
--8<-- "signatures/BuilderExample.scala:builder-sig"
```

## Custom types

Inputs and outputs are not limited to primitives. Any `case class` (or nested
container) that `derives Schema` can be a field. The schema drives both the wire
shape and nested encode/decode:

```scala
--8<-- "learn/programming/Signatures.scala:custom-types"
```

## Conversation history

A program does not keep the earlier turns of a conversation. To give them to
the model, add an input field of type `History` to the signature:

```scala
--8<-- "tutorials/conversation_history/ConversationHistory.scala:signature"
```

A `History` holds messages. Each message is a record whose keys are field names
of the same signature. The adapter does not show the field as a value. It
replays each message before the current request: the input fields make an
earlier user turn, and the output fields make the assistant turn that answered
it.

The caller adds each turn to the history and gives the history to the next
call:

```scala
--8<-- "tutorials/conversation_history/ConversationHistory.scala:conversation"
```

A message can also hold the tool calls of a turn and the results of those calls
(`History.withToolCallResults`). With
[native function calling](tools-and-react.md#native-function-calling), the
adapter sends them to the model as tool messages.

## Summary

| Form | Declared with | Use when |
|---|---|---|
| Inline | `Signature.fromType[(in: I) => (out: O)]` | Quick, primitive in/out. |
| With instructions | `Signature.fromType[...](instructions = ...)` | You need to steer the model. |
| Class-based | `Signature.of[T <: Spec]` | Named fields, enums, constrained outputs. |
| Case classes | `Signature.derived[In, Out]` | Inputs and outputs are already case classes. |
| Builder | `Signature.builder(...)` | Shapes built at runtime, or quick exploration. |
| Custom types | any `case class derives Schema` | Structured inputs/outputs. |

A signature only declares the task. To run it, you wrap it in a **module**.

Next: [Modules](modules.md).
