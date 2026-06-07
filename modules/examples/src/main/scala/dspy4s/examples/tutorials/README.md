# Tutorials

> Adapted for **dspy4s** from the DSPy docs page
> [`tutorials/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port; links point at the dspy4s
> example files and note which DSPy tutorials are ported, blocked, or external.

dspy4s ports the *code-bearing* DSPy tutorials. The status legend and full inventory live in the
[examples module README](../../../../../../README.md). Grouped index:

### Build AI programs
- [Managing Conversation History](./conversation_history/) — 🚫 blocked (needs `dspy.History`, mutable demos).
- [Classification](./classification/README.md) — external tutorial; see notes + the typed/enum examples.
- [Privacy-Conscious Delegation (PAPILLON)](./papillon/README.md) — external notebook; see notes.

### Optimize AI programs
- [Overview](./optimize_ai_program/README.md) — dspy4s ports `BootstrapFewShot*`; see [`learn/optimization`](../learn/optimization/README.md).

### Reflective prompt evolution (GEPA)
- [Overview](./gepa_ai_program/README.md) — 🚫 GEPA optimizer not ported.

### Experimental RL optimization
- [Overview](./rl_ai_program/README.md) — 🚫 RL optimization not ported.

### Tools, development, and deployment
- [Output Refinement](./output_refinement/BestOfNAndRefine.scala) — ✅ `BestOfN` / `Refine`.
- [Cache](./cache/Cache.scala) — ✅ `ManagedLanguageModel` caches.
- [Streaming](./streaming/Streaming.scala) — ✅ synchronous `streamify`.
- [Async](./async/Async.scala) — ✅ `applyAsync`.
- [Debugging & Observability](./observability/Observability.scala) — ✅ callback handler.
- [Use MCP in DSPy](./mcp/README.md), [Saving and Loading](./saving/), [Deployment](./deployment/),
  [Tracking Optimizers](./optimizer_tracking/) — 🚫 blocked (no MCP / save-load / serving / MLflow).

### Real-world examples
- [Overview](./real_world_examples/README.md), and: [llms.txt](./llms_txt_generation/LlmsTxtGeneration.scala) ✅,
  [Email Extraction](./email_extraction/EmailExtraction.scala) ✅,
  [Mem0 ReAct Agent](./mem0_react_agent/) 🚫, [Yahoo Finance](./yahoo_finance_react/YahooFinanceReact.scala) ✅,
  [Code Generation](./sample_code_generation/SampleCodeGeneration.scala) ✅,
  [AI Text Game](./ai_text_game/AiTextGame.scala) ✅.
