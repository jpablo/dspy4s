# dspy4s streaming

`ProgramEventStream.run` exposes one functional program run as `ZStream[R, DspyError, ProgramStreamItem[O]]`.

The stream contains:

- `ProgramStreamItem.Event`, with `Started`, `OutputChunk`, `Completed`, or `Failed`.
- `ProgramStreamItem.Result`, with the final typed `Prediction[O]`.

Prediction chunks use the same call ID, parent ID, component name, and `ParameterId` as other interpreter events.
Streaming uses `PredictionBackend.generateStreaming`; non-streaming backends use the default complete-result method.
There is no ambient queue, callback registry, or wrapper language model.
