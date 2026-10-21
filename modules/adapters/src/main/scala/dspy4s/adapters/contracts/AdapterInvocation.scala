package dspy4s.adapters.contracts

import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.data.Example
import dspy4s.lm.contracts.LmRequest

/** Everything an adapter needs to format one language-model request.
  *
  * `outputJsonSchema` is populated by the `Predict[I, O]` path, which can render the static output schema, and is left
  * empty by `DynamicPredict`. The Chat, JSON, and XML adapters take the schema of each structured output field from it
  * for the note of that field in the prompt. The JSON adapter also uses it for the strict `response_format`. No prompt
  * shows the whole schema, as upstream.
  */
final case class AdapterInvocation(
    layout          : SignatureLayout,
    demos           : Vector[Example],
    inputs          : Example,
    request         : LmRequest,
    outputJsonSchema: Option[String] = None,
    /** Tool definitions (pure [[ToolSpec]] data — name / description / parameter schema, no invoke closures) the caller
      * makes available to the model. An adapter with native function-calling enabled renders these into the provider
      * `tools` request option; adapters without it ignore them. Empty by default. The executable tool bodies stay on
      * the program (e.g. ReAct's `ToolFunction`s); only the schema travels to the adapter.
      */
    tools: Vector[ToolSpec] = Vector.empty
)
