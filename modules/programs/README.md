# dspy4s `programs`

`programs` contains the programs that you compose: `Predict`, `ChainOfThought`, `ReAct`, `MultiChainComparison`,
`BestOfN`, `Refine`, and `RLM`. It also contains the composition operators, the program capabilities for the
optimizers, and two in-memory retrievers. It uses `core`, `lm`, `adapters`, and [`signatures`](../signatures/README.md).

## Main concepts

- **A program is a `Module[I, O]`.** A program implements `forward`, which takes a `ProgramCall[I]` and returns
  `Either[DspyError, Prediction[O]]`. The `final apply` adds the callbacks, the trace, and the history.
- **Two kinds of programs.** Domain-valued programs, for example `Predict[I, O]`, encode the input `I` and decode the
  output `O`. Record-valued programs (`DynamicModule`) have records on each side, for a signature that exists only at
  runtime. `Predict` and `DynamicPredict` run the same `PredictEngine`.
- **Program capabilities.** `OptimizableStructure[P]` gives the predictors of a program in a fixed order. `read` gives
  their parameters, and `replace` writes new parameters back. `ProgramRunner[P]` runs a program from an input record.
  The [`optimize`](../optimize/README.md) and [`gepa`](../gepa/README.md) modules use these capabilities.

## Main types

### Programs

| Type | Description |
|------|-------------|
| `Predict[I, O]` | The base predictor. It encodes `I`, calls the LM through the adapter, and decodes the response. `withLm` binds a model to it. `erase` gives a `DynamicPredict` with the same configuration. |
| `DynamicPredict` | The predictor for a `SignatureLayout`. Its input and its output are records. |
| `ChainOfThought[I, O]` | Runs a `Predict` that has a `reasoning: String` output first. The output is a named tuple. |
| `ReAct[I, O]` | An agent that calls tools. In each step, the model writes `next_thought`, `next_tool_name`, and `next_tool_args`. When the model selects `finish`, or after `maxIterations` steps, an extractor writes the output with a `reasoning` field first. |
| `MultiChainComparison[I, O]` | Compares `m` candidate completions, and writes a corrected `rationale` and the answer. |
| `BestOfN` | Runs an inner program up to `n` times with different `rolloutId` values, and keeps the result with the highest reward. |
| `Refine` | Works like `BestOfN`. After an attempt with a reward below `threshold`, a critic predictor writes advice for the next attempt. |
| `RLM[I, O]` | A recursive language model. The inputs become variables in a Python REPL. The model writes code that reads them, calls `llm_query()`, and ends with `SUBMIT()`. |
| `DynamicSignature` | A signature that you parse at runtime. Each parse makes new input and output types, so its programs compose and optimize like domain-valued programs. |
| `Parallel` | Runs `(DynamicModule, ProgramCall)` tasks on more than one thread. |

### Composition

A composition node adds no callbacks, trace, or history of its own. The programs in it keep their own records, and
the optimizers can find their predictors.

- `Compose.lift` and `Compose.liftEither` make a program from a Scala function.
- `>>>` (or `andThen`) runs two programs in sequence.
- `mapOutput`, `contramapInput`, and `dimap` change the output type or the input type.
- `fanout` (`&&&`) runs two programs on one input. `split` (`***`) runs two programs on the two parts of a tuple.
- `recoverWith(policy)(fallback)` runs `fallback` when `policy` accepts the error.
- `mode(m)` changes the call controls of a program, for example with `Mode.temperature`.

### Contracts and tools

| Type | Description |
|------|-------------|
| `ProgramCall[I]` | One call: the input, the `config` options, `traceEnabled`, and `rolloutId`. |
| `Prediction[O]` | The output `O` and its `RawPrediction` (`raw`), with the completions and the token usage. |
| `OptimizableStructure[P]` / `OptimizableLeaf[P]` | The predictors of a program, and one predictor. The built-in composites have their own instances, and the compiler derives an instance for a case class. |
| `ToolFunction` | A tool: `name`, `description`, `argSchema`, and `invoke(args)`. `ToolFunction.fromMethod` makes a tool from a method. |
| `AgentLoop` / `TrajectoryAgent` | The bounded loop of the agents, and the template of `ReAct`: a loop that makes a trajectory, and then one extraction. |
| `Aggregation.majority` | Selects the most frequent value of a field in the candidate completions. |
| `KNN` / `EmbeddingsRetriever` | `KNN` finds the nearest trainset examples by dot product. `EmbeddingsRetriever` finds the top-k passages by cosine similarity. |

## Design notes

- **One module event for each `Predict` call.** `Predict` and `DynamicPredict` each have their own `PredictEngine`.
  Thus a `Predict` call sends one `predict` module event, with no second event from an inner `DynamicPredict`.
- **Config and bound models.** The `config` of a module and the `config` of a call merge. On a key collision, the
  value of the call has priority. A model that you bind to a predictor has priority over the model of the context.
- **The conversation history is an input.** A signature takes the earlier turns in one input field of type
  `History`, and the adapter sends them (see [`adapters`](../adapters/README.md)). A native tool call keeps its
  provider `id`. Thus you can put the call and its `ToolCallResult` into the history of the next call.
- **ReAct uses a text protocol.** The model selects a tool through output fields. ReAct does not use the native
  function calls of the adapters. A tool failure becomes an observation in the trajectory. On a context window
  overflow, ReAct removes the oldest trajectory entry and tries again.
- **Fixed agent templates.** `TrajectoryAgent.forward` and `InterpretedTrajectoryAgent.trajectoryStep` are `final`. A
  subclass selects its action type, but it cannot change the order of the steps.

## Source layout

| Package | Contents |
|---------|----------|
| top level | `Parallel`, `Aggregation`, `ProgramRunner`, `RecordCodec`, `DynamicSignature`, and the limit types, for example `AttemptCount` |
| `strategies/` | `Predict`, `DynamicPredict`, and the other programs |
| `compose/` | the composition operators and `Mode` |
| `contracts/` | `Module`, `ProgramCall`, `Prediction`, `ModuleLifecycle`, `ToolFunction`, and `ActionInterpreter` |
| `optimization/` | `OptimizableStructure`, `OptimizableLeaf`, `OptimizableParameters`, and their instances |
| `algebra/` | `Program` (a module with a fixed number of predictors) and the `algebra` instances for programs |
| `runtime/` | `PredictEngine`, the agent loops, `ParallelExecutor`, and `ToolExecutor` |
| `retrievers/` | `KNN` and `EmbeddingsRetriever` |
| `internal/` | the macro of `ToolFunction.fromMethod` |

## Relation to dspy

`programs` is the port of `dspy.predict` and of the module classes. These are the main differences:

- A program has no state that changes, and the runtime keeps the trace and the history.
- Domain-valued programs and record-valued programs use one engine.
- `OptimizableStructure` takes the place of `named_predictors()`.
