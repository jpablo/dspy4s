package dspy4s.adapters.contracts

import dspy4s.core.contracts.TypeRef
import dspy4s.lm.contracts.LmOutput
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

final case class ToolParameterSpec(
    name: String,
    typeRef: TypeRef = TypeRef.string,
    description: Option[String] = None,
    required: Boolean = true
)

final case class ToolSpec(
    name: String,
    description: Option[String] = None,
    parameters: Vector[ToolParameterSpec] = Vector.empty
)

final case class ToolCallData(
    name: String,
    args: DynamicValue.Record,
    id: Option[String] = None
)

object ToolSchemaBridge:
  def toOpenAiTools(tools: Vector[ToolSpec]): Vector[Map[String, Any]] =
    tools.map { tool =>
      val properties = tool.parameters.map { parameter =>
        val paramSchema = Map(
          "type" -> toJsonType(parameter.typeRef),
          "description" -> parameter.description.getOrElse("")
        )
        parameter.name -> paramSchema
      }.toMap
      val required = tool.parameters.filter(_.required).map(_.name)
      Map(
        "type" -> "function",
        "function" -> Map(
          "name" -> tool.name,
          "description" -> tool.description.getOrElse(""),
          "parameters" -> Map(
            "type" -> "object",
            "properties" -> properties,
            "required" -> required
          )
        )
      )
    }

  /** Build the OpenAI `tools` array as a [[DynamicValue]] so it can be injected into a request's option bag
    * ([[dspy4s.adapters.contracts.FormattedPrompt.requestOptions]]) and spread verbatim into the provider body by
    * `ProviderRequestNormalizer`. Each tool becomes `{type:"function", function:{name, description, parameters}}`
    * where `parameters` is a JSON-schema object (`type:"object"`, `properties`, `required`). This is the
    * DynamicValue-native counterpart of [[toOpenAiTools]] (which yields the `Any`-typed map form). */
  def toOpenAiToolsDynamic(tools: Vector[ToolSpec]): DynamicValue =
    DynamicValue.Sequence(Chunk.from(tools.map(toolToDynamic)))

  private def toolToDynamic(tool: ToolSpec): DynamicValue =
    val properties = DynamicValue.Record(Chunk.from(tool.parameters.map { parameter =>
      parameter.name -> DynamicValue.Record(Chunk(
        "type"        -> str(toJsonType(parameter.typeRef)),
        "description" -> str(parameter.description.getOrElse(""))
      ))
    }))
    val required = DynamicValue.Sequence(Chunk.from(tool.parameters.filter(_.required).map(p => str(p.name))))
    val parameters = DynamicValue.Record(Chunk(
      "type"       -> str("object"),
      "properties" -> properties,
      "required"   -> required
    ))
    val function = DynamicValue.Record(Chunk(
      "name"        -> str(tool.name),
      "description" -> str(tool.description.getOrElse("")),
      "parameters"  -> parameters
    ))
    DynamicValue.Record(Chunk("type" -> str("function"), "function" -> function))

  private def str(s: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(s))

  def fromOutput(output: LmOutput): Vector[ToolCallData] =
    output.toolCalls.map(call => ToolCallData(name = call.name, args = call.args))

  private def toJsonType(typeRef: TypeRef): String =
    typeRef match
      case TypeRef.int | TypeRef.double => "number"
      case TypeRef.bool                 => "boolean"
      case TypeRef.json                 => "object"
      case _                            => "string"
