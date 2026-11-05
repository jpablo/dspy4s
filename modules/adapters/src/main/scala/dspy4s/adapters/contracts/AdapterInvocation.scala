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
    /** The tools that the caller makes available to the model: pure [[ToolSpec]] data (name, description, and parameter
      * schema, with no invoke closures). They apply only to a layout with a tool calls field. When native function
      * calling is active ([[NativeTools.isActive]]), the request has them as provider tools
      * ([[NativeTools.toolOptions]]). Else the prompt shows them as an input field ([[NativeTools.promptInvocation]]).
      * Empty by default. The executable tool bodies stay on the program; only the schema goes to the adapter.
      */
    tools: Vector[ToolSpec] = Vector.empty,
    /** The layout of the signature of the caller. [[dspy4s.adapters.AdapterCall]] sets it, because each step of a call
      * can change [[layout]]: the native reasoning step removes the reasoning fields, and a decision output becomes an
      * evidence field. See [[fullLayout]].
      */
    signatureLayout: Option[SignatureLayout] = None
):

  /** The layout of the signature of the caller, else [[layout]]. A wrapper of an adapter that acts on a signature reads
    * this layout, as an upstream wrapper runs before `Adapter._call_preprocess`.
    */
  def fullLayout: SignatureLayout = signatureLayout.getOrElse(layout)
