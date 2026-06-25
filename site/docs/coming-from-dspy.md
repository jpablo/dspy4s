# Coming from DSPy

dspy4s is a Scala 3 port of [DSPy](https://dspy.ai/). If you already know the
Python library, this page maps its building blocks to their dspy4s equivalents
and explains where the two differ. The rest of the documentation is written as a
self-contained Scala guide and does not assume any DSPy background.

## Building blocks at a glance

| DSPy (Python) | dspy4s (Scala) |
|---|---|
| `dspy.Predict` | `Predict` |
| `dspy.ChainOfThought` | `ChainOfThought` |
| `dspy.ReAct` | `ReAct` |
| `dspy.Signature("a -> b")` (string) | `Signature.fromType[(a: A) => (b: B)]` |
| `class X(dspy.Signature)` (class) | `trait XSpec extends Spec` + `Signature.of[XSpec]` |
| `dspy.InputField()` / `dspy.OutputField()` | `InputField[T]` / `OutputField[T]` |
| `Literal["a", "b"]` | a Scala `enum ... derives Schema` |
| pydantic `BaseModel` | a `case class ... derives Schema` |
| `dspy.configure(lm=...)` | `RuntimeContext` installed via `RuntimeEnvironment.withSettings` |
| `BootstrapFewShot`, `COPRO`, `MIPROv2`, `GEPA` | same names |

## Signatures

DSPy has two ways to write a signature. Both map onto a typed `Signature` in
dspy4s.

A **string signature** becomes a function type whose parameters and result are
named tuples:

```python
# DSPy
classify = dspy.Predict("sentence -> sentiment: bool")
```

```scala
// dspy4s
val classify = Predict(Signature.fromType[(sentence: String) => (sentiment: Boolean)])
```

A **class signature** becomes a `Spec` trait with `InputField` / `OutputField`
members:

```python
# DSPy
class Emotion(dspy.Signature):
    """Classify emotion."""
    sentence: str = dspy.InputField()
    sentiment: Literal["sadness", "joy", "love", "anger", "fear", "surprise"] = dspy.OutputField()

classify = dspy.Predict(Emotion)
```

```scala
// dspy4s
enum Emotion derives Schema:
  case sadness, joy, love, anger, fear, surprise

trait EmotionSpec extends Spec:
  def sentence:  InputField[String]
  def sentiment: OutputField[Emotion]

val classify = Predict(Signature.of[EmotionSpec](instructions = "Classify emotion."))
```

A Python `Literal[...]` becomes a Scala `enum`, and a pydantic `BaseModel`
becomes a `case class`. Both use `derives Schema` to get their wire form.

## Calling a program and error handling

In DSPy a call returns the prediction directly and raises on failure. Field
access is dynamic:

```python
# DSPy
result = classify(sentence="...")
result.sentiment            # dynamic attribute, KeyError if misspelled
```

In dspy4s a call returns `Either[DspyError, Output]`, and the output is a named
tuple with typed dot-access. A misspelled field is a compile error:

```scala
// dspy4s
classify.apply((sentence = "...")).map(_.output.sentiment)   // Either[DspyError, Boolean]
```

## Configuration and runtime

DSPy configures a global language model:

```python
# DSPy
dspy.configure(lm=dspy.LM("openai/gpt-4o"))
```

dspy4s carries the language model and adapter in a `RuntimeContext`, installed
for a scope with `RuntimeEnvironment.withSettings` and passed implicitly as a
`given`:

```scala
// dspy4s
val settings = RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))
RuntimeEnvironment.withSettings(settings) {
  given RuntimeContext = RuntimeEnvironment.current
  // run programs here
}
```

## Immutability

DSPy's `Example` is mutable. dspy4s models it as an immutable `case class`, so
in-place updates become a copy:

```python
# DSPy
example.input = "..."
```

```scala
// dspy4s
val updated = example.withValue("input", "...")
```

## What is not ported yet

Some DSPy features depend on machinery that dspy4s does not have yet, including
dataset loaders (`dspy.datasets`), the MCP tool bridge, the `dspy.History` input
type, model serving / deployment, and a few optimizers (BootstrapFinetune,
Optuna, SIMBA). The
[examples module README](https://github.com/jpablo/dspy4s/blob/main/modules/examples/README.md)
tracks the current state, and each blocked example records exactly what it is
waiting on.
