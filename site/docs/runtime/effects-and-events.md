# Effects, events, and streaming

The program environment states all required effects:

- `PredictionBackend`
- `CodeExecutionBackend`
- `ToolBackend`
- `ReplExecutionBackend`

`ProgramRunner.runJournaled` returns ordered `ProgramEvent` values. Prediction events include their `ParameterId`.
`ProgramEventStream.run` exposes these events and the final typed result through `ZStream`.

`LivePredictionBackend` is the bridge to the current blocking LM and adapter APIs. This bridge receives its low-level
runtime context explicitly.
