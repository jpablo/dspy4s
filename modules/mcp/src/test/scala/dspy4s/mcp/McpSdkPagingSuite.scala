package dspy4s.mcp

import dspy4s.core.contracts.RuntimeError
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.spec.McpClientTransport
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.ProtocolVersions
import munit.FunSuite
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import java.time.Duration
import java.util.function.Function as JFunction
import scala.jdk.CollectionConverters.*

/** [[McpToolClient.fromSync]] with a client transport that answers in the same JVM (upgrade item I10). The transport
  * plays a server that sends `tools/list` in pages, so the suite checks that `listTools` follows the next cursor. It
  * also checks the conversion of the arguments and of the structured content, and a JSON-RPC error.
  */
class McpSdkPagingSuite extends FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(60, "s")

  private type Handler = JFunction[Mono[McpSchema.JSONRPCMessage], Mono[McpSchema.JSONRPCMessage]]

  /** A server in the same JVM. `tools/list` gives the page of `pages` for the cursor of the request: the names of the
    * tools and the next cursor. `tools/call` of `echo_arguments` gives the arguments as structured content. Each other
    * call is a JSON-RPC error.
    */
  private final class InMemoryTransport(pages: Map[Option[String], (Vector[String], Option[String])])
      extends McpClientTransport:
    private val mapper                             = McpJsonDefaults.getMapper()
    @volatile private var inbound: Option[Handler] = None

    override def connect(handler: Handler): Mono[Void] = Mono.fromRunnable(() => inbound = Some(handler))

    override def sendMessage(message: McpSchema.JSONRPCMessage): Mono[Void] =
      message match
        case request: McpSchema.JSONRPCRequest => reply(answer(request))
        case _                                 => ()
      Mono.empty()

    override def closeGracefully(): Mono[Void] = Mono.empty()

    override def unmarshalFrom[T](data: AnyRef, typeRef: TypeRef[T]): T = mapper.convertValue(data, typeRef)

    private def reply(response: McpSchema.JSONRPCResponse): Unit =
      inbound.foreach { handler =>
        val _ = handler
          .apply(Mono.just[McpSchema.JSONRPCMessage](response))
          .subscribeOn(Schedulers.boundedElastic())
          .subscribe()
      }

    private def answer(request: McpSchema.JSONRPCRequest): McpSchema.JSONRPCResponse =
      request.method() match
        case McpSchema.METHOD_INITIALIZE =>
          val capabilities = McpSchema.ServerCapabilities.builder().tools(java.lang.Boolean.FALSE).build()
          val info         = McpSchema.Implementation.builder("in-memory", "1.0.0").build()
          McpSchema.JSONRPCResponse.result(
            request.id(),
            McpSchema.InitializeResult.builder(ProtocolVersions.MCP_2025_06_18, capabilities, info).build()
          )
        case McpSchema.METHOD_TOOLS_LIST =>
          val cursor = request.params() match
            case paged: McpSchema.PaginatedRequest => Option(paged.cursor())
            case _                                 => None
          pages.get(cursor) match
            case Some((names, next)) =>
              val tools = names.map(name =>
                McpSchema.Tool.builder(name, java.util.Map.of[String, AnyRef]("type", "object")).build()
              )
              McpSchema.JSONRPCResponse.result(
                request.id(),
                McpSchema.ListToolsResult.builder(tools.asJava).nextCursor(next.orNull).build()
              )
            case None => error(request, s"no page for the cursor $cursor", None)
        case McpSchema.METHOD_TOOLS_CALL => request.params() match
            case call: McpSchema.CallToolRequest if call.name() == "echo_arguments" =>
              McpSchema.JSONRPCResponse.result(
                request.id(),
                McpSchema.CallToolResult.builder().addTextContent("ok").structuredContent(call.arguments()).build()
              )
            case call: McpSchema.CallToolRequest =>
              error(request, "Unknown tool", Some(s"Tool not found: ${call.name()}"))
            case _ => error(request, "not a tool call", None)
        case other => error(request, s"no method $other", None)

    private def error(
        request: McpSchema.JSONRPCRequest,
        message: String,
        data   : Option[String]
    ): McpSchema.JSONRPCResponse =
      McpSchema.JSONRPCResponse.error(
        request.id(),
        new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_PARAMS, message, data.orNull)
      )

  private def withClient[A](pages: Map[Option[String], (Vector[String], Option[String])])(body: McpSyncClient => A): A =
    val client = McpClient.sync(InMemoryTransport(pages)).requestTimeout(Duration.ofSeconds(10)).build()
    try
      val _ = client.initialize()
      body(client)
    finally client.close()

  private def str(text: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(text))

  test("listTools follows the next cursor to the last page") {
    val pages = Map(
      None           -> (Vector("a", "b"), Some("page-2")),
      Some("page-2") -> (Vector("c"), Some("page-3")),
      Some("page-3") -> (Vector("d", "e"), None)
    )
    val names = withClient(pages)(client => McpToolClient.fromSync(client).listTools().map(_.map(_.name)))
    assertEquals(names, Right(Vector("a", "b", "c", "d", "e")))
  }

  test("a cursor that comes a second time gives a Left") {
    val pages = Map(
      None           -> (Vector("a"), Some("page-2")),
      Some("page-2") -> (Vector("b"), Some("page-2"))
    )
    val listed = withClient(pages)(client => McpToolClient.fromSync(client).listTools())
    assertEquals(listed, Left(RuntimeError("mcp", "MCP tools/list sent the cursor 'page-2' a second time")))
  }

  test("the arguments and the structured content keep the key order and the number types") {
    val arguments = DynamicValue.Record(Chunk(
      "b"   -> DynamicValue.Primitive(PrimitiveValue.Int(1)),
      "big" -> DynamicValue.Primitive(PrimitiveValue.Long(5000000000L)),
      "a"   -> DynamicValue.Sequence(Chunk(
        DynamicValue.Primitive(PrimitiveValue.Boolean(true)),
        DynamicValue.Primitive(PrimitiveValue.Double(2.5))
      )),
      "none"   -> DynamicValue.Null,
      "nested" -> DynamicValue.Record(Chunk("z" -> str("x"), "y" -> str("w")))
    ))
    val result = withClient(Map.empty)(client => McpToolClient.fromSync(client).callTool("echo_arguments", arguments))
    assertEquals(result, Right(McpCallResult(Vector(McpContent.Text("ok")), structuredContent = Some(arguments))))
  }

  test("a JSON-RPC error of tools/call gives a Left with the message and the data of the error") {
    val result =
      withClient(Map.empty)(client => McpToolClient.fromSync(client).callTool("missing", DynamicValue.Record.empty))
    assertEquals(
      result,
      Left(RuntimeError("mcp", "MCP tools/call of 'missing' failed: Unknown tool (Tool not found: missing)"))
    )
  }
