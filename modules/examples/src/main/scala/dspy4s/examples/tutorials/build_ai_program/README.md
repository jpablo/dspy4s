# Build AI Programs with dspy4s

> Adapted for **dspy4s** from the DSPy docs page
> [`tutorials/build_ai_program/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/build_ai_program/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

A landing page for DSPy's "build a real application" tutorials. Most of these are Jupyter notebooks that
haven't been ported to dspy4s; the ones with a dspy4s counterpart are linked below, and the building blocks
they rely on (typed signatures, `ChainOfThought`, `ReAct`, `ProgramOfThought`) are all available.

| DSPy tutorial | dspy4s status |
|---|---|
| Managing Conversation History | 🚫 blocked — needs `dspy.History` + mutable demos ([dir](../conversation_history/)). |
| Building AI Agents | use `ReAct` + `ToolFunction` ([`learn/programming/Tools.scala`](../../learn/programming/Tools.scala)). |
| Customizing DSPy Modules | compose `Module[I, O]` values (see the tutorial examples that build multi-step pipelines). |
| Retrieval-Augmented Generation / Multi-Hop / RAG-as-agent | not ported — dspy4s has no retriever; wire your own retrieval into a `ToolFunction`. |
| Entity Extraction | see [`tutorials/email_extraction`](../email_extraction/EmailExtraction.scala) (typed structured extraction). |
| Classification | [classification notes](../classification/README.md); typed enum outputs (see `typed/CaseClassExample`). |
| Privacy-Conscious Delegation (PAPILLON) | external notebook — [notes](../papillon/README.md). |
| Program of Thought | `ProgramOfThought[I, O]` is available in the `programs` module. |
| Image generation / Audio | not ported — no multimodal surface. |

The reusable patterns are demonstrated across [`learn/programming`](../../learn/programming/README.md) and the
[real-world examples](../real_world_examples/README.md).
