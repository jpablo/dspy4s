# Phase 5 Progress

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

## Remaining for Phase 5

- Expand program parity around richer tool-call envelopes (multi-call sequencing and result stitching).
- Add tighter parity cases for mixed completion/tool-call outputs across wrappers (`ReAct`, `Parallel`, `BestOfN`).
- Add more DSPy-aligned trace/history assertions at the program boundary.
