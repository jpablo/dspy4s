# Real-World Examples

> Adapted for **dspy4s** from the DSPy docs page
> [`tutorials/real_world_examples/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/real_world_examples/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

End-to-end applications. All but one are ported and runnable in dspy4s (each makes live LM calls — set
`OPENAI_API_KEY`):

| Example | dspy4s | Key concepts |
|---|---|---|
| 📄 Generating llms.txt | ✅ [`llms_txt_generation/LlmsTxtGeneration.scala`](../llms_txt_generation/LlmsTxtGeneration.scala) | composing `ChainOfThought`s; documentation generation. |
| 📧 Email Information Extraction | ✅ [`email_extraction/EmailExtraction.scala`](../email_extraction/EmailExtraction.scala) | typed structured extraction (enums, `List`, `Option`). |
| 🧠 Memory-Enabled ReAct Agent (Mem0) | 🚫 [dir](../mem0_react_agent/) | no mem0 integration; the ReAct part is portable. |
| 💰 Financial Analysis (Yahoo Finance) | ✅ [`yahoo_finance_react/YahooFinanceReact.scala`](../yahoo_finance_react/YahooFinanceReact.scala) | `ReAct` + `ToolFunction.fromMethod` (live data stubbed). |
| 🔄 Code Generation from Docs | ✅ [`sample_code_generation/SampleCodeGeneration.scala`](../sample_code_generation/SampleCodeGeneration.scala) | multi-signature pipeline (fetching out of scope). |
| 🎮 Creative Text-Based AI Game | ✅ [`ai_text_game/AiTextGame.scala`](../ai_text_game/AiTextGame.scala) | composed signatures; `Map`/`List` outputs (game loop out of scope). |
