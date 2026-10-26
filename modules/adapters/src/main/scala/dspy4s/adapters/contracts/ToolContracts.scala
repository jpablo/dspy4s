package dspy4s.adapters.contracts

import dspy4s.core.contracts.{:=, DynamicValues, TypeRef}
import zio.blocks.schema.DynamicValue

final case class ToolParameterSpec(
    name       : String,
    typeRef    : TypeRef        = TypeRef.string,
    description: Option[String] = None,
    required   : Boolean        = true
)

/** A tool that a model with native function calling can call.
  *
  * @param parameters
  *   the flat view of the arguments
  * @param inputSchema
  *   the JSON schema of the arguments, when the tool has one (for example the `inputSchema` of an MCP tool, upgrade
  *   item I10). When it is set, [[ToolSchemaBridge]] sends it as the `parameters` of the function in place of the
  *   schema that it builds from [[parameters]].
  */
final case class ToolSpec(
    name       : String,
    description: Option[String]              = None,
    parameters : Vector[ToolParameterSpec]   = Vector.empty,
    inputSchema: Option[DynamicValue.Record] = None
)

object ToolSchemaBridge:

  /** Build the OpenAI `tools` array as a [[DynamicValue]] so it can be injected into a request's option bag
    * ([[dspy4s.adapters.contracts.FormattedPrompt.requestOptions]]) and spread verbatim into the provider body by the
    * wire of the provider (for example `ChatWire` in `lm`). Each tool becomes
    * `{type:"function", function:{name, description, parameters}}` where `parameters` is the [[ToolSpec.inputSchema]]
    * of the tool when it has one, else a JSON-schema object (`type:"object"`, `properties`, `required`) built from its
    * [[ToolSpec.parameters]]. The type of a parameter is the JSON type of its [[TypeRef]] ([[TypeRef.jsonType]]), and
    * `string` for a type with no JSON type.
    */
  def toOpenAiToolsDynamic(tools: Vector[ToolSpec]): DynamicValue =
    DynamicValues.sequence(tools.map(toolToDynamic))

  private def toolToDynamic(tool: ToolSpec): DynamicValue =
    val function = DynamicValues.record(
      "name"        := tool.name,
      "description" := tool.description.getOrElse(""),
      "parameters"  -> tool.inputSchema.getOrElse(parametersToDynamic(tool.parameters))
    )
    DynamicValues.record("type" := "function", "function" -> function)

  private def parametersToDynamic(parameters: Vector[ToolParameterSpec]): DynamicValue.Record =
    val properties = DynamicValues.recordFromEntries(parameters.map { parameter =>
      parameter.name -> DynamicValues.record(
        "type"        := parameter.typeRef.jsonType.getOrElse("string"),
        "description" := parameter.description.getOrElse("")
      )
    })
    val required = DynamicValues.sequence(parameters.filter(_.required).map(p => DynamicValues.string(p.name)))
    DynamicValues.record("type" := "object", "properties" -> properties, "required" -> required)
