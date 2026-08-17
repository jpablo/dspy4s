# Strategies and tools

`ChainOfThought`, `MultiChainComparison`, `ProgramOfThought`, `Refine`, `ReAct`, `CodeAct`, `RLM`, and `Ensemble` build
ordinary program syntax.

`ReAct` receives a generator, a tool-invocation program, and an extractor. Its control value is either `Finish` or
`Invoke`. Tool failures become typed trajectory observations.

Host tools use `Tool`, an immutable value with metadata and an effectful invocation function. `LiveToolBackend`
provides a set of tools to `Program.invokeTool`.
