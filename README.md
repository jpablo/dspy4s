# dspy4s

A Scala 3 library for building language model programs with typed signatures.
Inputs and outputs are ordinary Scala types, so the compiler checks them for you.

dspy4s is a port of [DSPy](https://github.com/stanfordnlp/dspy) to Scala 3. It
keeps DSPy's structure (signatures, modules, adapters, optimizers) and replaces
the dynamic Python surface with a statically typed one.

> **Status:** pre-release. The API is still moving and the artifacts are not yet
> published to Maven Central. For now, build from source.

## What it looks like

A *signature* declares typed inputs and outputs. A *program* (here `Predict`)
runs it against a language model.

```scala
import dspy4s.programs.Predict
import dspy4s.typed.Signature

// Inputs and outputs are named tuples, so `sentence` and `sentiment` are real
// fields. A typo is a compile error, and `_.output.sentiment` is a typed Boolean.
val classify = Predict(Signature.fromType[(sentence: String) => (sentiment: Boolean)])

classify.apply((sentence = "it's a charming and often affecting journey."))
  .map(_.output.sentiment)
// Either[DspyError, Boolean]
```

Swapping `Predict` for `ChainOfThought` prepends a typed `reasoning: String` to
the output, with no signature changes.

## Why typed signatures

- **Compile-time field checks.** Signatures are ordinary Scala types. A wrong
  field name is a compile error, not a runtime lookup failure, and output access
  (`_.output.sentiment`) is typed.
- **One codec spine.** Field values flow through a single `DynamicValue.Record`
  intermediate (from `zio-blocks-schema`) shared by adapters, programs,
  evaluation, and the typed surface. Decode failures surface at the `run`
  boundary as `Either[DspyError, _]`, not via lazy field access.
- **Composable programs.** `Predict`, `ChainOfThought`, `ReAct`, `CodeAct`,
  `ProgramOfThought`, `BestOfN`, and `Refine` are plain values you compose like
  any other Scala code. `RLM` (Recursive Language Model) is an experimental
  program for reasoning over long contexts without placing them in the prompt:
  inputs become variables in a sandboxed REPL that the model explores with
  generated code.
- **Compiler-verified docs.** Every code sample on the docs site is extracted
  from the `examples` module, which builds under strict flags (`-Werror`,
  `-Wunused:all`). A snippet that would not compile fails the build.

## Modules

dspy4s is split into focused modules so you can depend only on what you need.

| Module      | Artifact           | What it gives you                                            |
|-------------|--------------------|-------------------------------------------------------------|
| `core`      | `dspy4s-core`      | Contract layer: `Example`, `DynamicValue` spine, runtime.   |
| `typed`     | `dspy4s-typed`     | Typed `Signature` / `Spec` surface and `Shape` codecs.      |
| `lm`        | `dspy4s-lm`        | Provider-agnostic LM API and the OpenAI client.             |
| `adapters`  | `dspy4s-adapters`  | Chat / JSON / XML adapters and native function-calling.     |
| `programs`  | `dspy4s-modules`   | `Predict`, `ChainOfThought`, `ReAct`, refinement programs.  |
| `evaluate`  | `dspy4s-evaluate`  | `Evaluate` runner, metrics, LLM-as-judge.                   |
| `optimize`  | `dspy4s-optimize`  | Bootstrap few-shot, COPRO, MIPROv2, KNN, ensembles.         |
| `gepa`      | `dspy4s-gepa`      | The reflective Genetic-Pareto prompt optimizer.             |
| `streaming` | `dspy4s-streaming` | Synchronous streaming of predictions.                       |

## Requirements

- Scala 3 (the build pins `3.8.4`)
- JDK 21 (the repo pins `openjdk-21.0.1` in `.tool-versions`)
- sbt 1.x
- An `OPENAI_API_KEY` for examples that make live LM calls

## Build from source

```bash
git clone https://github.com/jpablo/dspy4s.git
cd dspy4s
sbt compile
```

Run the test suite:

```bash
sbt test
```

Run one of the bundled examples (reads `OPENAI_API_KEY` from the environment):

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.main"
```

## Documentation

The docs site is built with MkDocs Material and lives under [`site/`](site/).
It is published at <https://jpablo.github.io/dspy4s/>.

- [Quickstart](site/docs/get-started/quickstart.md): write your first signature
  and run it end to end.
- [Signatures](site/docs/learn/programming/signatures.md): the full set of ways
  to declare inputs and outputs (inline, traits, enums, custom types).
- [Architecture](docs/ARCHITECTURE.md): the design choices, module graph, and
  the typed and dynamic stacks.

To preview the docs locally:

```bash
cd site
uv run mkdocs serve
```

## Relationship to DSPy

dspy4s follows the upstream DSPy decomposition (the data spine, composite
programs, adapter contracts, and optimizer pattern), so concepts carry over.
The main differences are in the surface: signatures are produced by macros and
typeclasses rather than Python metaclasses, inputs and outputs are typed, and
errors are a structured `DspyError` ADT. The
[`docs/port/`](docs/port/) directory tracks the per-symbol mapping and the
behavioral deltas.

## License

dspy4s is released under the [MIT License](LICENSE), the same license used by
DSPy. The upstream Stanford Future Data Systems copyright notice is retained in
the license file.
