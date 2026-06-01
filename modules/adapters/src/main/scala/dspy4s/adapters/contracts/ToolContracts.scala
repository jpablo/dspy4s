package dspy4s.adapters.contracts

import dspy4s.core.contracts.TypeRef
import dspy4s.lm.contracts.LmOutput
import zio.blocks.schema.DynamicValue

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

  def fromOutput(output: LmOutput): Vector[ToolCallData] =
    output.toolCalls.map(call => ToolCallData(name = call.name, args = call.args))

  private def toJsonType(typeRef: TypeRef): String =
    typeRef match
      case TypeRef.int | TypeRef.double => "number"
      case TypeRef.bool                 => "boolean"
      case TypeRef.json                 => "object"
      case _                            => "string"
