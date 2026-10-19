package dspy4s.adapters.contracts

import dspy4s.core.contracts.{DynamicValues, TypeRef}
import dspy4s.lm.contracts.LmOutput
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

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

final case class ToolCallData(
    name: String,
    args: DynamicValue.Record,
    id  : Option[String] = None
)

object ToolSchemaBridge:

  /** Build the OpenAI `tools` array in the `Map[String, Any]` form. The `parameters` of a tool are its
    * [[ToolSpec.inputSchema]] when it has one, else a JSON-schema object built from its [[ToolSpec.parameters]].
    */
  def toOpenAiTools(tools: Vector[ToolSpec]): Vector[Map[String, Any]] =
    tools.map { tool =>
      val parameters = tool.inputSchema.fold[Map[String, Any]] {
        val properties = tool.parameters.map { parameter =>
          val paramSchema = Map(
            "type"        -> toJsonType(parameter.typeRef),
            "description" -> parameter.description.getOrElse("")
          )
          parameter.name -> paramSchema
        }.toMap
        val required = tool.parameters.filter(_.required).map(_.name)
        Map(
          "type"       -> "object",
          "properties" -> properties,
          "required"   -> required
        )
      }(DynamicValues.recordToMap)
      Map(
        "type"     -> "function",
        "function" -> Map(
          "name"        -> tool.name,
          "description" -> tool.description.getOrElse(""),
          "parameters"  -> parameters
        )
      )
    }

  /** Build the OpenAI `tools` array as a [[DynamicValue]] so it can be injected into a request's option bag
    * ([[dspy4s.adapters.contracts.FormattedPrompt.requestOptions]]) and spread verbatim into the provider body by
    * `ProviderRequestNormalizer`. Each tool becomes `{type:"function", function:{name, description, parameters}}` where
    * `parameters` is the [[ToolSpec.inputSchema]] of the tool when it has one, else a JSON-schema object
    * (`type:"object"`, `properties`, `required`) built from its [[ToolSpec.parameters]]. This is the
    * DynamicValue-native counterpart of [[toOpenAiTools]] (which yields the `Map[String, Any]` form).
    */
  def toOpenAiToolsDynamic(tools: Vector[ToolSpec]): DynamicValue =
    DynamicValue.Sequence(Chunk.from(tools.map(toolToDynamic)))

  private def toolToDynamic(tool: ToolSpec): DynamicValue =
    val function = DynamicValue.Record(Chunk(
      "name"        -> str(tool.name),
      "description" -> str(tool.description.getOrElse("")),
      "parameters"  -> tool.inputSchema.getOrElse(parametersToDynamic(tool.parameters))
    ))
    DynamicValue.Record(Chunk("type" -> str("function"), "function" -> function))

  private def parametersToDynamic(parameters: Vector[ToolParameterSpec]): DynamicValue.Record =
    val properties = DynamicValue.Record(Chunk.from(parameters.map { parameter =>
      parameter.name -> DynamicValue.Record(Chunk(
        "type"        -> str(toJsonType(parameter.typeRef)),
        "description" -> str(parameter.description.getOrElse(""))
      ))
    }))
    val required = DynamicValue.Sequence(Chunk.from(parameters.filter(_.required).map(p => str(p.name))))
    DynamicValue.Record(Chunk(
      "type"       -> str("object"),
      "properties" -> properties,
      "required"   -> required
    ))

  private def str(s: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(s))

  def fromOutput(output: LmOutput): Vector[ToolCallData] =
    output.toolCalls.map(call => ToolCallData(name = call.name, args = call.args))

  private def toJsonType(typeRef: TypeRef): String =
    typeRef match
      case TypeRef.int | TypeRef.double => "number"
      case TypeRef.bool                 => "boolean"
      case TypeRef.json                 => "object"
      case _                            => "string"
