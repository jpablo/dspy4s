package dspy4s.mcp

import dspy4s.adapters.contracts.{ToolParameterSpec, ToolSpec}
import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, RuntimeError, TypeRef}
import dspy4s.programs.contracts.ToolFunction
import io.modelcontextprotocol.client.McpSyncClient
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec

/** One tool of an MCP server, as the server lists it (`tools/list`).
  *
  * @param inputSchema
  *   the JSON schema of the arguments: an object schema with `properties`, `required`, and possibly `$defs`
  */
final case class McpToolInfo(name: String, description: Option[String], inputSchema: DynamicValue.Record)
    derives CanEqual

/** One part of the content of a tool result. */
enum McpContent derives CanEqual:

  /** A text part. */
  case Text(text: String)

  /** An other part (an image, audio, a resource link, or an embedded resource) as its JSON object. */
  case Other(value: DynamicValue.Record)

/** The result of one tool call (`tools/call`).
  *
  * @param structuredContent
  *   the structured content of the result, when the server sends one
  */
final case class McpCallResult(
    content          : Vector[McpContent],
    isError          : Boolean              = false,
    structuredContent: Option[DynamicValue] = None
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
    * the first call and closes it after the last call. An exception of the SDK becomes a `Left`.
    */
  def fromSync(client: McpSyncClient): McpToolClient = SdkToolClient(client)

/** The conversion of MCP tools to dspy4s tools (upgrade item I10, PORT_GAPS G-47; upstream `convert_mcp_tool` in
  * `dspy/utils/mcp.py`).
  */
object McpTools:

  /** A [[ToolFunction]] that calls `tool` through `client`.
    *   - The name and the description are the name and the description of the MCP tool. No description gives an empty
    *     description.
    *   - [[ToolFunction.inputSchema]] is the input schema of the MCP tool, with no change.
    *   - [[ToolFunction.argSchema]] has one entry for each property of the input schema, in the order of the schema.
    *     The type comes from the `type` of the property, after a `$ref` to the `$defs` of the schema is resolved
    *     (upstream `convert_input_schema_to_tool_args`): `string`, `integer`, `number`, `boolean`, `array`, and
    *     `object` give the [[dspy4s.core.contracts.TypeRef]] `string`, `int`, `double`, `bool`, `list`, and `json`. An
    *     other type, or no type, gives `TypeRef("Any")`.
    *   - `invoke` calls the tool with the arguments of the call, and gives [[resultValue]] of the result.
    */
  def convert(
      client: McpToolClient,
      tool  : McpToolInfo,
      mode  : McpResultMode = McpResultMode.Text
  ): ToolFunction =
    val toolName   = tool.name
    val toolDesc   = tool.description.getOrElse("")
    val flatArgs   = arguments(tool.inputSchema).map(property => property.name -> property.typeRef)
    val toolSchema = Some(tool.inputSchema)
    new ToolFunction:
      override val name: String                                                                             = toolName
      override val description: String                                                                      = toolDesc
      override val argSchema: Vector[(String, TypeRef)]                                                     = flatArgs
      override val inputSchema: Option[DynamicValue.Record]                                                 = toolSchema
      override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
        client.callTool(toolName, args).flatMap(resultValue(_, mode))

  /** A [[convert]] of each tool of the server. */
  def fromClient(
      client: McpToolClient,
      mode  : McpResultMode = McpResultMode.Text
  ): Either[DspyError, Vector[ToolFunction]] =
    client.listTools().map(_.map(convert(client, _, mode)))

  /** The [[ToolSpec]] of `tool` for native function calling. The flat parameters come from the properties of the input
    * schema, as for [[convert]], with the `description` of each property and the `required` list of the schema.
    * [[ToolSpec.inputSchema]] is the input schema of the MCP tool, so the model sees the full schema.
    */
  def toolSpec(tool: McpToolInfo): ToolSpec =
    ToolSpec(
      name = tool.name,
      description = tool.description,
      parameters = arguments(tool.inputSchema).map(property =>
        ToolParameterSpec(property.name, property.typeRef, property.description, property.required)
      ),
      inputSchema = Some(tool.inputSchema)
    )

  /** The value of a tool result (upstream `_convert_mcp_tool_result`):
    *   - a result with `isError` is a `Left` with the message `Failed to call a MCP tool: <text>`, where `<text>` is
    *     the text of the single text part, else the JSON array of the texts;
    *   - in the [[McpResultMode.Structured]] mode, a result with structured content gives that content;
    *   - a single text part gives its text, unless the text is empty;
    *   - more than one text part gives the list of the texts;
    *   - else the list of the other parts, as JSON objects (an empty list when the result has no part).
    */
  def resultValue(result: McpCallResult, mode: McpResultMode): Either[DspyError, DynamicValue] =
    val texts  = result.content.collect { case McpContent.Text(text) => text }
    val others = result.content.collect { case McpContent.Other(value) => value }
    if result.isError then
      val text = texts match
        case Vector(single) => single
        case several        => several.map(jsonString).mkString("[", ", ", "]")
      Left(RuntimeError("mcp", s"Failed to call a MCP tool: $text"))
    else
      result.structuredContent.filter(_ => mode == McpResultMode.Structured) match
        case Some(structured) => Right(structured)
        case None             => texts match
            // Upstream gives `tool_content or non_text_contents`: an empty single text gives the other parts.
            case Vector(single) if single.nonEmpty => Right(string(single))
            case Vector() | Vector(_)              => Right(DynamicValue.Sequence(Chunk.from(others)))
            case several                           => Right(DynamicValue.Sequence(Chunk.from(several.map(string))))

  /** The flat view of one property of an input schema (upstream `convert_input_schema_to_tool_args`). */
  private final case class Argument(name: String, typeRef: TypeRef, description: Option[String], required: Boolean)

  /** The `TypeRef` of a property that has no type, or a type that is not in the upstream `_TYPE_MAPPING`. */
  private val AnyType = TypeRef("Any")

  /** One [[Argument]] for each property of `schema`, in the order of the schema. */
  private def arguments(schema: DynamicValue.Record): Vector[Argument] =
    val defs     = recordField(schema, "$defs")
    val required = DynamicValues.recordGet(schema, "required") match
      case Some(DynamicValue.Sequence(names)) => names.iterator.flatMap(DynamicValues.asString).toSet
      case _                                  => Set.empty[String]
    recordField(schema, "properties").fold(Vector.empty) { properties =>
      properties.fields.toVector.map { (name, property) =>
        val resolved = resolve(property, defs, Set.empty)
        Argument(name, typeRefOf(resolved), resolved.flatMap(stringField(_, "description")), required.contains(name))
      }
    }

  /** The property, after each `$ref` to the `$defs` of the schema is resolved. As upstream, the name of the definition
    * is the last segment of the reference, and the definition replaces the property. A reference that goes back to a
    * definition that it resolved before stops the resolution.
    */
  @tailrec
  private def resolve(
      property: DynamicValue,
      defs    : Option[DynamicValue.Record],
      seen    : Set[String]
  ): Option[DynamicValue.Record] =
    property match
      case record: DynamicValue.Record =>
        val target = stringField(record, "$ref")
          .map(ref => ref.substring(ref.lastIndexOf('/') + 1))
          .filterNot(seen.contains)
          .flatMap(ref => defs.flatMap(DynamicValues.recordGet(_, ref)).map(ref -> _))
        target match
          case Some((ref, definition)) => resolve(definition, defs, seen + ref)
          case None                    => Some(record)
      case _ => None

  /** The `TypeRef` of the `type` of a property (upstream `_TYPE_MAPPING`). */
  private def typeRefOf(property: Option[DynamicValue.Record]): TypeRef =
    property.flatMap(stringField(_, "type")) match
      case Some("string")  => TypeRef.string
      case Some("integer") => TypeRef.int
      case Some("number")  => TypeRef.double
      case Some("boolean") => TypeRef.bool
      case Some("array")   => TypeRef.list
      case Some("object")  => TypeRef.json
      case _               => AnyType

  private def recordField(record: DynamicValue.Record, name: String): Option[DynamicValue.Record] =
    DynamicValues.recordGet(record, name).collect { case field: DynamicValue.Record => field }

  private def stringField(record: DynamicValue.Record, name: String): Option[String] =
    DynamicValues.recordGet(record, name).flatMap(DynamicValues.asString)

  private def string(text: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(text))

  private val stringCodec = Schema[String].jsonCodec

  private def jsonString(text: String): String = new String(stringCodec.encode(text), StandardCharsets.UTF_8)
