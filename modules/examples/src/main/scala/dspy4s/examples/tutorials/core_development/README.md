# Tools, Development, and Deployment

> Adapted for **dspy4s** from the DSPy docs page
> [`tutorials/core_development/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/core_development/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

Essential features for building production-ready dspy4s systems. Each links to the runnable dspy4s example (or
notes why it's blocked):

| Topic | dspy4s |
|---|---|
| Output Refinement | ✅ [`output_refinement/BestOfNAndRefine.scala`](../output_refinement/BestOfNAndRefine.scala) — `BestOfN` / `Refine`. |
| Cache | ✅ [`cache/Cache.scala`](../cache/Cache.scala) — `ManagedLanguageModel` + `InMemory`/`Disk`/custom `LmCache`. |
| Streaming | ✅ [`streaming/Streaming.scala`](../streaming/Streaming.scala) — synchronous `streamify` → `ClosableIterator[StreamEvent]`. |
| Async | ✅ [`async/Async.scala`](../async/Async.scala) — `Module.applyAsync` / `ContextPropagation.future`. |
| Debugging & Observability | ✅ [`observability/Observability.scala`](../observability/Observability.scala) — `CallbackHandler` over the event stream. |
| Use MCP in DSPy | ✅ [`mcp/Mcp.scala`](../mcp/Mcp.scala): the tools of an MCP server as `ToolFunction` values (`McpTools.fromClient`). |
| Saving and Loading | ✅ [`saving/Saving.scala`](../saving/Saving.scala): `ProgramPersistence.save` and `load` write and read the state of a program as JSON. |
| Deployment | 🚫 [dir](../deployment/) — no FastAPI/MLflow serving; wrap a program in your own HTTP layer. |
| Tracking DSPy Optimizers | ✅ [`optimizer_tracking/OptimizerTracking.scala`](../optimizer_tracking/OptimizerTracking.scala): a `CallbackHandler` observes the optimizer. dspy4s has no MLflow integration. |
