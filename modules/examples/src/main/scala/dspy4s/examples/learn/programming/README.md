# Programming in dspy4s

> Adapted for **dspy4s** from the DSPy docs page
> [`learn/programming/overview.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/overview.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

The core idea carries over from DSPy: **write code, not prompt strings.** A conventional prompt entangles
four separable concerns — *what* the task is (a **signature**), *how* inputs/outputs are formatted on the
wire (an **adapter**), *what strategy* the LM applies like step-by-step reasoning or tool use (a **module**),
and the trial-and-error of phrasing it for a given LM (manual **optimization**). dspy4s separates these so you
can swap the LM or adapter, exchange one module for another, or optimize — without rewriting your logic.

How to start:

1. **Define the task.** What are the inputs and outputs? In dspy4s this is a typed `Signature[I, O]` — declared
   from a string (`Signature.fromString`), a function type (`Signature.fromType`), two case classes
   (`Signature.derived`), or a `Spec` trait (`Signature.of`).
2. **Define the initial pipeline.** Start simple — often a single `ChainOfThought` — then add steps, tools, or
   composition only as observations demand.
3. **Try a handful of examples.** Run a few easy and hard inputs through a capable LM to learn what's possible,
   and keep the interesting ones for evaluation and optimization later.

### Runnable examples in this folder

| File | Topic |
|---|---|
| [`Signatures.scala`](./Signatures.scala) | Declaring typed signatures (string / function / case-class / `Spec`). |
| [`Modules.scala`](./Modules.scala) | `Predict`, `ChainOfThought`, and composing modules. |
| [`Tools.scala`](./Tools.scala) | `ReAct` agents over `ToolFunction`s (incl. `ToolFunction.fromMethod`). |
| [`Adapters.scala`](./Adapters.scala) | `ChatAdapter` / `JSONAdapter`, and inspecting the formatted prompt. |
| [`LanguageModels.scala`](./LanguageModels.scala) | Configuring the LM, direct calls, generation params, usage, errors. |

Not yet portable here: `Assertions7.scala` (no `Assert`/`Suggest` — use `Refine`/`BestOfN`) and `Mcp.scala`
(no MCP bridge). See the [module README](../../../../../../../../README.md) for the full status table.
