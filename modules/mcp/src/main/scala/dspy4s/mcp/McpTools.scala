package dspy4s.mcp

import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, PythonText, RuntimeError, TypeRef}
import dspy4s.programs.contracts.ToolFunction
import io.modelcontextprotocol.client.McpSyncClient
import zio.blocks.schema.DynamicValue

/** One tool of an MCP server, as the server lists it (`tools/list`).
  *
  * @param inputSchema
  *   the JSON schema of the arguments, as the server sends it: an object schema with `properties`, `required`, and
  *   possibly `$defs`
  */
final case class McpToolInfo(name: String, description: Option[String], inputSchema: DynamicValue.Record)
    derives CanEqual

/** The result of one tool call (`tools/call`).
  *
  * @param texts
  *   the text of each text part of the content, in order
  * @param others
  *   each other part of the content (an image, audio, a resource link, or an embedded resource) as its JSON object
  * @param structuredContent
  *   the structured content of the result, when the server sends one
  */
final case class McpCallResult(
    texts            : Vector[String],
    others           : Vector[DynamicValue.Record] = Vector.empty,
    isError          : Boolean                     = false,
    structuredContent: Option[DynamicValue]        = None
) derives CanEqual

/** The value that a converted tool gives (upstream `result_mode`). */
enum McpResultMode derives CanEqual:

  /** The text of the result, as upstream "text": the text of a single text part, the list of the texts of more than one
    * text part, else the list of the other parts.
    */
  case Text

  /** The structured content of the result when the server sends one, else the value of [[Text]] (upstream "structured",
    * ca0a088bd).
    */
  case Structured

/** The part of an MCP client that the conversion uses (upstream `_MCPToolClient`). [[McpToolClient.fromSync]] gives one
  * for a client of the MCP Java SDK. A test can give a stub.
  */
trait McpToolClient:

  /** Each tool of the server, from all the pages of `tools/list`. */
  def listTools(): Either[DspyError, Vector[McpToolInfo]]

  /** Call the tool `name` with `arguments`. A failure of the transport or of the protocol is a `Left`. A result that
    * the server marks as an error is a `Right` with `isError = true`.
    */
  def callTool(name: String, arguments: DynamicValue.Record): Either[DspyError, McpCallResult]

object McpToolClient:

  /** The client of the MCP Java SDK as an [[McpToolClient]]. The caller owns `client`: it initializes the client before
    * the first call and closes it after the last call.
    *
    * Each call blocks until the server answers or the request timeout of `client` ends. The default of the SDK is 20
    * seconds, so give `requestTimeout` to the builder of the client for a slow tool. An interrupt of the caller goes up
    * as an `InterruptedException`. Each other exception of the SDK becomes a `Left`.
    */
  def fromSync(client: McpSyncClient): McpToolClient = SdkToolClient(client)

/** The conversion of MCP tools to dspy4s tools (upgrade item I10, PORT_GAPS G-47; upstream `convert_mcp_tool` in
  * `dspy/utils/mcp.py`).
  */
object McpTools:

  /** A [[ToolFunction]] that calls `tool` through `client`.
    *   - The name and the description are the name and the description of the MCP tool. No description gives an empty
    *     description.
    *   - [[ToolFunction.inputSchema]] is the object schema of [[toolSpec]].
    *   - [[ToolFunction.argSchema]] has one entry for each property of the object schema, in the order of the schema.
    *     The type comes from the `type` of the property ([[TypeRef.fromJsonType]], upstream `_TYPE_MAPPING`). An other
    *     type, or no type, gives `TypeRef("Any")`.
    *   - `invoke` calls the tool with the arguments of the call, and gives the value of the result (upstream
    *     `_convert_mcp_tool_result`).
    */
  def convert(
      client: McpToolClient,
      tool  : McpToolInfo,
      mode  : McpResultMode = McpResultMode.Text
  ): ToolFunction =
    val schema = objectSchema(tool.inputSchema)
    val flat   = properties(schema).fields.toVector.map((name, property) => name -> typeRefOf(property))
    ToolFunction(tool.name, tool.description.getOrElse(""), flat, Some(schema))(args =>
      client.callTool(tool.name, args).flatMap(resultValue(_, mode))
    )

  /** A [[convert]] of each tool of the server. */
  def fromClient(
      client: McpToolClient,
      mode  : McpResultMode = McpResultMode.Text
  ): Either[DspyError, Vector[ToolFunction]] =
    client.listTools().map(_.map(convert(client, _, mode)))

  /** The [[ToolSpec]] of `tool` for native function calling. [[ToolSpec.inputSchema]] is the object schema of the tool:
    * `type`, `properties`, and the `required` list of the server (upstream `format_as_litellm_function_call`). Each
    * property has each `$ref` expanded (upstream `convert_input_schema_to_tool_args`).
    */
  def toolSpec(tool: McpToolInfo): ToolSpec =
    ToolSpec(tool.name, tool.description, objectSchema(tool.inputSchema))

  /** The value of a tool result (upstream `_convert_mcp_tool_result`):
    *   - a result with `isError` is a `Left` with the message `Failed to call a MCP tool: <text>`, where `<text>` is
    *     the text of the single text part, else the JSON array of the texts;
    *   - in the [[McpResultMode.Structured]] mode, a result with structured content gives that content;
    *   - a single text part gives its text, unless the text is empty;
    *   - more than one text part gives the list of the texts;
    *   - else the list of the other parts, as JSON objects (an empty list when the result has no part).
    */
  private[mcp] def resultValue(result: McpCallResult, mode: McpResultMode): Either[DspyError, DynamicValue] =
    val content = result.texts match
      case Vector(single) => DynamicValues.string(single)
      case several        => DynamicValues.sequence(several.map(DynamicValues.string))
    if result.isError then Left(RuntimeError("mcp", s"Failed to call a MCP tool: ${PythonText.str(content)}"))
    else
      result.structuredContent.filter(_ => mode == McpResultMode.Structured) match
        case Some(structured) => Right(structured)
        case None             =>
          // Upstream gives `tool_content or non_text_contents`: an empty single text gives the other parts.
          val noText = result.texts.isEmpty || result.texts == Vector("")
          Right(if noText then DynamicValues.sequence(result.others) else content)

  /** The schema of a converted tool, from the input schema of the server. Only `type`, `properties`, and `required` go
    * to the model. When the input schema has definitions (`$defs`), each property has each `$ref` expanded.
    */
  private def objectSchema(inputSchema: DynamicValue.Record): DynamicValue.Record =
    val definitions = DynamicValues.recordGet(inputSchema, "$defs").flatMap(DynamicValues.asRecord)
      .filter(_.fields.nonEmpty)
    val all = properties(inputSchema)
    DynamicValues.record(
      "type"       := "object",
      "properties" -> definitions.fold[DynamicValue](all)(expanded(all, _, Set.empty)),
      "required"   -> DynamicValues.sequence(DynamicValues.sequenceAt(inputSchema, "required"))
    )

  private def properties(schema: DynamicValue.Record): DynamicValue.Record =
    DynamicValues.recordGet(schema, "properties").flatMap(DynamicValues.asRecord).getOrElse(DynamicValue.Record.empty)

  /** `value`, with each record that has a string `$ref` at each depth replaced by the definition, which is also
    * expanded. As upstream, the name of the definition is the last segment of the reference, and the siblings of `$ref`
    * go. `path` holds the definitions of this walk. A `$ref` with no definition, or a `$ref` to a definition on the
    * path, becomes `{}`, the schema of any value. Upstream raises here, but a recursive schema is valid JSON schema.
    */
  private def expanded(value: DynamicValue, definitions: DynamicValue.Record, path: Set[String]): DynamicValue =
    value match
      case record: DynamicValue.Record => stringField(record, "$ref") match
          case Some(reference) =>
            val name = reference.substring(reference.lastIndexOf('/') + 1)
            DynamicValues.recordGet(definitions, name).filterNot(_ => path.contains(name))
              .fold[DynamicValue](DynamicValue.Record.empty)(expanded(_, definitions, path + name))
          case None => DynamicValue.Record(record.fields.map((key, item) => key -> expanded(item, definitions, path)))
      case DynamicValue.Sequence(items) => DynamicValue.Sequence(items.map(expanded(_, definitions, path)))
      case other                        => other

  /** The `TypeRef` of the `type` of a property (upstream `_TYPE_MAPPING`). */
  private def typeRefOf(property: DynamicValue): TypeRef =
    stringField(property, "type").flatMap(TypeRef.fromJsonType).getOrElse(TypeRef("Any"))

  private def stringField(value: DynamicValue, name: String): Option[String] =
    DynamicValues.recordGet(value, name).flatMap(DynamicValues.asString)
