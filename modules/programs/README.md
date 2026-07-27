# dspy4s `programs`

The program (module) layer — the inference patterns you actually compose: `Predict`, `ChainOfThought`,
`ReAct`, `CodeAct`, `ProgramOfThought`, `MultiChainComparison`, `BestOfN`, `Refine`, `RLM`, plus the
introspection type-class the optimizers rely on and the in-memory retrievers. Depends on `core`, `lm`,
`adapters`, and [`typed`](../typed/README.md).

## The core idea

Every program is a `Module[I, O]`: its semantic computation is wrapped uniformly as
`ProgramCall[I] => Either[DspyError, Prediction[O]]`. A `final apply` adds the universal lifecycle (callbacks, tracing,
history); subclasses implement only `forward`, so bookkeeping is never reimplemented or mutated in place.

Programs live on two layers that share one engine:

- **The typed surface** — `Predict[I, O]`, `ChainOfThought[I, O]`, `ReAct[I, O]`, … bind static input/output
  types and encode/decode at the boundary.
- **The dynamic spine** — `DynamicModule = Module[DynamicValue.Record, DynamicValue.Record]`, where programs can build
  and augment signatures at runtime. Its `RawPrediction` is lifted through `Prediction.dynamic` at the module
  boundary. `DynamicPredict` is the executable prediction leaf on this spine. The typed
  `Predict[I, O]` is its sibling: each is a thin module over the same `PredictEngine` execution body.

The bridge for optimization is `OptimizableTraversal[P]`, the dspy4s analogue of Python's `named_predictors()`: it exposes
non-executable predictor views (`inspect` / `readIdentified`) and `OptimizableParameters` values (`read`), then writes
an arity-matched parameter vector back through `replace`. This is what the [`optimize`](../optimize/README.md) and
[`gepa`](../gepa/README.md) modules drive.

## Key types

### Programs

| Type | Role |
|------|------|
| `Predict[I, O]` | The fundamental typed predictor: encode `I`, run its `PredictEngine` against the LM, decode into `Prediction[O]`. |
| `DynamicPredict` | The dynamic predictor for runtime-known layouts: accept a `ProgramCall[DynamicValue.Record]`, run the shared engine, and return `Prediction[DynamicValue.Record]`. |
| `OptimizableParameters` | The writable optimizer/persistence carrier: instructions, demos, and module config only. |
| `OptimizableView` | A non-executable snapshot pairing `OptimizableParameters` with read-only signature structure and module name. |
| `ChainOfThought[I, O]` | Wraps `Predict` and prepends a `reasoning: String` output via `OutputAugmentation` (idempotent if `O` already has it). |
| `ReAct[I, O]` | Reasoning-and-acting agent: iterates over a tool set using a text protocol (`next_thought` / `next_tool_name` / `next_tool_args`), then a CoT-augmented extractor produces `O`. Learnable: `reactPredict`, `extractorPredict`. |
| `CodeAct[I, O]` | Generates and executes Python via a `CodeInterpreter` in a loop, then extracts outputs. Supports user tools bridged into the sandbox. |
| `ProgramOfThought[I, O]` | Three-pass code reasoning: generate → regenerate on error → answer. Its three stable predictors are optimizer-addressable. |
| `MultiChainComparison[I, O]` | Compares `m` candidate reasoning chains and synthesizes a corrected `rationale`. |
| `BestOfN[I, O]` | Runs an inner program up to `n` times (varied by `rolloutId`), keeps the highest-reward result; short-circuits at a threshold. |
| `Refine[P]` | Iterative feedback loop: runs, generates advice from the trace + reward, injects per-predictor hints into the next attempt. |
| `RLM[I, O]` | Recursive Language Model (experimental): long inputs become REPL variables the LM explores with generated code calling `llm_query()` / `SUBMIT()`. |
| `Parallel` | Concurrent executor over `(DynamicModule, ProgramCall)` tasks. |

### Composition

Typed modules compose without adding lifecycle noise: structural nodes are transparent, while semantic leaves retain
their callbacks, trace, history, and optimizer-addressable predictors.

- `Compose.lift` / `liftEither` embed local transformations.
- `>>>` composes dependent stages.
- `mapOutput`, `contramapInput`, and `dimap` adapt typed boundaries while preserving the inner raw prediction.
- `fanout` pairs two programs over one shared input, left-to-right; `parallel` remains its compatibility name.
- `split` pairs two programs over independent tuple inputs, left-to-right; `tensor` remains its compatibility name.
- `recoverWith(policy)(fallback)` makes error selection explicit and retains both branches for optimization.

### Contracts & introspection

| Type | Role |
|------|------|
| `Module[I, O]` | Semantic program trait: `forward` returns `Prediction[O]`; `final apply` owns the lifecycle. `DynamicModule` specializes both sides to records. |
| `ProgramCall[I]` | The uniform call envelope: input carrier `I`, config bag, `traceEnabled`, and `rolloutId`; `mapInput` preserves the controls. |
| `ProgramRunner[P]` | Runs typed or dynamic `P` from a `ProgramCall[DynamicValue.Record]`; shared by evaluation, optimization, and streaming. |
| `Prediction[O]` | Typed output `O` + its `RawPrediction` evidence (completions, usage). |
| `OptimizableTraversal[P]` / `OptimizableLeaf[P]` | The introspection type-classes: a composite's learnable predictors (with dotted names like `"field.sub"`) and a single learnable leaf. Instances are hand-written for composites and structurally derived for case classes. |
| `ToolFunction` | The tool contract: `name`, `description`, `argSchema`, `invoke(args)`. `fromMethod` derives one from a method via a macro. |
| `Aggregation.majority` | Picks the most-common field value across candidate completions (ties to first). |
| `KNN` / `EmbeddingsRetriever` | Brute-force in-memory retrievers (no FAISS): nearest trainset examples by dot product, top-k passages by cosine. |

## Design notes

- **Module purity.** `forward` is side-effect-free; trace, history, and callbacks are a transparent `final`
  wrapper, so every program is observed identically with no subclass boilerplate. (The
  [module-purity memory](../../README.md): runtime owns bookkeeping, no `ProgramMeta`/`BaseModule`/`Parameter`.)
- **Two sibling layers with explicit erasure.** `Predict[I, O]` and `DynamicPredict` each own a `PredictEngine` built
  from their signature representation, so a typed call emits one `predict` module lifecycle rather than a
  wrapper-over-dynamic pair. `Predict.erase` creates a one-way dynamic snapshot with the same engine state;
  programs that start with a runtime-known layout construct `DynamicPredict` directly.
- **`OptimizableTraversal` is the optimizer backbone.** Optimizers never special-case program types — they read the
  predictor genome through `OptimizableTraversal`, build edited copies, and `replace`. This is why one optimizer codepath
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
| `Predict.scala`, `DynamicPredict.scala` | sibling statically typed and dynamic predictors over the shared engine |
| `ChainOfThought.scala`, `ReAct.scala`, `CodeAct.scala`, `RLM.scala`, `ProgramOfThought.scala`, `MultiChainComparison.scala` | the composite programs |
| `BestOfN.scala`, `Refine.scala`, `Parallel.scala`, `Aggregation.scala` | wrappers and utilities |
| `optimization/OptimizableLeaf.scala`, `OptimizableTraversal.scala` | leaf lens and composite optimizer-traversal typeclasses |
| `optimization/CompositeOptimizableTraversalInstances.scala`, `OptimizableTraversalDerivation.scala` | built-in composite instances and strict Mirror derivation |
| `contracts/Module.scala`, `ProgramCall.scala`, `ProgramRuntime.scala` | module boundary, call envelope, and runtime resolution contracts |
| `contracts/ToolFunction.scala`, `ToolCall.scala` | callable tools and their invocation messages |
| `ProgramRunner.scala` | the shared typed/dynamic record-running capability |
| `ProgramInput.scala`, `RecordCodec.scala` | coherent decoding capabilities for the dynamic-to-typed input boundary |
| `retrievers/KNN.scala`, `EmbeddingsRetriever.scala` | in-memory retrieval |
| `runtime/PredictEngine.scala`, `SettingsProgramRuntime.scala`, `ParallelExecutor.scala`, `ToolExecutor.scala` | the shared execution body, model/adapter resolution, concurrency, tool dispatch |
| `internal/ToolMacro.scala` | the `ToolFunction.fromMethod` derivation macro |

## Relation to dspy

This ports `dspy.predict` and the module family. The shape decisions specific to dspy4s — pure modules with
runtime-owned bookkeeping, the static/dynamic split sharing one engine, and `OptimizableTraversal` standing in for
`named_predictors()` — are what let the typed surface and the optimizers coexist over one substrate.
