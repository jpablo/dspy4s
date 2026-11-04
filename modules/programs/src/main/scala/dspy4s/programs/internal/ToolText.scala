package dspy4s.programs.internal

import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.{:=, DynamicValues, PythonText, TypeRef}
import dspy4s.programs.contracts.ToolFunction
import zio.blocks.schema.DynamicValue

/** The prompt texts of a tool, in the port form of upstream `Tool` (`dspy/adapters/types/tool.py`, decision D3).
  * `ReAct` lists each tool with [[describe]], and `RLM` lists the parameters of each tool with [[parameters]].
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

  /** The schema of an argument: its JSON type ([[TypeRef.jsonType]], the types of upstream `Tool._parse_function`). */
  private def argument(typeRef: TypeRef): DynamicValue.Record =
    typeRef.jsonType.fold(DynamicValue.Record.empty)(name => DynamicValues.record("type" := name))
