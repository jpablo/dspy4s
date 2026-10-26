package dspy4s.adapters.contracts

import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.data.Example
import dspy4s.lm.contracts.LmRequest
import zio.blocks.schema.DynamicValue

/** Everything an adapter needs to format one language-model request.
  *
  * `outputJsonSchema` is the JSON schema of the outputs as a JSON object. The `Predict[I, O]` path takes it from the
  * static output shape (`Shape.jsonSchema`), and `DynamicPredict` leaves it empty. The Chat, JSON, and XML adapters
  * take the schema of each structured output field from it for the note of that field in the prompt. The JSON adapter
  * also uses it for the strict `response_format`. No prompt shows the whole schema, as upstream.
  */
final case class AdapterInvocation(
    layout          : SignatureLayout,
    demos           : Vector[Example],
    inputs          : Example,
    request         : LmRequest,
    outputJsonSchema: Option[DynamicValue.Record] = None,
    /** Tool definitions (pure [[ToolSpec]] data — name / description / parameter schema, no invoke closures) the caller
      * makes available to the model. An adapter with native function-calling enabled renders these into the provider
      * `tools` request option; adapters without it ignore them. Empty by default. The executable tool bodies stay on
      * the program (e.g. ReAct's `ToolFunction`s); only the schema travels to the adapter.
      */
    tools: Vector[ToolSpec] = Vector.empty
)
