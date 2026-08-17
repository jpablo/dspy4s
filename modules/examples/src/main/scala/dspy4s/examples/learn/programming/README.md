# Programming in dspy4s

> Adapted for **dspy4s** from the DSPy docs page
> [`learn/programming/overview.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/overview.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

The core idea carries over from DSPy: **write code, not prompt strings.** A conventional prompt entangles
four separable concerns — *what* the task is (a **signature**), *how* inputs/outputs are formatted on the
wire (an **adapter**), *what strategy* the LM applies like step-by-step reasoning or tool use (a **program**),
and the trial-and-error of phrasing it for a given LM (manual **optimization**). dspy4s separates these so you
can swap the LM or adapter, exchange one module for another, or optimize — without rewriting your logic.

How to start:

1. **Define the task.** What are the inputs and outputs? In dspy4s this is a `Signature[I, O]` — declared
   from a string (`Signature.fromString`), a function type (`Signature.fromType`), two case classes
   (`Signature.derived`), or a `Spec` trait (`Signature.of`).
2. **Define the initial pipeline.** Start simple — often a single `ChainOfThought` — then add steps, tools, or
   composition only as observations demand.
3. **Try a handful of examples.** Run a few easy and hard inputs through a capable LM to learn what's possible,
   and keep the interesting ones for evaluation and optimization later.

### Runnable examples in this folder

| File | Topic |
|---|---|
| [`Signatures.scala`](./Signatures.scala) | Declaring signatures (string / function / case-class / `Spec`). |
| [`Modules.scala`](./Modules.scala) | `Program.predict`, `ChainOfThought`, and typed composition. |
| [`Tools.scala`](./Tools.scala) | `ReAct` over explicit `Tool` and `ToolBackend` values. |
| [`Adapters.scala`](./Adapters.scala) | `ChatAdapter` / `JSONAdapter`, and inspecting the formatted prompt. |
| [`LanguageModels.scala`](./LanguageModels.scala) | Configuring the LM, direct calls, generation params, usage, errors. |
| [`Assertions7.scala`](./Assertions7.scala) | `Refine` as the replacement for deprecated `Assert` / `Suggest`. |
| [`Mcp.scala`](./Mcp.scala) | An `McpSession` boundary that converts remote tools to `Tool`. |

The MCP example does not provide an HTTP or stdio transport client. It defines the boundary that such a client must
implement.
