package dspy4s.adapters.contracts

import dspy4s.core.contracts.{:=, DynamicValues}
import zio.blocks.schema.DynamicValue

/** A tool that a model with native function calling can call.
  *
  * @param inputSchema
  *   the JSON schema of the arguments: an object schema with `type`, `properties`, and `required`. For example the
  *   object schema of an MCP tool (upgrade item I10). [[ToolSchemaBridge]] sends it as the `parameters` of the
  *   function.
  */
final case class ToolSpec(
    name       : String,
    description: Option[String] = None,
    inputSchema: DynamicValue.Record
)

object ToolSchemaBridge:

  /** Build the OpenAI `tools` array as a [[DynamicValue]] so it can be injected into a request's option bag
    * ([[dspy4s.adapters.contracts.FormattedPrompt.requestOptions]]) and spread verbatim into the provider body by the
    * wire of the provider (for example `ChatWire` in `lm`). Each tool becomes
    * `{type:"function", function:{name, description, parameters}}` where `parameters` is the [[ToolSpec.inputSchema]]
    * of the tool.
    */
  def toOpenAiToolsDynamic(tools: Vector[ToolSpec]): DynamicValue =
    DynamicValues.sequence(tools.map(toolToDynamic))

  private def toolToDynamic(tool: ToolSpec): DynamicValue =
    val function = DynamicValues.record(
      "name"        := tool.name,
      "description" := tool.description.getOrElse(""),
      "parameters"  -> tool.inputSchema
    )
    DynamicValues.record("type" := "function", "function" -> function)
