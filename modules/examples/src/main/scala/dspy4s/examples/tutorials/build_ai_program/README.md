# Build AI Programs with dspy4s

> Adapted for **dspy4s** from the DSPy docs page
> [`tutorials/build_ai_program/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/build_ai_program/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

A landing page for DSPy's "build a real application" tutorials. Most of these are Jupyter notebooks that
haven't been ported to dspy4s; the ones with a dspy4s counterpart are linked below, and the building blocks
they rely on (signatures, `ChainOfThought`, `ReAct`, `ProgramOfThought`) are all available.

| DSPy tutorial | dspy4s status |
|---|---|
| Managing Conversation History | typed immutable history ([source](../conversation_history/ConversationHistory.scala)). |
| Building AI Agents | use `ReAct` + `Tool` + `ToolBackend` ([source](../../learn/programming/Tools.scala)). |
| Customizing DSPy Modules | compose `ProgramWithEnv[I, O, R]` values with `>>>`, `&&&`, and other generic combinators. |
| Retrieval-Augmented Generation / Multi-Hop / RAG-as-agent | inject retrieval as a child `Program`; see the Baleen shape in [`Assertions7.scala`](../../learn/programming/Assertions7.scala). |
| Entity Extraction | see [`tutorials/email_extraction`](../email_extraction/EmailExtraction.scala) (structured extraction). |
| Classification | [classification notes](../classification/README.md); enum outputs (see `signatures/CaseClassExample`). |
| Privacy-Conscious Delegation (PAPILLON) | external notebook — [notes](../papillon/README.md). |
| Program of Thought | `ProgramOfThought[I, O]` is available in the `programs` module. |
| Image generation / Audio | not ported — no multimodal surface. |

The reusable patterns are demonstrated across [`learn/programming`](../../learn/programming/README.md) and the
[real-world examples](../real_world_examples/README.md).
