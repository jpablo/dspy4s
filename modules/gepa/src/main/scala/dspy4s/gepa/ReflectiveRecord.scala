package dspy4s.gepa

/** One high-signal example the reflection LM reads to improve a component's instruction — the analogue of gepa's
  * `{Inputs, Generated Outputs, Feedback}` record. `inputs`/`generatedOutputs` are the predictor's rendered I/O for
  * one trajectory (on a parse failure, `generatedOutputs` is the raw, unparseable model response); `feedback` is
  * the predictor-level [[dspy4s.gepa.contracts.FeedbackMetric]] verdict. A component's reflective dataset is a
  * sequence of these. See PORT_GAPS G-12. */
final case class ReflectiveRecord(
    inputs: String,
    generatedOutputs: String,
    feedback: String
)
