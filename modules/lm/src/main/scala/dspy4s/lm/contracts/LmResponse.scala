package dspy4s.lm.contracts

import dspy4s.core.contracts.{LmUsage, ToolCall}
import zio.blocks.schema.DynamicValue

/** One output of a reply.
  *
  * @param reasoning
  *   the reasoning text of the output (upstream `reasoning_content`), else an empty text. The Responses wire and the
  *   assembly of a stream fill it.
  * @param citations
  *   the citations of the output, in order (upstream `citations`). The Responses wire and the assembly of a stream fill
  *   it. The Responses wire gives each citation the form `{"type": "citation", "text", "title", "url", "metadata"}`,
  *   with no key for a null value.
  * @param metadata
  *   the other keys of the reply for this output: for a chat reply, the keys of its choice other than `message` and
  *   `text` (for example `finish_reason` and `index`).
  * @param answers
  *   experimental. The typed answers of the reply to a decision request, by question name, else `None`
  *   ([[DecisionRequests.response]]). The text of such an output is the JSON text of the answers.
  */
final case class LmOutput(
    text     : String,
    toolCalls: Vector[ToolCall]            = Vector.empty,
    reasoning: String                      = "",
    citations: Vector[DynamicValue.Record] = Vector.empty,
    metadata : DynamicValue.Record         = DynamicValue.Record.empty,
    answers  : Option[DynamicValue.Record] = None
)

final case class LmResponse(
    outputs  : Vector[LmOutput],
    usage    : Option[LmUsage] = None,
    modelName: Option[String]  = None,
    cacheHit : Boolean         = false
)
