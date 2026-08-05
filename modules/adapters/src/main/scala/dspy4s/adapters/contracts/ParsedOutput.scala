package dspy4s.adapters.contracts

import zio.blocks.schema.DynamicValue

/** Adapter parse result. `values` is the structured record of output field values produced from the LM completion;
  * `metadata` is a free-form bag of debug or adapter-specific annotations.
  */
final case class ParsedOutput(
    values: DynamicValue.Record,
    rawText: Option[String] = None,
    metadata: Map[String, Any] = Map.empty
)
