# Phase 0 Contracts

> **Historical snapshot.** Captures what shipped in this phase. For the current
> API see [ARCHITECTURE.md](../ARCHITECTURE.md) and [TYPED_SIGNATURES_GUIDE.md](../TYPED_SIGNATURES_GUIDE.md);
> for the running per-feature ledger see [PORT_MAP.md](../port/PORT_MAP.md) and
> [PORT_BACKLOG.md](../port/PORT_BACKLOG.md). Symbols referenced below may have been
> renamed since (e.g. the original `Signature` is now `SignatureLayout` and the
> typed wrapper carries that name).


This file captures the interface-first contract layer introduced before concrete feature implementation.

## Added Contract Packages

- `dspy4s.core.contracts`
  - Error ADT (`DspyError` + concrete error types)
  - Signature contracts (`Signature`, `FieldSpec`, `TypeRef`, parser contract)
  - Data contracts (`Example`, `Prediction`, `Completions`)
  - Runtime contracts (`Settings`, `RuntimeContext`, setting keys)
  - Callback event model (`CallbackEvent`, `CallbackHandler`)
  - Module contracts (`Module`, `StatefulModule`, `ModuleGraph`)

- `dspy4s.lm.contracts`
  - LM protocol (`LanguageModel`)
  - Request/response model (`LmRequest`, `LmResponse`, `LmOutput`)
  - Cache + retry contracts (`LmCache`, `RetryPolicy`)

- `dspy4s.adapters.contracts`
  - Adapter protocol (`Adapter`)
  - Invocation/format/parse data model
  - Adapter fallback policy contract

- `dspy4s.programs.contracts`
  - Program call model (`ProgramCall`)
  - Program runtime resolution contract (`ProgramRuntime`)
  - Predict + tool contracts

- `dspy4s.evaluate.contracts`
  - Metric and evaluator contracts
  - Evaluation result model

- `dspy4s.optimize.contracts`
  - Teleprompter contract
  - Candidate/report models

- `dspy4s.streaming.contracts`
  - Stream events
  - Stream listener/streamifier contracts

## Why This Matters

These contracts freeze the package boundaries and runtime protocol before implementation details, so subsequent phases can be built with lower refactor risk and clearer test targets.

## Next Step

Implement Phase 1 (`core`) against these contracts:
- signature DSL parser implementation
- concrete signature operations parity
- richer `Example`/`Prediction` behavior and state handling
