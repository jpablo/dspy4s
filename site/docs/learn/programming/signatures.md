# Signatures

A **signature** is a typed declaration of what a program takes in and what it
produces. Instead of hand-writing a prompt, you describe the *shape* of the task
and let dspy4s build the prompt for you.

In Python DSPy a signature is often a string (`"sentence -> sentiment"`). In
dspy4s the same idea is a real Scala type, so the compiler checks every field.

!!! tip "Every snippet here is verified"
    The Scala blocks on this page are extracted from
    [`Signatures.scala`](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/learn/programming/Signatures.scala),
    which builds under `-Werror -Wunused:all`. They can't drift out of sync with
    the library.

## Inline (string-style) signatures

The quickest form mirrors DSPy's `"in -> out"` string. In dspy4s it's
`Signature.fromType`, applied to a function type whose parameters and result are
**named tuples**:

=== "Scala"

    ```scala
    --8<-- "learn/programming/Signatures.scala:sentiment"
    ```

=== "Python (DSPy)"

    ```python
    classify = dspy.Predict('sentence -> sentiment: bool')
    classify(sentence=sentence).sentiment
    ```

The payoff: `classify.apply((sentence = ...))` is checked at compile time, and
`_.output.sentiment` is a typed `Boolean`.

### Adding instructions

`fromType` takes an optional `instructions` string — the equivalent of DSPy's
`dspy.Signature(..., instructions=...)`:

```scala
--8<-- "learn/programming/Signatures.scala:toxicity"
```

## ChainOfThought

Any signature can be run with `ChainOfThought` instead of `Predict`. It augments
the output named tuple with a `reasoning: String` field, so both
`_.output.reasoning` and `_.output.summary` are typed dot-accesses:

```scala
--8<-- "learn/programming/Signatures.scala:summarize"
```

## Class-based signatures

When you want named fields, descriptions, or a constrained output type, declare
a **`Spec` trait** with `InputField` / `OutputField` members. A Python
`Literal[...]` becomes a Scala `enum`:

=== "Scala"

    ```scala
    --8<-- "learn/programming/Signatures.scala:emotion"
    ```

=== "Python (DSPy)"

    ```python
    class Emotion(dspy.Signature):
        """Classify emotion."""
        sentence: str = dspy.InputField()
        sentiment: Literal['sadness', 'joy', 'love', 'anger', 'fear', 'surprise'] = dspy.OutputField()

    classify = dspy.Predict(Emotion)
    ```

`derives Schema` gives the enum a flat-string wire form (the case name) at the
output boundary, so the model's answer decodes straight into a typed `Emotion`.

## Custom types

Inputs and outputs aren't limited to primitives. Any `case class` (or nested
container) that `derives Schema` can be a field — the schema drives both the
wire shape and nested encode/decode:

```scala
--8<-- "learn/programming/Signatures.scala:custom-types"
```

## Summary

| Form | Declared with | Use when |
|---|---|---|
| Inline / string-style | `Signature.fromType[(in: I) => (out: O)]` | Quick, primitive in/out. |
| With instructions | `Signature.fromType[...](instructions = ...)` | You need to steer the model. |
| Class-based | `Signature.of[T <: Spec]` | Named fields, enums, constrained outputs. |
| Custom types | any `case class derives Schema` | Structured inputs/outputs. |

Next: [Quickstart](../../get-started/quickstart.md) ties these together into a
runnable program.
