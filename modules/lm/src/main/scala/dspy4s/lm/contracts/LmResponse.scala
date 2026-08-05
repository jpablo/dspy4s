package dspy4s.lm.contracts

import dspy4s.core.contracts.{LmUsage, ToolCall}
import zio.blocks.schema.DynamicValue

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
