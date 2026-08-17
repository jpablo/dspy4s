# Tools, Development, and Deployment

> Adapted for **dspy4s** from the DSPy docs page
> [`tutorials/core_development/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/core_development/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

Essential features for building production-ready dspy4s systems. Each link opens a runnable dspy4s example:

| Topic | dspy4s |
|---|---|
| Output Refinement | ✅ [`output_refinement/BestOfNAndRefine.scala`](../output_refinement/BestOfNAndRefine.scala) — `BestOfN` / `Refine`. |
| Cache | ✅ [`cache/Cache.scala`](../cache/Cache.scala) — `ManagedLanguageModel` + `InMemory`/`Disk`/custom `LmCache`. |
| Streaming | ✅ [`streaming/Streaming.scala`](../streaming/Streaming.scala) — `ProgramEventStream`. |
| Async | ✅ [`async/Async.scala`](../async/Async.scala) — run the ZIO interpreter as a future. |
| Debugging & Observability | ✅ [`observability/Observability.scala`](../observability/Observability.scala) — `ProgramObserver` and journals. |
| Use MCP in DSPy | ✅ [`mcp/Mcp.scala`](../mcp/Mcp.scala) — remote tools behind `McpSession`; transport injected. |
| Saving and Loading | ✅ [`saving/Saving.scala`](../saving/Saving.scala) — `ProgramPersistence`. |
| Deployment | ✅ [`deployment/Deployment.scala`](../deployment/Deployment.scala) — framework-neutral endpoint effect. |
| Tracking DSPy Optimizers | ✅ [`optimizer_tracking/OptimizerTracking.scala`](../optimizer_tracking/OptimizerTracking.scala) — backend decoration. |
