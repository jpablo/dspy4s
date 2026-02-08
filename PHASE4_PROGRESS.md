# Phase 4 Progress

Phase 4 focuses on adapter formatting/parsing parity.

## Implemented in this step

1. First concrete adapter (`ChatAdapter`)
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/adapters/src/main/scala/dspy4s/adapters/ChatAdapter.scala`
- Implemented prompt formatting with:
  - system instructions
  - demo turns (user/assistant)
  - final user input turn
- Implemented output parsing with:
  - label-based extraction from generated text
  - single-output fallback behavior
  - scalar type coercion (`int`, `double`, `bool`)
  - structured parse errors for missing output fields

2. Adapter test harness
- Added `munit` test dependency for adapters in `/Users/jpablo/proyectos/experimentos/dspy4s/build.sbt`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/adapters/src/test/scala/dspy4s/adapters/ChatAdapterSuite.scala`
- Added coverage for:
  - format message sequencing and content
  - typed parse extraction
  - single-output fallback parsing
  - missing-field parse failure

3. Module phase metadata
- Updated `/Users/jpablo/proyectos/experimentos/dspy4s/modules/adapters/src/main/scala/dspy4s/adapters/AdaptersApi.scala`
  - `contractsPhase = "phase-4"`

## Remaining for Phase 4

- Add `JSONAdapter` and `XMLAdapter` implementations with robust parse diagnostics.
- Add tool schema/tool-call adapter bridge (`Tool`, `ToolCalls`) for native function-calling parity.
- Add fallback/repair behavior for malformed model outputs.
