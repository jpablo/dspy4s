# Phase 2 Progress

> **Historical snapshot.** Captures what shipped in this phase. For the current
> API see [ARCHITECTURE.md](../ARCHITECTURE.md) and [TYPED_SIGNATURES_GUIDE.md](../TYPED_SIGNATURES_GUIDE.md);
> for the running per-feature ledger see [PORT_MAP.md](../PORT_MAP.md) and
> [PORT_BACKLOG.md](../PORT_BACKLOG.md). Symbols referenced below may have been
> renamed since (e.g. the original `Signature` is now `SignatureLayout` and the
> typed wrapper carries that name).


Phase 2 focuses on settings, callbacks, and context propagation semantics.

## Implemented in this step

1. Runtime settings model with ownership guard
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/runtime/RuntimeEnvironment.scala`
- Added global settings store and effective-settings resolution
- Added configure APIs (`configure`, `configureEntries`, keyed `configure`)
- Added owner-thread restriction for global configure operations
- Added async-task ownership restriction for configure operations (`async_task_id`)
- Added scoped setting helpers (`withSetting`, merged `withSettings`)
- Added async-task helpers (`withAsyncTask`, `withGeneratedAsyncTask`)

2. Callback dispatch and wrappers
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/runtime/CallbackDispatcher.scala`
- Added start/end wrappers for module, LM, and adapter execution paths
- Added consistent end-event emission on success and thrown exceptions
- Added tool callback wrappers (`withTool`) and tool start/end events
- Added callback call IDs with parent call tracking for nested and async-propagated execution

3. Context propagation helpers
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/runtime/ContextPropagation.scala`
- Added captured runtime context wrappers for execution contexts and futures
- Added generated async task IDs per propagated future execution

4. Test coverage
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/test/scala/dspy4s/core/RuntimeEnvironmentSuite.scala`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/test/scala/dspy4s/core/CallbackDispatcherSuite.scala`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/test/scala/dspy4s/core/ContextPropagationSuite.scala`
- Added assertions for configure ownership, callback ordering, exception-path event emission, and future context propagation
- Added callback call-id lineage tests (nested module calls, async propagation, and tool callbacks)

5. Programs runtime integration
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/main/scala/dspy4s/programs/runtime/BasePredictProgram.scala`
- Added settings-backed resolver (`SettingsProgramRuntime`) for LM and adapter lookup
- Added module callback emission + trace/history append wiring inside `BasePredictProgram`
- Added async execution path that preserves runtime context via `ContextPropagation`

6. Programs parallel executor foundation
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/main/scala/dspy4s/programs/runtime/ParallelExecutor.scala`
- Added captured-context parallel execution with configurable `numThreads`, `maxErrors`, and timeout
- Added failure indexing and error map reporting for partial-success runs
- Added `ParallelExecutor.fromSettings` to bind defaults from runtime `num_threads` and `max_errors`
- Switched to bounded scheduling with early cancellation when `maxErrors` is reached

7. Programs tests
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/ProgramRuntimeSuite.scala`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/ParallelExecutorSuite.scala`
- Added `munit` dependency for `programs` tests in `build.sbt`
- Updated `ProgramsApi.contractsPhase` to `phase-2`
- Added coverage for async configure ownership and cancellation stop behavior

8. First concrete program module
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/main/scala/dspy4s/programs/Predict.scala`
- Implemented settings-backed `Predict` pipeline (`adapter.format -> lm.call -> adapter.parse`)
- Wired callback dispatcher for adapter format/parse and LM call stages
- Added prediction completion/usage construction from parsed LM outputs
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/PredictSuite.scala`

9. Additional program wrappers
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/main/scala/dspy4s/programs/ChainOfThought.scala`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/main/scala/dspy4s/programs/Parallel.scala`
- Added `ParallelExecutor.executeEither` for structured module-level failure tracking
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/ChainOfThoughtSuite.scala`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/ParallelSuite.scala`

10. Selection wrappers (`BestOfN` / `Refine`)
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/main/scala/dspy4s/programs/BestOfN.scala`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/main/scala/dspy4s/programs/Refine.scala`
- Added rollout-aware repeated execution (`rollout_id`, `temperature`) with reward-based best-candidate selection
- Added fail-count behavior compatible with DSPy tests (default and custom fail thresholds)
- Added best-attempt trace/history propagation back to outer runtime context
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/BestOfNSuite.scala`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/RefineSuite.scala`

11. ReAct and tool execution parity foundations
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/main/scala/dspy4s/programs/runtime/ToolExecutor.scala`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/main/scala/dspy4s/programs/ReAct.scala`
- Added iterative tool-orchestration loop with bounded iterations and explicit exhaustion error semantics
- Added tool lookup/invocation path with callback-dispatched tool start/end events
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/ReActSuite.scala`

12. Explicit call-stack inspection APIs
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/contracts/Runtime.scala` with `SettingKeys.callStack`
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/runtime/RuntimeEnvironment.scala` with:
  - `activeCallStack`
  - `activeCallDepth`
  - stack-aware `withActiveCall` push/pop behavior
- Added call-stack restoration coverage in `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/test/scala/dspy4s/core/RuntimeEnvironmentSuite.scala`
- Added async call-stack propagation coverage in `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/test/scala/dspy4s/core/CallbackDispatcherSuite.scala`

## Remaining for Phase 2

- No open blockers for the Phase 2 target subset.
- Next focus should move to Phase 3 (`lm` cache/retry/history/usage parity).
