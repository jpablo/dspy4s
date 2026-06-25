# Quickstart

This walks through declaring a signature, building a program, and running it
end to end. Every Scala block below is pulled directly from
[`Signatures.scala`](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/learn/programming/Signatures.scala)
in the examples module, so it compiles under the project's strict flags.

## 1. Declare a signature and a program

A **signature** declares typed inputs and outputs. A **program** (here
`Predict`) runs it against a language model. This one classifies sentiment:

```scala
--8<-- "learn/programming/Signatures.scala:sentiment"
```

Because the input and output are named tuples, `sentence` and `sentiment` are
real fields. A typo is a compile error, and `_.output.sentiment` is a typed
`Boolean`, not a string lookup.

## 2. Wire up a runtime and run it

A program needs a `RuntimeContext` carrying a live LM and an adapter. This is a
complete, runnable program (it reads `OPENAI_API_KEY` from the environment):

```scala
--8<-- "learn/programming/Signatures.scala:run"
```

Run it with:

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.main"
```

## 3. Add reasoning with ChainOfThought

Swapping `Predict` for `ChainOfThought` prepends a typed `reasoning: String` to
the output, with no signature changes required:

```scala
--8<-- "learn/programming/Signatures.scala:summarize"
```

## Where to next

- [Signatures](../learn/programming/signatures.md): the full set of ways to
  declare inputs and outputs (inline, traits, enums, custom types).
