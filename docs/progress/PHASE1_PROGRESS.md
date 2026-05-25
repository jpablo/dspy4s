# Phase 1 Progress

> **Historical snapshot.** Captures what shipped in this phase. For the current
> API see [ARCHITECTURE.md](../ARCHITECTURE.md) and [TYPED_SIGNATURES_GUIDE.md](../TYPED_SIGNATURES_GUIDE.md);
> for the running per-feature ledger see [PORT_MAP.md](../PORT_MAP.md) and
> [PORT_BACKLOG.md](../PORT_BACKLOG.md). Symbols referenced below may have been
> renamed since (e.g. the original `Signature` is now `SignatureLayout` and the
> typed wrapper carries that name).


Phase 1 focuses on signatures and core primitives.

## Implemented in this step

1. Signature model enhancements
- Added type token mapping (`TypeRef.fromToken`)
- Added field name validation and prefix inference (`FieldSpec.validateName`, `FieldSpec.inferPrefix`)
- Added field normalization defaults (`prefix`, `description`)
- Added signature insert API (`Signature.insert`)
- Added signature field update API (`Signature.withUpdatedField`)
- Added Python-parity update aliases (`Signature.withUpdatedFields`) including typed token updates and multi-field patching
- Added structural equality helper (`Signature.equalsByStructure`)
- Added signature state serialization and restore (`dumpState`, `SignatureSpec.fromState`)
- Added signature string rendering (`Signature.signatureString`)
- Added validated signature constructor (`SignatureSpec.create`)

2. Signature DSL parser
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/signatures/DefaultSignatureParser.scala`
- Supports `input1, input2 -> output1, output2` and typed fields (`x: int`)
- Validates arrow count and field identifiers

3. Signature convenience API
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/signatures/SignatureDsl.scala`
- Includes parser access, builder helper, and default instructions helper

4. Primitive behavior enhancements
- `Example` now supports key mutation helpers (`withValue`, `without`)
- `Completions.at` now returns `Either[DspyError, PredictionData]` with bounds validation
- `CompletionData` validates equal-length completion vectors
- Added completion row/column helpers (`field`, `items`, `first`, `last`, `toPredictions`)
- Added `CompletionData.fromRows` and `CompletionData.single`
- `PredictionData` adds `score` extraction helper
- Added generic prediction accessors (`value`, `asDouble`, `withValue`)
- `PredictionData.fromCompletions` returns validated `Either`
- Added `PredictionData.fromRows`

5. Module graph traversal utilities
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/primitives/BaseModule.scala`
- Added recursive graph walker for named parameters/submodules
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/primitives/BasicParameter.scala`

6. Runtime context foundation (Phase 2 bootstrap)
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/core/src/main/scala/dspy4s/core/runtime/RuntimeEnvironment.scala`
- Added scoped context/settings management and callback dispatch entry points

## Remaining for Phase 1

- No open Phase 1 blockers for the targeted subset.
- Next focus: Phase 2 parity (`settings`, callbacks, and parallel context propagation tests).
