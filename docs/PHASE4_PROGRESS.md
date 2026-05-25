# Phase 4 Progress

> **Historical snapshot.** Captures what shipped in this phase. For the current
> API see [ARCHITECTURE.md](ARCHITECTURE.md) and [TYPED_SIGNATURES_GUIDE.md](TYPED_SIGNATURES_GUIDE.md);
> for the running per-feature ledger see [PORT_MAP.md](PORT_MAP.md) and
> [PORT_BACKLOG.md](PORT_BACKLOG.md). Symbols referenced below may have been
> renamed since (e.g. the original `Signature` is now `SignatureLayout` and the
> typed wrapper carries that name).


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

4. JSON adapter baseline
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/adapters/src/main/scala/dspy4s/adapters/JSONAdapter.scala`
- Implemented JSON-oriented formatting with explicit output-key contract
- Implemented parse path with:
  - raw/fenced JSON extraction
  - object validation
  - typed field coercion
  - malformed JSON error diagnostics
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/adapters/src/test/scala/dspy4s/adapters/JSONAdapterSuite.scala`
  - format instruction coverage
  - plain JSON parse coverage
  - fenced JSON parse coverage
  - malformed output failure coverage
- Added adapters runtime JSON dependency in `/Users/jpablo/proyectos/experimentos/dspy4s/build.sbt`:
  - `"com.lihaoyi" %% "ujson" % "4.0.2"`

5. XML adapter baseline
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/adapters/src/main/scala/dspy4s/adapters/XMLAdapter.scala`
- Implemented XML-oriented formatting with explicit `<outputs>` schema instructions
- Implemented parse path with:
  - raw/fenced XML extraction
  - XML document parsing and tag extraction
  - typed field coercion
  - malformed XML diagnostics
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/adapters/src/test/scala/dspy4s/adapters/XMLAdapterSuite.scala`
  - format instruction coverage
  - typed parse coverage
  - fenced XML parse coverage
  - malformed output failure coverage
- Added adapters runtime XML dependency in `/Users/jpablo/proyectos/experimentos/dspy4s/build.sbt`:
  - `"org.scala-lang.modules" %% "scala-xml" % "2.3.0"`

6. Tool schema + tool-call bridge
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/adapters/src/main/scala/dspy4s/adapters/contracts/ToolContracts.scala`
- Added tool schema contracts:
  - `ToolParameterSpec`
  - `ToolSpec`
  - `ToolCallData`
- Added bridge utilities:
  - `ToolSchemaBridge.toOpenAiTools(...)`
  - `ToolSchemaBridge.fromOutput(...)`
- Added `/Users/jpablo/proyectos/experimentos/dspy4s/modules/adapters/src/test/scala/dspy4s/adapters/ToolSchemaBridgeSuite.scala`
  - OpenAI function schema rendering coverage
  - LM tool-call extraction coverage

7. Malformed-output fallback/repair behavior
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/adapters/src/main/scala/dspy4s/adapters/JSONAdapter.scala`
  - single-output text fallback when JSON parsing fails (`allowTextFallbackForSingleOutput`)
- Extended `/Users/jpablo/proyectos/experimentos/dspy4s/modules/adapters/src/main/scala/dspy4s/adapters/XMLAdapter.scala`
  - single-output text fallback when XML parsing fails (`allowTextFallbackForSingleOutput`)
- Extended adapter suites with fallback coverage:
  - `/Users/jpablo/proyectos/experimentos/dspy4s/modules/adapters/src/test/scala/dspy4s/adapters/JSONAdapterSuite.scala`
  - `/Users/jpablo/proyectos/experimentos/dspy4s/modules/adapters/src/test/scala/dspy4s/adapters/XMLAdapterSuite.scala`

## Remaining for Phase 4

- No open blockers for the Phase 4 target subset.
- Next focus should move to Phase 5 program-surface expansion and tighter parity across predict/react parallel behaviors.
