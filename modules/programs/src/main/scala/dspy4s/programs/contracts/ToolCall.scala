package dspy4s.programs.contracts

import dspy4s.core.contracts.DspyError
import zio.blocks.schema.DynamicValue

final case class ToolCallRequest(name: String, args: DynamicValue.Record)
final case class ToolCallResult(name: String, result: Either[DspyError, DynamicValue])
