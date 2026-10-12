package dspy4s.gepa

/** One high-signal example the reflection LM reads to improve a component's instruction — the analogue of gepa's
  * `{Inputs, Generated Outputs, Feedback}` record. `inputs`/`generatedOutputs` are the rendered I/O of one predictor
  * call of one trajectory; `feedback` is the predictor-level [[dspy4s.gepa.contracts.FeedbackMetric]] verdict. A
  * component's reflective dataset is a sequence of these. See PORT_GAPS G-12.
  *
  * The record of a call that failed exists only with `addFormatFailureAsFeedback`. Its `generatedOutputs` holds the
  * raw, unparseable model response, and its `feedback` holds the output structure to follow, not a metric verdict.
  */
final case class ReflectiveRecord(
    inputs          : String,
    generatedOutputs: String,
    feedback        : String
)
