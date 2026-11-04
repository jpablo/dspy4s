package dspy4s.adapters.contracts

import dspy4s.core.contracts.{:=, DynamicValues, PythonText}
import zio.blocks.schema.DynamicValue

/** A tool that a model can call. The request has it as a provider tool ([[ToolSchemaBridge]]), or the prompt shows its
  * [[text]] ([[NativeTools.promptInvocation]]).
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
):

  /** The text of this tool in a list of tools ([[ToolSpec.describe]]). */
  def text: String =
    ToolSpec.describe(name, description.getOrElse(""), ToolSpec.arguments(inputSchema))

object ToolSpec:

  /** The schema of each argument of a tool (upstream `Tool.args`): the `properties` of the object schema `schema`, and
    * an empty record when it has none.
    */
  private[dspy4s] def arguments(schema: DynamicValue.Record): DynamicValue.Record =
    DynamicValues.recordGet(schema, "properties").collect { case properties: DynamicValue.Record => properties }
      .getOrElse(DynamicValue.Record.empty)

  /** The text of a tool in a list of tools (upstream `Tool.__str__`): the name, the description, and the arguments as
    * JSON text. A tool with an empty description has no description part. This function is the one source of this text:
    * [[ToolSpec.text]] and `ToolText.describe` of the programs module use it.
    */
  private[dspy4s] def describe(name: String, description: String, arguments: DynamicValue.Record): String =
    val desc =
      if description.nonEmpty then s", whose description is <desc>$description</desc>.".replace("\n", "  ")
      else "."
    s"$name$desc It takes arguments ${PythonText.json(arguments)}."

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
