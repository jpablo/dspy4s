# Signatures

A signature is a typed declaration of what a program takes in and what it
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
`_.output.sentiment` is a typed `Boolean`.

### Adding instructions

`fromType` takes an optional `instructions` string to steer the model:

```scala
--8<-- "learn/programming/Signatures.scala:toxicity"
```

## ChainOfThought

Any signature can run with `ChainOfThought` instead of `Predict`. It adds a
`reasoning: String` field to the output named tuple, so both
`_.output.reasoning` and `_.output.summary` are typed dot-accesses:

```scala
--8<-- "learn/programming/Signatures.scala:summarize"
```

## Class-based signatures

When you want named fields or a constrained output type, declare a **`Spec`
trait** with `InputField` / `OutputField` members. A fixed set of output values
is a Scala `enum`:

```scala
--8<-- "learn/programming/Signatures.scala:emotion"
```

`derives Schema` gives the enum a flat-string wire form (the case name) at the
output boundary, so the model's answer decodes straight into a typed `Emotion`.

## Custom types

Inputs and outputs are not limited to primitives. Any `case class` (or nested
container) that `derives Schema` can be a field. The schema drives both the wire
shape and nested encode/decode:

```scala
--8<-- "learn/programming/Signatures.scala:custom-types"
```

## Summary

| Form | Declared with | Use when |
|---|---|---|
| Inline | `Signature.fromType[(in: I) => (out: O)]` | Quick, primitive in/out. |
| With instructions | `Signature.fromType[...](instructions = ...)` | You need to steer the model. |
| Class-based | `Signature.of[T <: Spec]` | Named fields, enums, constrained outputs. |
| Custom types | any `case class derives Schema` | Structured inputs/outputs. |

Next: [Quickstart](../../get-started/quickstart.md) ties these together into a
runnable program.
