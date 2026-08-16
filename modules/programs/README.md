# dspy4s `programs`

The program (module) layer — the inference patterns you actually compose: `Predict`, `ChainOfThought`,
`ReAct`, `CodeAct`, `ProgramOfThought`, `MultiChainComparison`, `BestOfN`, `Refine`, `RLM`, plus the
introspection type-class the optimizers rely on and the in-memory retrievers. Depends on `core`, `lm`,
`adapters`, and [`signatures`](../signatures/README.md).

## Target architecture

The primary API is exposed from `dspy4s.programs`. Its implementation stays in the temporary `plan` source package
while old sources are removed. `Program[I, O]` is the common prediction-program alias. `ProgramWithEnv[I, O, R]` also
records the service requirement `R`. Program syntax cannot run itself and it does not contain a language model,
adapter, runner, callback registry, or mutable parameter object. The old record-boundary type class is now named
`LegacyProgramRunner`.

- `Program.predict`, `identity`, `lift`, `>>>`, `&&&`, `***`, `|||`, `map`, `contramap`, `local`, `localWithInput`,
  `localParametersWith`, `recoverWith`, `attempt`, `collectAll`, `collectAllPar`, `bestOfN`, and bounded `iterate` build
  one closed structural language.
- `collectAll` is ordered and fail-fast. `collectAllPar` runs homogeneous visible members with bounded ZIO
  parallelism and keeps member order in its output. Compose each member with `attempt` to retain ordered partial
  failures as data.
- `localParametersWith` runs a visible typed configurator and applies its result to an immutable parameter store for
  one inner run. It supports input-dependent parameterization without mutation or a hidden dynamic program tree.
- `withEvidence` makes a complete prediction available as typed data. `Program.fromEvidence` is its selection dual: a
  prediction carried as output becomes the final output and raw evidence.
- `repeatUntil` builds a reusable bounded strategy from existing nodes. It does not add a hidden execution loop.
- `FeedbackRetry` makes the feedback critic a visible program from an attempted prediction to the next typed input.
- Functional `Refine` scores attempts, sends typed attempt data to a visible critic, and routes its advice through
  stable `ParameterId` values. Advice changes only the next run. Equal scores retain the earlier attempt.
- `ProgramRunner` is a stateless ZIO interpreter. `PredictionBackend` and `ProgramObserver` are explicit services.
  Streaming-capable prediction backends emit neutral `PredictionChunk` values through the same interpreter call.
- `Program.executeCode` returns the neutral `CodeExecutionResult` and requires `CodeExecutionBackend`. Composition with prediction computes
  `PredictionBackend & CodeExecutionBackend`.
- `Program.invokeTool` requires `ToolBackend`. `LiveToolBackend` adapts current `ToolFunction` values without global
  callbacks.
- `Program.executeRepl` requires `ReplExecutionBackend`. `LiveReplExecutionBackend.layer` owns one persistent
  interpreter in a ZIO scope and closes it after success or failure.
- `ParameterStore` keeps optimizer values separate from syntax and keys them with stable `ParameterId` values.
- `ProgramGraph` interprets the same syntax without running it.
- `RecordProgram` adds a `Shape[I]` decoder only at the dataset and optimizer boundary.
- `ChainOfThought` is a signature transformation plus one prediction node. It is not a wrapper class.
- `MultiChainComparison` is a validated input transformation followed by one prediction node.
- `ProgramOfThought` composes generator, regenerator, executor, and answerer programs around one visible bounded loop.
- Functional `ReAct` composes a typed `Finish | Invoke` action loop, tool program, and extractor. Tool failures become
  typed trajectory data through `attempt`; finish is not a synthetic tool.
- Functional `CodeAct` composes generator, parser, code executor, and extractor programs. Parse and code-domain
  failures are trajectory data; code-service failures remain in ZIO's typed error channel.
- Functional `RLM` composes action generation, typed REPL execution, direct submission, and exhausted-budget
  extraction. `RLM.replExecutor` adapts typed input and output shapes to the neutral REPL capability.
- Functional `Ensemble` uses `collectAll` over homogeneous visible members and reduces their complete typed prediction
  evidence. Execution is ordered and fail-fast.
- `LivePredictionBackend` isolates the current blocking adapter and LM contracts behind one effect boundary.

`LabeledFewShot` already consumes the new `ProgramParameters` capability. Its legacy compatibility instance lets old
programs use the same optimizer during migration. `ProgramBootstrapFewShot` runs a `RecordProgram` with explicit ZIO
effects and writes accepted demos through stable parameter IDs.

`ProgramEventStream` exposes ordered interpreter events, live prediction chunks, and the typed final prediction as a
`ZStream`. Chunk events carry the same call and parent IDs as prediction start, completion, and failure events.

`DeepProgramPlanSuite` checks execution and graph interpretation with 20,000 sequential nodes, 20,000 loop transitions,
and 20,000 `collectAll` members. The parameter state
codec saves only stable ID-to-value data; it does not claim that Scala functions, tools, syntax, or services are
serializable.

See [the implementation record](../../docs/refactor/program-ast-interpreters.md) for the design verdict and migration
order.

## Legacy architecture

Every program is a `Module[I, O]`: its semantic computation is wrapped uniformly as
`ProgramCall[I] => Either[DspyError, Prediction[O]]`. A `final apply` adds the universal lifecycle (callbacks, tracing,
history); subclasses implement only `forward`, so bookkeeping is never reimplemented or mutated in place.

Programs live on two layers that share one engine:

- **Domain-valued programs** — `Predict[I, O]`, `ChainOfThought[I, O]`, `ReAct[I, O]`, … bind input/output
  types and encode/decode at the boundary.
- **The dynamic spine** — `DynamicModule = Module[DynamicValue.Record, DynamicValue.Record]`, where programs can build
  and augment signatures at runtime. Its `RawPrediction` is lifted through `Prediction.dynamic` at the module
  boundary. `DynamicPredict` is the executable prediction leaf on this spine. The
  `Predict[I, O]` is its sibling: each is a thin module over the same `PredictEngine` execution body.

The bridge for optimization is `OptimizableStructure[P]`, the dspy4s analogue of Python's `named_predictors()`: it exposes
non-executable predictor views (`inspect` / `readIdentified`) and `OptimizableParameters` values (`read`), then writes
an arity-matched parameter vector back through `replace`. This is what the [`optimize`](../optimize/README.md) and
[`gepa`](../gepa/README.md) modules drive.

## Key types

### Programs

| Type | Role |
|------|------|
| `Predict[I, O]` | The fundamental predictor: encode `I`, run its `PredictEngine` against the LM, decode into `Prediction[O]`. |
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

Modules compose without adding lifecycle noise: structural nodes are transparent, while semantic leaves retain
their callbacks, trace, history, and optimizer-addressable predictors.

- `Compose.lift` / `liftEither` embed local transformations.
- `>>>` composes dependent stages.
- `mapOutput`, `contramapInput`, and `dimap` adapt domain boundaries while preserving the inner raw prediction.
- `fanout` pairs two programs over one shared input, left-to-right.
- `split` pairs two programs over independent tuple inputs, left-to-right.
- `recoverWith(policy)(fallback)` makes error selection explicit and retains both branches for optimization.

Deep sequential chains do not use the JVM call stack. Execution uses an explicit
heap stack, and optimizer inspection and replacement use a stack-safe structural
interpreter. `DeepProgramStackSafetySuite` checks both paths with a chain of
20,000 programs.

### Contracts & introspection

| Type | Role |
|------|------|
| `Module[I, O]` | Semantic program trait: `forward` returns `Prediction[O]`; `final apply` owns the lifecycle. `DynamicModule` specializes both sides to records. |
| `ProgramCall[I]` | The uniform call envelope: input carrier `I`, config bag, `traceEnabled`, and `rolloutId`; `mapInput` preserves the controls. |
| `LegacyProgramRunner[P]` | Old type class that runs a `Module` from a record input. It remains only for migration. |
| `Prediction[O]` | Domain output `O` + its `RawPrediction` evidence (completions, usage). |
| `OptimizableStructure[P]` / `OptimizableLeaf[P]` | The introspection type-classes: a composite's learnable predictors (with dotted names like `"field.sub"`) and a single learnable leaf. Instances are hand-written for composites and structurally derived for case classes. |
| `ToolFunction` | The tool contract: `name`, `description`, `argSchema`, `invoke(args)`. `fromMethod` derives one from a method via a macro. |
| `ActionInterpreter[Action, Observation]` | Executes an agent action and distinguishes success, recoverable failure, and fatal `Left`. |
| `AgentLoop` | The bounded `Continue` / `Done` / exhaustion state-machine kernel shared by ReAct, CodeAct, RLM, and ProgramOfThought. |
| `TrajectoryAgent[I, O, S]` | Final loop-then-extract template used by ReAct and CodeAct; owns extraction count, failure short-circuiting, envelope preservation, and complete-trajectory attachment. |
| `InterpretedTrajectoryAgent[I, O, Entry]` | Explicit state machine for the final generate → prepare → interpret → record → decide transition shared by ReAct and CodeAct. |
| `Aggregation.majority` | Picks the most-common field value across candidate completions (ties to first). |
| `KNN` / `EmbeddingsRetriever` | Brute-force in-memory retrievers (no FAISS): nearest trainset examples by dot product, top-k passages by cosine. |

## Design notes

The notes in this section describe the legacy stack unless they name the functional `Program` API.

- **Module purity.** `forward` is side-effect-free; trace, history, and callbacks are a transparent `final`
  wrapper, so every program is observed identically with no subclass boilerplate. (The
  [module-purity memory](../../README.md): runtime owns bookkeeping, no `ProgramMeta`/`BaseModule`/`Parameter`.)
- **Two sibling layers with explicit erasure.** `Predict[I, O]` and `DynamicPredict` each own a `PredictEngine` built
  from their signature representation, so a `Predict` call emits one `predict` module lifecycle rather than a
  wrapper-over-dynamic pair. `Predict.erase` creates a one-way dynamic snapshot with the same engine state;
  programs that start with a runtime-known layout construct `DynamicPredict` directly.
- **`OptimizableStructure` is the optimizer backbone.** Optimizers never special-case program types — they read the
  predictor genome through `OptimizableStructure`, build edited copies, and `replace`. This is why one optimizer codepath
  covers a bare `Predict` and an arbitrary composite.
- **Config layering and bound LMs.** Module-level and per-call `config` merge with per-call winning;
  `rolloutId` is a first-class cache-busting field, not part of the provider bag. A predictor can bind its own
  `LanguageModel`, overriding the ambient context.
- **ReAct stays on the text protocol.** Tools are selected via output fields, not provider-native
  function-calling; tool failures become trajectory observations, and context-window overflow triggers
  trajectory truncation and retry. The native function-calling path is adapter-level and deliberately not
  wired into ReAct (see the [design memory](../../README.md)).
- **Agent templates own behavioral laws.** `TrajectoryAgent.forward` and
  `InterpretedTrajectoryAgent.trajectoryStep` are final, so subclasses choose the action ADT but cannot
  alter the orchestration. `InterpretedTrajectoryAgent.State` gives each phase exactly the data available there, while
  state-specific transition ADTs enumerate its legal successors; rejection and interpreted-outcome recording are
  separate states. `TrajectoryAgentLawSuite` pins extract-once, short-circuit, raw-envelope, and full-history behavior;
  `InterpretedTrajectoryAgentLawSuite` pins the `Halted`, `Rejected`, `Ready`, post-outcome `decide`, and fatal-error
  branches.

## Source layout

| Path | Contents |
|------|----------|
| `plan/Program.scala`, `plan/ProgramRunner.scala` | replacement typed syntax and stateless ZIO interpreter |
| `plan/ParameterStore.scala`, `plan/ProgramParameters.scala` | stable optimizer identity, state, and migration capability |
| `plan/ProgramGraph.scala`, `plan/RecordProgram.scala` | graph interpreter and explicit dataset record boundary |
| `plan/LivePredictionBackend.scala`, `plan/LiveCodeExecutionBackend.scala`, `plan/LiveToolBackend.scala`, `plan/ReplExecutionBackend.scala` | blocking-service bridges, including scoped persistent REPL ownership |
| `plan/CodeExecutionResult.scala` | strategy-neutral code execution result at the capability boundary |
| `plan/ChainOfThought.scala`, `plan/FeedbackRetry.scala`, `plan/MultiChainComparison.scala`, `plan/ProgramOfThought.scala`, `plan/ReAct.scala`, `plan/CodeAct.scala`, `plan/RLM.scala`, `plan/Ensemble.scala` | functional strategy constructors over the common syntax |
| `Predict.scala`, `DynamicPredict.scala` | sibling domain-value and runtime-record predictors over the shared engine |
| `ChainOfThought.scala`, `ReAct.scala`, `CodeAct.scala`, `RLM.scala`, `ProgramOfThought.scala`, `MultiChainComparison.scala` | the composite programs |
| `BestOfN.scala`, `Refine.scala`, `Parallel.scala`, `Aggregation.scala` | wrappers and utilities |
| `optimization/OptimizableLeaf.scala`, `OptimizableStructure.scala` | leaf lens and composite optimizer-structure typeclasses |
| `optimization/ParameterOptic.scala`, `StackSafeOptimizableStructure.scala` | carrier-based parameter composition and stack-safe structural traversal |
| `optimization/CompositeOptimizableStructureInstances.scala`, `OptimizableStructureDerivation.scala` | built-in composite instances and strict Mirror derivation |
| `contracts/Module.scala`, `ProgramCall.scala`, `ProgramRuntime.scala` | module boundary, call envelope, and runtime resolution contracts |
| `contracts/ToolFunction.scala`, `ToolCall.scala`, `ActionInterpreter.scala` | callable tools, action messages, and the action-execution boundary |
| `ProgramRunner.scala` | the old `LegacyProgramRunner[P]` domain/record capability |
| `RecordCodec.scala` | sealed canonical decoding evidence for the record-to-domain input boundary |
| `retrievers/KNN.scala`, `EmbeddingsRetriever.scala` | in-memory retrieval |
| `runtime/AgentLoop.scala`, `TrajectoryAgent.scala`, `InterpretedTrajectoryAgent.scala` | bounded iteration, loop-then-extract, and interpreted-action templates |
| `runtime/PredictEngine.scala`, `SettingsProgramRuntime.scala`, `ParallelExecutor.scala`, `ToolExecutor.scala` | the shared prediction body, model/adapter resolution, concurrency, tool dispatch |
| `internal/ToolMacro.scala` | the `ToolFunction.fromMethod` derivation macro |

## Relation to dspy

This ports `dspy.predict` and the module family. The shape decisions specific to dspy4s — pure modules with
runtime-owned bookkeeping, the domain/record split sharing one engine, and `OptimizableStructure` standing in for
`named_predictors()` — are what let the program API and the optimizers coexist over one substrate.
