package dspy4s.mcp

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.TypeRef
import dspy4s.programs.contracts.ToolFunction
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** [[McpToolClient.fromSync]] against a real MCP server of the MCP Java SDK (upgrade item I10). The suite starts
  * [[McpTestServer]] in a child JVM with the class path of the test JVM, and connects an `McpSyncClient` to it over
  * stdio with the `StdioClientTransport` of the SDK. The suite closes the client, and so stops the server, at the end.
  */
class McpSdkClientSuite extends FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(120, "s")

  /** The lines that the server writes to stderr. A failure shows them. */
  private val serverErrors = new ConcurrentLinkedQueue[String]()

  private val server = new Fixture[McpSyncClient]("mcp-server"):
    @volatile private var client: Option[McpSyncClient] = None

    override def apply(): McpSyncClient = client.getOrElse(fail(s"the MCP client is not open; stderr: $stderr"))

    override def beforeAll(): Unit =
      val javaBin    = Paths.get(System.getProperty("java.home"), "bin", "java").toString
      val parameters = ServerParameters
        .builder(javaBin)
        .args("-cp", System.getProperty("java.class.path"), "dspy4s.mcp.McpTestServer")
        .build()
      val transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper())
      transport.setStdErrorHandler { line =>
        val _ = serverErrors.add(line)
      }
      val opened = McpClient
        .sync(transport)
        .requestTimeout(Duration.ofSeconds(30))
        .initializationTimeout(Duration.ofSeconds(60))
        .build()
      client = Some(opened)
      val _ = opened.initialize()

    override def afterAll(): Unit =
      client.foreach { opened =>
        val _ = opened.closeGracefully()
      }
      client = None

  override def munitFixtures: Seq[Fixture[?]] = List(server)

  private def stderr: String = serverErrors.asScala.mkString("\n")

  private def json(text: String): DynamicValue.Record =
    Schema.dynamic.jsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)) match
      case Right(record: DynamicValue.Record) => record
      case other                              => fail(s"not a JSON object: $other")

  private def str(text: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(text))

  private def int(number: Int): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Int(number))

  /** Each tool of the server, converted in `mode`, by name. */
  private def tools(mode: McpResultMode = McpResultMode.Text): Map[String, ToolFunction] =
    McpTools
      .fromClient(McpToolClient.fromSync(server()), mode)
      .fold(error => fail(s"${error.message}; stderr: $stderr"), _.map(tool => tool.name -> tool).toMap)

  test("listTools gives each tool of the server, with the description and the input schema in the key order") {
    val listed = McpToolClient.fromSync(server()).listTools().fold(error => fail(error.message), identity)
    assertEquals(listed.map(_.name), Vector("echo", "fail", "count", "pixel"))
    assertEquals(
      listed.map(_.description),
      Vector("Echo the text.", "Fail with a message.", "Count the words of the text.", "Draw a pixel.").map(Some(_))
    )
    assertEquals(listed.head.inputSchema, json(McpTestServer.echoSchema))
  }

  test("a converted tool calls the server with the arguments of the call and gives the text") {
    given RuntimeContext = RuntimeContext()
    val echo             = tools()("echo")
    assertEquals(echo.argSchema, Vector("text" -> TypeRef.string, "times" -> TypeRef.int))
    assertEquals(echo.inputSchema, Some(json(McpTestServer.echoSchema)))
    assertEquals(echo.invoke(DynamicValues.record("text" := "hi", "times" := 2)), Right(str("hi hi")))
  }

  test("a result that the server marks as an error is a Left with the text") {
    given RuntimeContext = RuntimeContext()
    assertEquals(
      tools()("fail").invoke(DynamicValue.Record.empty),
      Left(RuntimeError("mcp", "Failed to call a MCP tool: the input is bad"))
    )
  }

  test("the structured mode gives the structured content of the server, the text mode gives the text") {
    given RuntimeContext = RuntimeContext()
    val args             = DynamicValues.record("text" := "one two three")
    assertEquals(
      tools(McpResultMode.Structured)("count").invoke(args),
      Right(DynamicValue.Record(Chunk(
        "count" -> int(3),
        "words" -> DynamicValue.Sequence(Chunk(str("one"), str("two"), str("three")))
      )))
    )
    assertEquals(tools()("count").invoke(args), Right(str("3")))
  }

  test("an image part is the JSON object of the part") {
    given RuntimeContext = RuntimeContext()
    val parts            = tools()("pixel").invoke(DynamicValue.Record.empty) match
      case Right(DynamicValue.Sequence(elements)) => elements.toVector
      case other                                  => fail(s"not a list of parts: $other")
    val image = parts match
      case Vector(record: DynamicValue.Record) => record
      case other                               => fail(s"not one part: $other")
    assertEquals(DynamicValues.recordKeys(image).headOption, Some("type"))
    assertEquals(DynamicValues.recordGet(image, "type"), Some(str("image")))
    assertEquals(DynamicValues.recordGet(image, "data"), Some(str(McpTestServer.pixelData)))
    assertEquals(DynamicValues.recordGet(image, "mimeType"), Some(str("image/png")))
  }

  test("arguments that the server rejects give a Left") {
    given RuntimeContext = RuntimeContext()
    tools()("echo").invoke(DynamicValues.record("times" := 2)) match
      case Left(error) => assert(error.message.startsWith("Failed to call a MCP tool:"), error.message)
      case other       => fail(s"not a Left: $other")
  }

  test("a JSON-RPC error of the server is a Left, not an exception") {
    McpToolClient.fromSync(server()).callTool("missing", DynamicValue.Record.empty) match
      case Left(RuntimeError(component, message)) =>
        assertEquals(component, "mcp")
        assert(message.contains("Unknown tool"), message)
        assert(message.contains("missing"), message)
      case other => fail(s"not a Left of the mcp component: $other")
  }
