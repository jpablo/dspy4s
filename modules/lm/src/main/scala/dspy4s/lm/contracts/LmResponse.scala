package dspy4s.lm.contracts

import dspy4s.core.contracts.ToolCall
import zio.blocks.schema.DynamicValue

/** Compatibility alias: usage is defined in core because both LM responses and predictions carry the same execution
  * metadata.
  */
type LmUsage = dspy4s.core.contracts.LmUsage
val LmUsage: dspy4s.core.contracts.LmUsage.type = dspy4s.core.contracts.LmUsage

final case class LmOutput(
    text     : String,
    toolCalls: Vector[ToolCall]    = Vector.empty,
    metadata : DynamicValue.Record = DynamicValue.Record.empty
)

final case class LmResponse(
    outputs  : Vector[LmOutput],
    usage    : Option[LmUsage] = None,
    modelName: Option[String]  = None,
    cacheHit : Boolean         = false
)
