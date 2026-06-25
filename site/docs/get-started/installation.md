# Installation

dspy4s targets **Scala 3** and runs on **JDK 21**.

!!! warning "Not yet published"
    dspy4s is pre-release and not on Maven Central yet. For now, build it from
    source (below). The coordinates on this page are the intended published
    artifacts and will work once the first release is cut.

## Requirements

- JDK 21 (the repo pins `openjdk-21.0.1` in `.tool-versions`)
- sbt 1.x
- An `OPENAI_API_KEY` for examples that make live LM calls

## Build from source

```bash
git clone https://github.com/jpablo/dspy4s.git
cd dspy4s
sbt compile
```

To run one of the bundled examples:

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.main"
```

## Once published (intended coordinates)

dspy4s is split into focused modules; depend only on what you need.

```scala title="build.sbt"
libraryDependencies ++= Seq(
  "io.github.jpablo" %% "dspy4s-core"      % "0.1.0",
  "io.github.jpablo" %% "dspy4s-typed"     % "0.1.0", // typed signatures
  "io.github.jpablo" %% "dspy4s-lm"        % "0.1.0", // language-model providers
  "io.github.jpablo" %% "dspy4s-adapters"  % "0.1.0", // chat / JSON adapters
  "io.github.jpablo" %% "dspy4s-modules"   % "0.1.0", // Predict, ChainOfThought, ReAct
  "io.github.jpablo" %% "dspy4s-evaluate"  % "0.1.0", // metrics + Evaluate
  "io.github.jpablo" %% "dspy4s-optimize"  % "0.1.0", // COPRO, MIPROv2, bootstrap
  "io.github.jpablo" %% "dspy4s-gepa"      % "0.1.0"  // GEPA optimizer
)
```

| Module | Artifact | What it gives you |
|---|---|---|
| core | `dspy4s-core` | The contract layer (`Example`, `DynamicValue`, runtime context). |
| typed | `dspy4s-typed` | Typed `Signature` / `Spec` surface. |
| lm | `dspy4s-lm` | Language-model providers (OpenAI). |
| adapters | `dspy4s-adapters` | Chat / JSON adapters and native function-calling. |
| programs | `dspy4s-modules` | `Predict`, `ChainOfThought`, `ReAct`, refinement. |
| evaluate | `dspy4s-evaluate` | `Evaluate`, metrics, LLM-as-judge. |
| optimize | `dspy4s-optimize` | Bootstrap few-shot, COPRO, MIPROv2. |
| gepa | `dspy4s-gepa` | The reflective Genetic-Pareto optimizer. |
| streaming | `dspy4s-streaming` | Synchronous streaming of predictions. |

## Next step

Head to the [Quickstart](quickstart.md) to write your first signature.
