# dspy4s examples

This module keeps Scala ports next to the DSPy examples that inspired them. Comments in each source file show the
corresponding Python construction. The Scala code uses immutable `Program` values, stable `ParameterId` values, typed
composition, and explicit execution backends.

Compile every example with:

```bash
sbt examples/compile
```

Run one example with:

```bash
sbt "examples/runMain dspy4s.examples.functionalQuickstart"
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.modulesMain"
```

`FunctionalQuickstart.scala`, `FunctionalOptimization.scala`, and `FunctionalTools.scala` are small offline entry
points. The restored DSPy comparison suite is under `src/main/scala/dspy4s/examples`:

- `signatures/`: builder, function, case-class, and `Spec` signature forms.
- `deep_dive/data_handling/`: examples, custom input data, and deterministic dataset split conversion.
- `learn/programming/`: signatures, programs, adapters, language models, tools, MCP boundaries, and modern refinement
  in place of deprecated assertions.
- `learn/evaluation/`: data, function metrics, effectful judge metrics, and `Evaluate`.
- `learn/optimization/`: immutable few-shot optimization and parameter persistence.
- `tutorials/`: composition, code generation, ReAct, RLM, refinement, streaming, async execution, cache,
  observability, persistence, optimizer tracking, conversation history, deployment, MCP, and memory tools.
- `verify/`: live COPRO, MIPROv2, and GEPA smoke programs.

Some Python integrations have no bundled Scala client. Their examples still compile and define explicit replacement
boundaries: `McpSession` for MCP transports, `MemoryStore` for Mem0, an injected retrieval `Program`, and a
framework-neutral deployment route. This keeps the program design usable without adding those libraries as
dependencies.
