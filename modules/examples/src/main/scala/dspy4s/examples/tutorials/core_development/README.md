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
| Use MCP in DSPy | 🚫 [notes](../mcp/README.md) — no MCP client/tool bridge. |
| Saving and Loading | 🚫 [dir](../saving/) — programs have no `.save`/`.load`. |
| Deployment | 🚫 [dir](../deployment/) — no FastAPI/MLflow serving; wrap a program in your own HTTP layer. |
| Tracking DSPy Optimizers | 🚫 [dir](../optimizer_tracking/) — no MLflow integration. |
