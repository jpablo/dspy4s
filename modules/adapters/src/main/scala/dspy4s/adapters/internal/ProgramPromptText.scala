package dspy4s.adapters.internal

import dspy4s.core.contracts.FieldSpec
import zio.blocks.schema.DynamicValue

/** The prompt texts of the adapters that the programs use (decision D3). `ChainOfThought`, `ReAct`, `ReActV2`, and
  * `RLM` write a part of their prompts with the rules of the adapters, so these texts are equal to the texts of the
  * adapters. This object makes only these rules visible outside the adapters module.
  */
private[dspy4s] object ProgramPromptText:

  /** The text of the value of `field` (upstream `format_field_value` in `dspy/adapters/utils.py`). */
  def fieldValue(field: FieldSpec, value: DynamicValue): String =
    PromptText.fieldValue(field, value)

  /** The placeholder of the output field `field`, with its note (upstream `translate_field_type`). The note of a
    * structured field shows its schema in `outputJsonSchema`, the output JSON schema of the signature.
    */
  def outputPlaceholder(field: FieldSpec, outputJsonSchema: Option[DynamicValue.Record]): String =
    PromptText.placeholder(field, output = true, PromptText.OutputSchema(outputJsonSchema))

  /** The schema of the output `name` in `outputJsonSchema`, the output JSON schema of the signature: the property of
    * the output, with the `$defs` entries that it uses at the end. `None` when the schema has no such property.
    */
  def outputProperty(name: String, outputJsonSchema: Option[DynamicValue.Record]): Option[DynamicValue] =
    PromptText.OutputSchema(outputJsonSchema).propertySchema(name)
