package dspy4s.mcp

import dspy4s.core.contracts.{DspyError, DynamicValues, PythonText, RuntimeError}
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.json.{McpJsonDefaults, McpJsonMapper, TypeRef}
import io.modelcontextprotocol.spec.{McpError, McpSchema}
import reactor.core.Exceptions
import zio.blocks.schema.DynamicValue

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/** An [[McpToolClient]] over a client of the MCP Java SDK (see [[McpToolClient.fromSync]]). Each call blocks until the
  * server answers or the request timeout of `client` ends. The default of the SDK is 20 seconds, so give
  * `requestTimeout` to the builder of the client for a slow tool. An interrupt of the caller goes up as an
  * `InterruptedException`. Each other exception of the SDK becomes a `Left`.
  */
private[mcp] final class SdkToolClient(client: McpSyncClient) extends McpToolClient:
  import SdkToolClient.guarded

  private val mapper: McpJsonMapper = McpJsonDefaults.getMapper()

  override def listTools(): Either[DspyError, Vector[McpToolInfo]] =
    guarded("tools/list")(pages(None, Set.empty, Vector.empty))

  override def callTool(name: String, arguments: DynamicValue.Record): Either[DspyError, McpCallResult] =
    guarded(s"tools/call of '$name'") {
      DynamicValues.tryEncodeJson(arguments) match
        case Left(cause) => Left(RuntimeError("mcp", s"MCP tools/call of '$name' failed: $cause"))
        case Right(text) =>
          // `Builder.arguments(mapper, text)` of the SDK reads a `HashMap`, and that loses the key order.
          val javaArguments = mapper.readValue(text, new TypeRef[java.util.LinkedHashMap[String, AnyRef]] {})
          val request       = McpSchema.CallToolRequest.builder(name).arguments(javaArguments).build()
          Right(callResult(client.callTool(request)))
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
    val page = client.listTools(cursor.getOrElse(McpSchema.FIRST_PAGE))
    val all  = tools ++ page.tools().asScala.map(toolInfo)
    Option(page.nextCursor()).filter(_.nonEmpty) match
      case None                              => Right(all)
      case Some(next) if seen.contains(next) =>
        Left(RuntimeError("mcp", s"MCP tools/list sent the cursor '$next' a second time"))
      case Some(next) => pages(Some(next), seen + next, all)

  private def toolInfo(tool: McpSchema.Tool): McpToolInfo =
    McpToolInfo(
      name = tool.name(),
      description = Option(tool.description()),
      inputSchema = jsonObject(tool.inputSchema())
    )

  /** A part that is not text becomes its JSON object, as the SDK writes it: the `type` key and the fields of the part.
    */
  private def callResult(result: McpSchema.CallToolResult): McpCallResult =
    val (texts, others) = result.content().asScala.toVector.flatMap(Option(_)).partitionMap {
      case text: McpSchema.TextContent => Left(text.text())
      case other                       => Right(jsonObject(other))
    }
    McpCallResult(
      texts = texts,
      others = others,
      isError = Option(result.isError()).exists(_.booleanValue),
      structuredContent = Option(result.structuredContent()).map(jsonValue)
    )

  /** The value of a JSON value of the SDK, through the JSON text. A read of a text from the mapper cannot fail, so this
    * method throws in that case, and [[guarded]] gives the `Left`.
    */
  private def jsonValue(value: AnyRef): DynamicValue =
    PythonText.parseJson(mapper.writeValueAsString(value)).fold(
      error => throw IllegalStateException(error.message),
      identity
    )

  private def jsonObject(value: AnyRef): DynamicValue.Record =
    DynamicValues.asRecord(jsonValue(value)).getOrElse(throw IllegalStateException(s"Not a JSON object: $value"))

private[mcp] object SdkToolClient:

  /** Initialize `client`, run `body` with it, and close it (see [[McpToolClient.using]]). */
  def using[A](client: => McpSyncClient)(body: McpToolClient => Either[DspyError, A]): Either[DspyError, A] =
    guarded("client") {
      Using.resource(client) { opened =>
        val _ = opened.initialize()
        body(SdkToolClient(opened))
      }
    }

  /** `body`, with an exception of the SDK as a `Left`. `Mono.block` of the SDK wraps an interrupt in a
    * `RuntimeException` and sets the interrupt status. The interrupt goes up as the `InterruptedException`, with the
    * status clear, as for an `InterruptedException` of a blocking call of the JDK.
    */
  private def guarded[A](operation: String)(body: => Either[DspyError, A]): Either[DspyError, A] =
    try body
    catch
      case NonFatal(error) => Exceptions.unwrap(error) match
          case interrupt: InterruptedException =>
            val _ = Thread.interrupted()
            throw interrupt
          case cause => Left(RuntimeError("mcp", s"MCP $operation failed: ${describe(cause)}"))

  /** The message of `error`, with the data of a JSON-RPC error when the server sends one. */
  private def describe(error: Throwable): String =
    val message = DspyError.messageOf(error)
    error match
      case mcp: McpError =>
        Option(mcp.getJsonRpcError).flatMap(rpc => Option(rpc.data())).fold(message)(data => s"$message ($data)")
      case _ => message
