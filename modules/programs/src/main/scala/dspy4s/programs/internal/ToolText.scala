package dspy4s.programs.internal

import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.{:=, DynamicValues, PythonText, TypeRef}
import dspy4s.programs.contracts.ToolFunction
import zio.blocks.schema.DynamicValue

/** The prompt texts and the provider tool of a tool, in the port form of upstream `Tool`
  * (`dspy/adapters/types/tool.py`, decision D3). `ReAct` lists each tool with [[describe]], and `RLM` lists the
  * parameters of each tool with [[parameters]]. `ReActV2` and the extract steps of `ReAct` and `RLM` give their
  * predictors the [[toolSpec]] of each tool.
  */
private[programs] object ToolText:

  /** The schema of each argument of `tool` (upstream `Tool.args`): the `properties` of [[ToolFunction.inputSchema]]
    * when the tool has one. Else one `{"type": <JSON type>}` for each entry of [[ToolFunction.argSchema]], and `{}` for
    * a type that has no JSON type.
    */
  def args(tool: ToolFunction): DynamicValue.Record =
    tool.inputSchema match
      case Some(schema) => ToolSpec.arguments(schema)
      case None         => DynamicValues.recordFromEntries(tool.argSchema.map((name, typeRef) => name -> argument(typeRef)))

  /** The text of `tool` in a list of tools (upstream `Tool.__str__`, [[ToolSpec.describe]]). */
  def describe(tool: ToolFunction): String =
    ToolSpec.describe(tool.name, tool.description, args(tool))

  /** The provider tool of `tool` (upstream `Tool.format_as_litellm_function_call`): the input schema of the tool, else
    * an object schema with the arguments of [[args]] and each argument in `required`.
    */
  def toolSpec(tool: ToolFunction): ToolSpec =
    val arguments = args(tool)
    ToolSpec(
      name = tool.name,
      description = Some(tool.description).filter(_.nonEmpty),
      inputSchema = tool.inputSchema.getOrElse(
        DynamicValues.record(
          "type"       := "object",
          "properties" -> arguments,
          "required"   -> DynamicValues.sequence(DynamicValues.recordKeys(arguments).map(DynamicValues.string))
        )
      )
    )

  /** The parameters of `tool` as `name: type` texts (upstream `RLM._format_tool_docs`). An argument with no `type` has
    * the type `Any`.
    */
  def parameters(tool: ToolFunction): String =
    args(tool).fields.map((name, schema) => s"$name: ${typeText(schema)}").mkString(", ")

  private def typeText(schema: DynamicValue): String =
    schema match
      case record: DynamicValue.Record => DynamicValues.recordGet(record, "type")
          .fold("Any")(value => DynamicValues.asString(value).getOrElse(PythonText.json(value)))
      case _ => "Any"

  /** The schema of an argument: its JSON type ([[TypeRef.jsonType]], the types of upstream `Tool._parse_function`). A
    * list has the empty `items` of a Python `list`, because a provider can reject an array with no `items`.
    */
  private[programs] def argument(typeRef: TypeRef): DynamicValue.Record =
    typeRef match
      case TypeRef.list => DynamicValues.record("items" -> DynamicValue.Record.empty, "type" := "array")
      case _            => typeRef.jsonType.fold(DynamicValue.Record.empty)(name => DynamicValues.record("type" := name))
