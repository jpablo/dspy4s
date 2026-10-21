package dspy4s.adapters.contracts

/** The texts of an incomplete demo (dspy's `Adapter.format_demos`).
  *
  * A demo is complete when it has a value that is not null for each field of the signature. A demo that is not complete
  * and has the key of one input field and one output field at least is an incomplete demo. A key with a null value
  * counts, as upstream. Each other demo is dropped. The adapters put the incomplete demos first, then the complete
  * demos, each group in the order of the demos.
  *
  * The user turn of an incomplete demo starts with [[IncompletePrefix]] and has each input value of the demo. The
  * assistant turn of the Chat, JSON, and XML adapters has each output field: a field that the demo does not have shows
  * [[NotSupplied]]. A null value shows as a null (`None` in text, `null` in JSON), as upstream. The assistant turn of
  * the TwoStep adapter shows the output fields that the demo has only (upstream
  * `TwoStepAdapter.format_assistant_message_content`). A field default
  * ([[dspy4s.core.contracts.FieldSpec.defaultValue]]) does not fill a demo.
  */
object AdapterDemos:

  /** The first text of the user turn of an incomplete demo. */
  val IncompletePrefix: String = "This is an example of the task, though some input or output fields are not supplied."

  /** The value of an output field that an incomplete demo does not have. The space at the end is in the upstream text.
    */
  val NotSupplied: String = "Not supplied for this particular example. "
