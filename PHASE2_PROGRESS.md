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

## Remaining for Phase 2

- Add explicit async-task ownership semantics (Python parity currently only enforces thread ownership).
- Wire callback dispatcher into real module/program execution paths as they are implemented.
- Add parallel executor primitive in `programs` using `ContextPropagation` and phase-specific settings keys.
