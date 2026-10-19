package dspy4s.mcp

import dspy4s.adapters.contracts.ToolParameterSpec
import dspy4s.adapters.contracts.ToolSchemaBridge
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.TypeRef
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets

/** The conversion of MCP tools to dspy4s tools (upgrade item I10, PORT_GAPS G-47; upstream `convert_mcp_tool` and
  * `_convert_mcp_tool_result` in `dspy/utils/mcp.py`, with the tests of `tests/utils/test_mcp.py`).
  */
class McpToolsSuite extends FunSuite:

  private def json(text: String): DynamicValue.Record =
    Schema.dynamic.jsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)) match
      case Right(record: DynamicValue.Record) => record
      case other                              => fail(s"not a JSON object: $other")

  private def str(text: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(text))

  private def strings(texts: String*): DynamicValue = DynamicValue.Sequence(Chunk.from(texts.map(str)))

  private val searchSchema = json(
    """{"type": "object", "properties": {"query": {"type": "string", "description": "The query"}, "limit": {"type": "integer"}, "filter": {"$ref": "#/$defs/Filter"}, "tags": {"type": "array", "items": {"type": "string"}}, "exact": {"type": "boolean"}, "score": {"type": "number"}, "anything": {}}, "required": ["query", "filter"], "$defs": {"Filter": {"type": "object", "properties": {"lang": {"type": "string"}}}}}"""
  )

  private val search = McpToolInfo("search", Some("Search the web."), searchSchema)

  /** A client that keeps each call and answers with `result`. */
  private final class StubClient(
      tools : Vector[McpToolInfo],
      result: Either[DspyError, McpCallResult] = Right(McpCallResult(Vector(McpContent.Text("ok"))))
  ) extends McpToolClient:
    @volatile var calls: Vector[(String, DynamicValue.Record)]                                            = Vector.empty
    override def listTools(): Either[DspyError, Vector[McpToolInfo]]                                      = Right(tools)
    override def callTool(name: String, arguments: DynamicValue.Record): Either[DspyError, McpCallResult] =
      calls = calls :+ (name -> arguments)
      result

  private val imageJson = json("""{"type": "image", "data": "aGk=", "mimeType": "image/png"}""")
  private val image     = McpContent.Other(imageJson)

  test("a converted tool has the name, the description, and the input schema of the MCP tool") {
    val tool = McpTools.convert(StubClient(Vector(search)), search)
    assertEquals(tool.name, "search")
    assertEquals(tool.description, "Search the web.")
    assertEquals(tool.inputSchema, Some(searchSchema))
  }

  test("the arguments of a converted tool have the types of the properties, after the $ref is resolved") {
    val tool = McpTools.convert(StubClient(Vector(search)), search)
    assertEquals(
      tool.argSchema,
      Vector(
        "query"    -> TypeRef.string,
        "limit"    -> TypeRef.int,
        "filter"   -> TypeRef.json,
        "tags"     -> TypeRef.list,
        "exact"    -> TypeRef.bool,
        "score"    -> TypeRef.double,
        "anything" -> TypeRef("Any")
      )
    )
  }

  test("a tool with no description has an empty description, and a schema with no properties has no arguments") {
    val bare = McpToolInfo("ping", None, json("""{"type": "object"}"""))
    val tool = McpTools.convert(StubClient(Vector(bare)), bare)
    assertEquals(tool.description, "")
    assertEquals(tool.argSchema, Vector.empty)
  }

  test("a call of a converted tool calls the MCP tool with the arguments of the call") {
    given RuntimeContext = RuntimeContext()
    val client           = StubClient(Vector(search), Right(McpCallResult(Vector(McpContent.Text("3 results")))))
    val args             = DynamicValues.record("query" := "cats", "limit" := 3)
    assertEquals(McpTools.convert(client, search).invoke(args), Right(str("3 results")))
    assertEquals(client.calls, Vector("search" -> args))
  }

  test("a failure of the client is the failure of the call") {
    given RuntimeContext = RuntimeContext()
    val failure          = RuntimeError("mcp", "connection closed")
    val client           = StubClient(Vector(search), Left(failure))
    assertEquals(McpTools.convert(client, search).invoke(DynamicValue.Record.empty), Left(failure))
  }

  test("fromClient converts each tool of the server") {
    val ping  = McpToolInfo("ping", None, json("""{"type": "object"}"""))
    val tools = McpTools.fromClient(StubClient(Vector(search, ping))).fold(error => fail(error.message), identity)
    assertEquals(tools.map(_.name), Vector("search", "ping"))
  }

  // ── The value of a result ──────────────────────────────────────────────────────────────────────────────────────

  private def value(result: McpCallResult, mode: McpResultMode = McpResultMode.Text): DynamicValue =
    McpTools.resultValue(result, mode).fold(error => fail(error.message), identity)

  test("a single text part gives its text") {
    assertEquals(value(McpCallResult(Vector(McpContent.Text("hello")))), str("hello"))
  }

  test("more than one text part gives the list of the texts, with no other part") {
    assertEquals(
      value(McpCallResult(Vector(McpContent.Text("a"), image, McpContent.Text("b")))),
      strings("a", "b")
    )
  }

  test("a result with no text part gives the list of the other parts") {
    assertEquals(value(McpCallResult(Vector(image))), DynamicValue.Sequence(Chunk.single(imageJson)))
    assertEquals(value(McpCallResult(Vector.empty)), DynamicValue.Sequence(Chunk.empty))
  }

  test("a single empty text part gives the list of the other parts, as upstream") {
    assertEquals(
      value(McpCallResult(Vector(McpContent.Text(""), image))),
      DynamicValue.Sequence(Chunk.single(imageJson))
    )
  }

  test("a result that the server marks as an error is a Left with the text") {
    val single =
      McpTools.resultValue(McpCallResult(Vector(McpContent.Text("bad input")), isError = true), McpResultMode.Text)
    assertEquals(single.left.map(_.message), Left("Failed to call a MCP tool: bad input"))
    val several = McpTools.resultValue(
      McpCallResult(Vector(McpContent.Text("a"), McpContent.Text("b")), isError = true),
      McpResultMode.Structured
    )
    assertEquals(several.left.map(_.message), Left("""Failed to call a MCP tool: ["a", "b"]"""))
  }

  test("the structured mode gives the structured content, else the text") {
    val structured = DynamicValues.record("count" := 3)
    val result     = McpCallResult(Vector(McpContent.Text("3")), structuredContent = Some(structured))
    assertEquals(value(result, McpResultMode.Structured), structured)
    assertEquals(value(result, McpResultMode.Text), str("3"))
    assertEquals(value(result.copy(structuredContent = None), McpResultMode.Structured), str("3"))
  }

  test("a converted tool in the structured mode gives the structured content") {
    given RuntimeContext = RuntimeContext()
    val structured       = DynamicValues.record("count" := 3)
    val client           = StubClient(
      Vector(search),
      Right(McpCallResult(Vector(McpContent.Text("3")), structuredContent = Some(structured)))
    )
    assertEquals(
      McpTools.convert(client, search, McpResultMode.Structured).invoke(DynamicValue.Record.empty),
      Right(structured)
    )
  }

  // ── Native function calling ────────────────────────────────────────────────────────────────────────────────────

  test("the tool spec of an MCP tool has the flat parameters and the input schema") {
    val spec = McpTools.toolSpec(search)
    assertEquals(spec.name, "search")
    assertEquals(spec.description, Some("Search the web."))
    assertEquals(spec.inputSchema, Some(searchSchema))
    assertEquals(
      spec.parameters.take(2),
      Vector(
        ToolParameterSpec("query", TypeRef.string, Some("The query"), required = true),
        ToolParameterSpec("limit", TypeRef.int, None, required = false)
      )
    )
    assertEquals(spec.parameters.find(_.name == "filter").map(_.required), Some(true))
  }

  test("the provider tools of a spec with an input schema send that schema as the parameters") {
    val spec       = ToolSpec("search", Some("Search the web."), inputSchema = Some(searchSchema))
    val tools      = ToolSchemaBridge.toOpenAiToolsDynamic(Vector(spec))
    val parameters = tools match
      case DynamicValue.Sequence(elements) => elements.headOption.collect { case tool: DynamicValue.Record => tool }
          .flatMap(DynamicValues.recordGet(_, "function"))
          .collect { case function: DynamicValue.Record => function }
          .flatMap(DynamicValues.recordGet(_, "parameters"))
      case other => fail(s"not a list: $other")
    assertEquals(parameters, Some(searchSchema))
  }
