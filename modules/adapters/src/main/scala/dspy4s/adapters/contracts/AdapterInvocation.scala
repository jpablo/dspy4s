package dspy4s.adapters.contracts

import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.data.Example
import dspy4s.lm.contracts.LmRequest

/** Everything an adapter needs to format one language-model request.
  *
  * `outputJsonSchema` is populated by the typed `Predict[I, O]` path, which can render the static output schema, and is
  * left empty by `DynamicPredict`. Adapters that understand structured-output hints use it in their instructions or
  * provider options; other adapters ignore it.
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
