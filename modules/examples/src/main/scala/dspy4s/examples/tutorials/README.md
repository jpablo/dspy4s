# Tutorials

> Adapted for **dspy4s** from the DSPy docs page
> [`tutorials/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port; links point at the dspy4s
> example files and note which DSPy tutorials are ported, blocked, or external.

dspy4s ports the *code-bearing* DSPy tutorials. The status legend and full inventory live in the
[examples module README](../../../../../../README.md). Grouped index:

### Build AI programs
- [Managing Conversation History](./conversation_history/ConversationHistory.scala) — ✅ immutable typed history.
- [Classification](./classification/README.md) — external tutorial; see notes and enum examples.
- [Privacy-Conscious Delegation (PAPILLON)](./papillon/README.md) — external notebook; see notes.

### Optimize AI programs
- [Overview](./optimize_ai_program/README.md) — dspy4s ports `BootstrapFewShot*`; see [`learn/optimization`](../learn/optimization/README.md).

### Reflective prompt evolution (GEPA)
- [Overview](./gepa_ai_program/README.md) — ✅ GEPA optimizer ported (`dspy4s-gepa`); see the live smoke harness
  [`gepaSmokeMain`](../verify/GepaSmokeTest.scala). The upstream notebook tutorials (AIME, etc.) are not ported.

### Experimental RL optimization
- [Overview](./rl_ai_program/README.md) — 🚫 RL optimization not ported.

### Tools, development, and deployment
- [Output Refinement](./output_refinement/BestOfNAndRefine.scala) — ✅ `BestOfN` / `Refine`.
- [Cache](./cache/Cache.scala) — ✅ `ManagedLanguageModel` caches.
- [Streaming](./streaming/Streaming.scala) — ✅ typed `ProgramEventStream`.
- [Async](./async/Async.scala) — ✅ run the ZIO interpreter as a future.
- [Debugging & Observability](./observability/Observability.scala) — ✅ `ProgramObserver` and journals.
- [Use MCP in DSPy](./mcp/Mcp.scala) — ✅ program and tool conversion; transport is an injected `McpSession`.
- [Saving and Loading](./saving/Saving.scala) — ✅ immutable parameter persistence.
- [Deployment](./deployment/Deployment.scala) — ✅ framework-neutral route effect.
- [Tracking Optimizers](./optimizer_tracking/OptimizerTracking.scala) — ✅ backend decoration.

### Real-world examples
- [Overview](./real_world_examples/README.md), and: [llms.txt](./llms_txt_generation/LlmsTxtGeneration.scala) ✅,
  [Email Extraction](./email_extraction/EmailExtraction.scala) ✅,
  [Mem0 ReAct Agent](./mem0_react_agent/Mem0ReactAgent.scala) ✅ with an injected memory store,
  [Yahoo Finance](./yahoo_finance_react/YahooFinanceReact.scala) ✅,
  [Code Generation](./sample_code_generation/SampleCodeGeneration.scala) ✅,
  [AI Text Game](./ai_text_game/AiTextGame.scala) ✅.
