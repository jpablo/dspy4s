# dspy4s

dspy4s is a Scala 3 library for typed language-model programs. DSPy is an inspiration, but the main API uses a
functional Scala design. A `Program[I, O]` is immutable syntax. `ProgramRunner` interprets it with explicit ZIO
services. Programs do not contain a model, a runner, callbacks, or mutable parameters.

> **Status:** pre-release. The artifacts are not yet on Maven Central.

## Program model

```scala
import dspy4s.programs.{Program, ProgramRunner}
import dspy4s.signatures.Signature

final case class Question(question: String)
final case class Answer(answer: String)

val signature = Signature.derived[Question, Answer]("Answer", "Answer briefly.")
val answer     = Program.predict(signature)
val text       = answer >>> Program.lift[Answer, String](_.answer)

// The result type requires PredictionBackend. The program value stays pure.
val effect = ProgramRunner.run(text, Question("Why use typed programs?"))
```

The main properties are:

- Typed inputs, outputs, errors, and service requirements.
- Composition with `>>>`, `&&&`, `***`, `|||`, `map`, and `contramap`.
- Anonymous prediction slots by default, with optional stable named declarations.
- A separate immutable `ParameterStore` for optimizer-writable values.
- One interpreter for execution, events, streaming, and stack safety.
- Effectful evaluation and optimization over `RecordProgram` values.
- Explicit backends for prediction, code, tools, and persistent REPL sessions.

The current `LivePredictionBackend` adapts the older blocking LM and adapter contracts. This compatibility is below the
program boundary. Program construction and program execution do not use global runtime lookup.

## Modules

| Module | Purpose |
|---|---|
| `algebra` | Categories, functors, optics, and laws |
| `core` | Data and error contracts |
| `signatures` | Typed signatures and record shapes |
| `lm` | Low-level language-model providers |
| `adapters` | Low-level prompt and output adapters |
| `programs` | Functional syntax, interpreters, and strategies |
| `evaluate` | Effectful evaluation and metrics |
| `optimize` | Immutable few-shot and instruction optimizers |
| `gepa` | Reflective Genetic-Pareto optimization |
| `streaming` | ZStream view of program events and results |

## Build

Requirements: JDK 21, Scala 3.8.4, and sbt 1.x.

```bash
sbt test
sbt fmtCheck
sbt benchQuick
```

Run the offline examples:

```bash
sbt "examples/runMain dspy4s.examples.functionalQuickstart"
sbt "examples/runMain dspy4s.examples.functionalOptimization"
sbt "examples/runMain dspy4s.examples.functionalTools"
```

See [programs](modules/programs/README.md), [evaluation](modules/evaluate/README.md),
[optimization](modules/optimize/README.md), and the [architecture decision](docs/refactor/program-ast-interpreters.md).

## License

dspy4s uses the MIT License.
