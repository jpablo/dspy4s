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

The same signature can also be written as a string with
`Signature.fromString("sentence -> sentiment: bool")`. Both forms are parsed at
compile time into the same typed signature, so pick whichever reads better.

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
output boundary, so the model's answer decodes straight into a typed `Emotion`.

## Case-class signatures

When you already model inputs and outputs as case classes, `Signature.derived`
builds a signature from an input type and an output type directly. The output is
a typed value, so `_.output.sentiment` has type `Emotion` with no cast:

```scala
--8<-- "typed/CaseClassExample.scala:derived-types"
```

```scala
--8<-- "typed/CaseClassExample.scala:derived-sig"
```

## Building a signature programmatically

When a case class per signature is overkill (exploration, shapes assembled from
config, tests), `Signature.builder` constructs one fluently. Each `input`/
`output` call summons the field's `Schema`:

```scala
--8<-- "typed/BuilderExample.scala:builder-sig"
```

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
| Case classes | `Signature.derived[In, Out]` | Inputs and outputs are already case classes. |
| Builder | `Signature.builder(...)` | Shapes built at runtime, or quick exploration. |
| Custom types | any `case class derives Schema` | Structured inputs/outputs. |

A signature only declares the task. To run it, you wrap it in a **module**.

Next: [Modules](modules.md).
