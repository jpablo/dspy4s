# Cheatsheet

A one-page index of the dspy4s surface. Each row links to the page that covers
it in full. For runnable versions of every construct below, see
[`Cheatsheet.scala`](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/Cheatsheet.scala)
in the examples module.

## Declare a task

| I want to | Use | Page |
|---|---|---|
| Declare typed inputs and outputs | `Signature.fromType` / `Signature.fromString` | [Signatures](../programs/signatures.md) |
| Use named fields, enums, custom types | `Spec` trait + `Signature.of[T]` | [Signatures](../programs/signatures.md) |

## Run a task

| I want to | Use | Page |
|---|---|---|
| Answer directly | `Predict` | [Modules](../programs/modules.md) |
| Reason first | `ChainOfThought` | [Modules](../programs/modules.md) |
| Call tools in a loop | `ReAct` | [Tools & ReAct](../programs/tools-and-react.md) |
| Sample and keep the best | `BestOfN` / `Refine` | [Modules](../programs/modules.md) |
| Chain steps into one program | `DynamicModule` | [Composing programs](../programs/composing.md) |

## Talk to a model

| I want to | Use | Page |
|---|---|---|
| Configure a model | `OpenAiLanguageModel` | [Configuring an LM](../language-models/configuring.md) |
| Pick text vs JSON output | `ChatAdapter` / `JSONAdapter` | [Adapters](../language-models/adapters.md) |
| Cache or track usage | `ManagedLanguageModel`, `UsageTracking` | [Caching & usage](../language-models/caching-and-usage.md) |

## Measure and improve

| I want to | Use | Page |
|---|---|---|
| Build labeled data | `Example`, `withInputs` | [Examples & data](../evaluation/examples-and-data.md) |
| Score outputs | `FunctionMetric`, `SemanticF1` | [Metrics](../evaluation/metrics.md) |
| Run a batch evaluation | `Evaluate` | [Running evaluations](../evaluation/running-evaluations.md) |
| Improve demonstrations | `BootstrapFewShot`, `KNNFewShot` | [Few-shot](../optimization/few-shot.md) |
| Improve instructions | `COPRO`, `MIPROv2`, `GEPA` | [Instructions](../optimization/instructions.md) |

## Operate

| I want to | Use | Page |
|---|---|---|
| Configure the active model/adapter | `RuntimeContext`, `RuntimeEnvironment` | [Runtime context](../runtime/runtime-context.md) |
| Observe a run | `CallbackHandler`, `inspectHistory` | [Observability](../runtime/observability.md) |
| Persist a tuned program | `ProgramPersistence` | [Saving & loading](../runtime/saving-and-loading.md) |
| Stream output | `Streamify`, `StreamListener` | [Streaming](../runtime/streaming.md) |
