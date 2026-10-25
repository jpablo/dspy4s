package dspy4s.mcp

import dspy4s.core.contracts.{DspyError, RuntimeError}
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.json.{McpJsonDefaults, McpJsonMapper}
import io.modelcontextprotocol.spec.{McpError, McpSchema}
import zio.blocks.schema.DynamicValue

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** An [[McpToolClient]] over a client of the MCP Java SDK (see [[McpToolClient.fromSync]]). Each call blocks until the
  * server answers or the request timeout of `client` ends.
  */
private[mcp] final class SdkToolClient(client: McpSyncClient) extends McpToolClient:

  /** The mapper that gives the JSON object of a content part that is not text. */
  private lazy val mapper: McpJsonMapper = McpJsonDefaults.getMapper()

  override def listTools(): Either[DspyError, Vector[McpToolInfo]] =
    guarded("tools/list")(pages(None, Set.empty, Vector.empty))

  override def callTool(name: String, arguments: DynamicValue.Record): Either[DspyError, McpCallResult] =
    guarded(s"tools/call of '$name'") {
      val request = McpSchema.CallToolRequest.builder(name).arguments(JavaJson.toJavaMap(arguments)).build()
      Option(client.callTool(request)) match
        case Some(result) => Right(callResult(result))
        case None         => Left(RuntimeError("mcp", s"MCP tools/call of '$name' gave no result"))
    }

  /** Each page of `tools/list`, from `cursor` to the page with no next cursor. A server that sends a cursor a second
    * time gives a `Left`, because the list does not end.
    */
  @tailrec
  private def pages(
      cursor: Option[String],
      seen  : Set[String],
      tools : Vector[McpToolInfo]
  ): Either[DspyError, Vector[McpToolInfo]] =
    Option(client.listTools(cursor.getOrElse(McpSchema.FIRST_PAGE))) match
      case None       => Left(RuntimeError("mcp", "MCP tools/list gave no result"))
      case Some(page) =>
        val all = tools ++ Option(page.tools()).fold(Vector.empty)(_.asScala.toVector.map(toolInfo))
        Option(page.nextCursor()).filter(_.nonEmpty) match
          case None                              => Right(all)
          case Some(next) if seen.contains(next) =>
            Left(RuntimeError("mcp", s"MCP tools/list sent the cursor '$next' a second time"))
          case Some(next) => pages(Some(next), seen + next, all)

  private def toolInfo(tool: McpSchema.Tool): McpToolInfo =
    McpToolInfo(
      name = tool.name(),
      description = Option(tool.description()),
      inputSchema = Option(tool.inputSchema()).fold(DynamicValue.Record.empty)(JavaJson.toRecord)
    )

  private def callResult(result: McpSchema.CallToolResult): McpCallResult =
    McpCallResult(
      content =
        Option(result.content()).fold(Vector.empty)(_.asScala.toVector.flatMap(part => Option(part).map(content))),
      isError = Option(result.isError()).exists(_.booleanValue),
      structuredContent = Option(result.structuredContent()).map(JavaJson.toDynamic)
    )

  /** A text part becomes [[McpContent.Text]]. An other part becomes its JSON object, as the SDK writes it: the `type`
    * key and the fields of the part.
    */
  private def content(part: McpSchema.Content): McpContent =
    part match
      case text: McpSchema.TextContent => McpContent.Text(Option(text.text()).getOrElse(""))
      case other                       => McpContent.Other(jsonObject(other))

  private def jsonObject(part: McpSchema.Content): DynamicValue.Record =
    JavaJson.toRecord(mapper.convertValue(part, classOf[java.util.Map[?, ?]]))

  /** `body`, with an exception of the SDK as a `Left`. */
  private def guarded[A](operation: String)(body: => Either[DspyError, A]): Either[DspyError, A] =
    try body
    catch case NonFatal(error) => Left(RuntimeError("mcp", s"MCP $operation failed: ${describe(error)}"))

  /** The message of `error`, with the data of a JSON-RPC error when the server sends one. */
  private def describe(error: Throwable): String =
    val message = DspyError.messageOf(error)
    error match
      case mcp: McpError =>
        Option(mcp.getJsonRpcError).flatMap(rpc => Option(rpc.data())).fold(message)(data => s"$message ($data)")
      case _ => message
