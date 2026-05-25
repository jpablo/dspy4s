# Phase 5 Progress

> **Historical snapshot.** Captures what shipped in this phase. For the current
> API see [ARCHITECTURE.md](../ARCHITECTURE.md) and [TYPED_SIGNATURES_GUIDE.md](../TYPED_SIGNATURES_GUIDE.md);
> for the running per-feature ledger see [PORT_MAP.md](../PORT_MAP.md) and
> [PORT_BACKLOG.md](../PORT_BACKLOG.md). Symbols referenced below may have been
> renamed since (e.g. the original `Signature` is now `SignatureLayout` and the
> typed wrapper carries that name).


Phase 5 focuses on program-level parity and end-to-end runtime behavior.

## Implemented in this step

1. Native tool-call propagation into `Predict`
- Updated `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/main/scala/dspy4s/programs/Predict.scala`
- `Predict` now surfaces LM-native tool calls in prediction values under:
  - `tool_calls: Vector[Map[String, Any]]`
- Tool call payload is derived from the first LM output choice, preserving:
  - `name`
  - `args`

2. ReAct native tool-call execution path
- Updated `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/main/scala/dspy4s/programs/ReAct.scala`
- `ReAct` tool request extraction now supports:
  - native `tool_calls` payloads (from LM/provider path)
  - legacy `tool_name` / `tool_args` fields (backward compatible)
- Added robust parsing for typed and map-based tool-call entries.

3. Program test coverage additions
- Updated `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/PredictSuite.scala`
  - added coverage for LM tool-call propagation to prediction values
- Updated `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/ReActSuite.scala`
  - added coverage for native `tool_calls` execution flow in `ReAct`

4. ReAct multi-tool-call sequencing and result stitching
- Updated `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/main/scala/dspy4s/programs/ReAct.scala`
- `ReAct` now executes multiple native `tool_calls` entries in a single iteration, sequentially.
- Added batched result stitching fields:
  - `tool_results`: current iteration batch as `Vector[Map[String, Any]]` with `tool_name`, `tool_args`, `result`, `index`
  - `tool_history`: cumulative history including all batch entries in order
  - `tool_result`: last tool result (backward compatibility)
- Existing behavior preserved:
  - direct answer short-circuits tool execution
  - legacy `tool_name` / `tool_args` path still supported

5. Mixed completion/tool-call parity tests across wrappers
- Updated `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/ReActSuite.scala`
  - added coverage for multi-call native `tool_calls` execution in one iteration
  - added coverage that direct `answer` is prioritized over tool execution when both are present
- Updated `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/ParallelSuite.scala`
  - added coverage that `Parallel` preserves mixed `answer` + `tool_calls` payloads
- Updated `/Users/jpablo/proyectos/experimentos/dspy4s/modules/programs/src/test/scala/dspy4s/programs/BestOfNSuite.scala`
  - added coverage that `BestOfN` preserves mixed `answer` + `tool_calls` for the selected best candidate

## Validation

- Ran full test suite with `sbt test` on February 7, 2026.
- Result: all tests passed.

## Remaining for Phase 5

- Add more DSPy-aligned trace/history assertions at the program boundary.
