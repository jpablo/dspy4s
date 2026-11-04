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

A required field that the response does not have is a parse error. A reply with
no text is also a parse error, except a reply with tool calls for a signature
with a `tool_calls` output. With the default `ChatAdapter`, and with `XMLAdapter`,
a parse error gives one more call in the JSON format (`useJsonAdapterFallback`).

### Sealed traits, tuples, and time values

A prompt shows the JSON schema of a structured field, and each typed value has one form:

| Field type | The form of the value |
|---|---|
| A sealed trait or an `enum` with only cases with no fields | The case name: `beta`. |
| A sealed trait with fields | The case name as a key: `{"Circle": {"radius": 1.5}}`, and `{"Dot": {}}` for a case with no fields. |
| A tuple | A JSON array: `[1, "a"]`. |
| An `Option` | The value, or a null. |
| `Instant`, `LocalDate`, `UUID`, and the other time types | A text: `2024-01-02T03:04:05Z`. |
| `BigDecimal` | A number. Write it as a text to keep each digit of a fraction. |

Each form also works in a `List`, a `Map` value, a case, and a tuple. An input field, a demo, and a saved demo show these
forms, and the variables of `RLM` have these forms.

The output also decodes from a bare case name (`Dot`), and from a record for a tuple (`{"_1": 1, "_2": "a"}`). The schema
shows a case of a nested sealed trait with its own name, and a value in a prompt shows the path of the cases:
`{"Animal": {"Cat": {"name": "a"}}}`. The output decodes from each form.

`Signature.derived` makes the record of each class from its structure. To use a `Schema.transform`, give the signature a
shape with `Signature.fromShapes` and `Shape.derived`. The field then has the form of the value behind the transform.

### Annotations

The annotations of zio-blocks change the names and the forms of a typed value. Only the field of a Scala value keeps its name
in the code. The new name is the name in the prompt, the schema, a raw prediction, a labeled example, a demo, a history
message, and a metric, so use it in a data set:

```scala
final case class Person(
    @Modifier.rename("full_name") name: String,
    @Modifier.alias("years") age: Int,
    @Modifier.transient() cached: Int = 0
)

@Modifier.discriminator("kind")
sealed trait Shape
final case class Circle(radius: Double) extends Shape
case object Dot extends Shape
```

| Annotation | The effect |
|---|---|
| `@Modifier.rename("x")` on a field or a case | The name is `x`. |
| `@Modifier.fieldNaming("snake_case")` on a class | Each field has a name in that form, except a field with a `rename`. |
| `@Modifier.caseNaming("snake_case")` on a sealed trait or an `enum` | Each case has a name in that form, except a case with a `rename`. The cases of a nested sealed trait take the form of the outer one. |
| `@Modifier.alias("x")` on a field or a case | The decode also reads `x`. The prompt does not show it. |
| `@Modifier.discriminator("kind")` on a sealed trait with fields | The value is flat: `{"kind": "Circle", "radius": 1.5}`, and `{"kind": "Dot"}` for a case with no fields. |
| `@Modifier.transient()` on a field | The field is not in the prompt, and the output has its default. |
| `@Modifier.encodeTransient()` on a field | A value in your code does not reach the prompt. The model writes the field in an output, and an input shows its default. |
| `@Modifier.noExtraFields()` on a class | The schema closes the object. The decode ignores an extra field. |

An annotation on a simple case of a Scala `enum` has no effect. Use a sealed trait with case objects for such a case, or
`caseNaming` on the `enum`.

The strategies are `snake_case`, `camelCase`, `kebab-case`, `PascalCase`, and `identity`. A field of a signature needs a name
that is an identifier, so use `kebab-case` only on a nested class.

The name in the code of a nested field or case also decodes, and a `Maybe` field works as an `Option` field. A model must
write an output field and an `enum` value with the name that the prompt shows: another name is a parse error. Keep the
discriminator name different from each field name of the cases. If a case has a field with that name, the value keeps the
case name as a key.

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
