# Phase 2 Progress

Phase 2 focuses on settings, callbacks, and context propagation semantics.

## Implemented in this step

1. Runtime settings model with ownership guard
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/runtime/RuntimeEnvironment.scala`
- Added global settings store and effective-settings resolution
- Added configure APIs (`configure`, `configureEntries`, keyed `configure`)
- Added owner-thread restriction for global configure operations
- Added scoped setting helpers (`withSetting`, merged `withSettings`)

2. Callback dispatch and wrappers
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/runtime/CallbackDispatcher.scala`
- Added start/end wrappers for module, LM, and adapter execution paths
- Added consistent end-event emission on success and thrown exceptions

3. Context propagation helpers
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/runtime/ContextPropagation.scala`
- Added captured runtime context wrappers for execution contexts and futures

4. Test coverage
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/test/scala/dspy4s/core/RuntimeEnvironmentSuite.scala`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/test/scala/dspy4s/core/CallbackDispatcherSuite.scala`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/test/scala/dspy4s/core/ContextPropagationSuite.scala`
- Added assertions for configure ownership, callback ordering, exception-path event emission, and future context propagation

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

7. Programs tests
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/ProgramRuntimeSuite.scala`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/ParallelExecutorSuite.scala`
- Added `munit` dependency for `programs` tests in `build.sbt`
- Updated `ProgramsApi.contractsPhase` to `phase-2`

## Remaining for Phase 2

- Add explicit async-task ownership semantics (Python parity currently only enforces thread ownership).
- Wire callback dispatcher into upcoming concrete program modules (`Predict`, `ChainOfThought`, etc.).
- Tune parallel cancellation semantics to short-circuit in-flight work once `maxErrors` is reached.
