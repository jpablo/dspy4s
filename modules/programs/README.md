# dspy4s `programs`

The program (module) layer — the inference patterns you actually compose: `Predict`, `ChainOfThought`,
`ReAct`, `CodeAct`, `ProgramOfThought`, `MultiChainComparison`, `BestOfN`, `Refine`, `RLM`, plus the
introspection type-class the optimizers rely on and the in-memory retrievers. Depends on `core`, `lm`,
`adapters`, and [`typed`](../typed/README.md).

## The core idea

Every program is a `Module` — a pure `forward: I => Either[DspyError, O]` wrapped by a `final` `apply` that
adds the universal lifecycle (callbacks, tracing, history). Subclasses implement only `forward`; bookkeeping is
never reimplemented and never mutated in place.

Programs live on two layers that share one engine:

- **The typed surface** — `Predict[I, O]`, `ChainOfThought[I, O]`, `ReAct[I, O]`, … bind static input/output
  types and encode/decode at the boundary.
- **The untyped spine** — `DynamicModule = Module[ProgramCall, DynamicPrediction]`, where programs build and
  augment signatures at runtime (e.g. `ChainOfThought` prepends a `reasoning` field). `DynamicPredict` is the
  runtime substrate both layers delegate to, through a single shared `PredictEngine`.

The bridge for optimization is `Predictors[P]`, the dspy4s analogue of Python's `named_predictors()`: it
enumerates a program's learnable sub-predictors (`read` / `readNamed`) and writes edited copies back
(`replace`), which is exactly what the [`optimize`](../optimize/README.md) and [`gepa`](../gepa/README.md)
modules drive.

## Key types

### Programs

| Type | Role |
|------|------|
| `Predict[I, O]` | The fundamental typed predictor: encode `I`, run the engine against the LM, decode into `Prediction[O]`. Delegates to a private memoized `DynamicPredict`. |
| `DynamicPredict` | The untyped predictor and shared substrate; serializable learnable state (layout, demos, config). |
| `ChainOfThought[I, O]` | Wraps `Predict` and prepends a `reasoning: String` output via `OutputAugmentation` (idempotent if `O` already has it). |
| `ReAct[I, O]` | Reasoning-and-acting agent: iterates over a tool set using a text protocol (`next_thought` / `next_tool_name` / `next_tool_args`), then a CoT-augmented extractor produces `O`. Learnable: `reactPredict`, `extractorPredict`. |
| `CodeAct[I, O]` | Generates and executes Python via a `CodeInterpreter` in a loop, then extracts outputs. Supports user tools bridged into the sandbox. |
| `ProgramOfThought[I, O]` | Three-pass code reasoning: generate → regenerate on error → answer. |
| `MultiChainComparison[I, O]` | Compares `m` candidate reasoning chains and synthesizes a corrected `rationale`. |
| `BestOfN[I, O]` | Runs an inner program up to `n` times (varied by `rolloutId`), keeps the highest-reward result; short-circuits at a threshold. |
| `Refine[P]` | Iterative feedback loop: runs, generates advice from the trace + reward, injects per-predictor hints into the next attempt. |
| `RLM[I, O]` | Recursive Language Model (experimental): long inputs become REPL variables the LM explores with generated code calling `llm_query()` / `SUBMIT()`. |
| `Parallel` | Concurrent executor over `(DynamicModule, ProgramCall)` tasks. |

### Contracts & introspection

| Type | Role |
|------|------|
| `Module[I, O]` | Base trait: pure `forward`, `final` lifecycle `apply`. `DynamicModule` is the untyped specialization. |
| `ProgramCall` / `TypedCall[I]` | The untyped / typed call arguments: inputs, config bag, `traceEnabled`, `rolloutId`. |
| `Prediction[O]` | Typed output `O` + the raw `DynamicPrediction` (completions, usage). |
| `Predictors[P]` / `Predictor[P]` | The introspection type-classes: a composite's learnable predictors (with dotted names like `"field.sub"`) and a single learnable leaf. Instances are hand-written for composites and structurally derived for case classes. |
| `ToolFunction` | The tool contract: `name`, `description`, `argSchema`, `invoke(args)`. `fromMethod` derives one from a method via a macro. |
| `Aggregation.majority` | Picks the most-common field value across candidate completions (ties to first). |
| `KNN` / `EmbeddingsRetriever` | Brute-force in-memory retrievers (no FAISS): nearest trainset examples by dot product, top-k passages by cosine. |

## Design notes

- **Module purity.** `forward` is side-effect-free; trace, history, and callbacks are a transparent `final`
  wrapper, so every program is observed identically with no subclass boilerplate. (The
  [module-purity memory](../../README.md): runtime owns bookkeeping, no `ProgramMeta`/`BaseModule`/`Parameter`.)
- **Two layers, neither wraps the other.** The typed predictors delegate to a private `DynamicPredict`, not a
  public sibling; both reach the LM through the same `PredictEngine`. Programs that reshape signatures at
  runtime construct `DynamicPredict(layout = augmented)` directly.
- **`Predictors` is the optimizer backbone.** Optimizers never special-case program types — they read the
  predictor genome through `Predictors`, build edited copies, and `replace`. This is why one optimizer codepath
  covers a bare `Predict` and an arbitrary composite.
- **Config layering and bound LMs.** Module-level and per-call `config` merge with per-call winning;
  `rolloutId` is a typed cache-busting field, not part of the provider bag. A predictor can bind its own
  `LanguageModel`, overriding the ambient context.
- **ReAct stays on the text protocol.** Tools are selected via output fields, not provider-native
  function-calling; tool failures become trajectory observations, and context-window overflow triggers
  trajectory truncation and retry. The native function-calling path is adapter-level and deliberately not
  wired into ReAct (see the [design memory](../../README.md)).

## Source layout

| Path | Contents |
|------|----------|
| `Predict.scala`, `DynamicPredict.scala` | the typed predictor and the untyped substrate |
| `ChainOfThought.scala`, `ReAct.scala`, `CodeAct.scala`, `RLM.scala`, `ProgramOfThought.scala`, `MultiChainComparison.scala` | the composite programs |
| `BestOfN.scala`, `Refine.scala`, `Parallel.scala`, `Aggregation.scala` | wrappers and utilities |
| `Predictors.scala` | the `Predictors`/`Predictor` introspection type-classes |
| `contracts/Module.scala`, `ProgramContracts.scala` | `Module`/`DynamicModule`, `ProgramCall`/`TypedCall`, `ToolFunction` |
| `retrievers/KNN.scala`, `EmbeddingsRetriever.scala` | in-memory retrieval |
| `runtime/PredictEngine.scala`, `SettingsProgramRuntime.scala`, `ParallelExecutor.scala`, `ToolExecutor.scala` | the shared execution body, model/adapter resolution, concurrency, tool dispatch |
| `internal/ToolMacro.scala` | the `ToolFunction.fromMethod` derivation macro |

## Relation to dspy

This ports `dspy.predict` and the module family. The shape decisions specific to dspy4s — pure modules with
runtime-owned bookkeeping, the typed/untyped split sharing one engine, and `Predictors` standing in for
`named_predictors()` — are what let the typed surface and the optimizers coexist over one substrate.
