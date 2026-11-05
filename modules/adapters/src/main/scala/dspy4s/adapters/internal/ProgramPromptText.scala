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

  /** The prompt texts of the output fields for `outputJsonSchema`, the output JSON schema of the signature. Build this
    * object one time for a prompt and use it for each output field: it computes the `$defs` entries of the schema one
    * time.
    */
  def schema(outputJsonSchema: Option[DynamicValue.Record]): OutputSchemaText =
    OutputSchemaText(PromptText.OutputSchema(outputJsonSchema))

  /** The prompt texts of the output fields for one output JSON schema. Use [[ProgramPromptText.schema]] to build it. */
  final class OutputSchemaText private[adapters] (schema: PromptText.OutputSchema):

    /** The placeholder of the output field `field`, with its note (upstream `translate_field_type`). The note of a
      * structured field shows its schema in the output JSON schema.
      */
    def placeholder(field: FieldSpec): String =
      PromptText.placeholder(field, output = true, schema)

    /** The schema of the output `name` in the output JSON schema: the property of the output, with the `$defs` entries
      * that it uses at the end. `None` when the schema has no such property.
      */
    def property(name: String): Option[DynamicValue] =
      schema.propertySchema(name)
