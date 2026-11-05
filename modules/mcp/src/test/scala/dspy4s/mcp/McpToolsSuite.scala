package dspy4s.mcp

import dspy4s.adapters.contracts.ToolSchemaBridge
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.TypeRef
import dspy4s.programs.contracts.ToolFunction
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

  private val searchSchema = json(
    """{"type": "object", "properties": {"query": {"type": "string", "description": "The query"}, "limit": {"type": "integer"}, "filter": {"$ref": "#/$defs/Filter"}, "tags": {"type": "array", "items": {"type": "string"}}, "exact": {"type": "boolean"}, "score": {"type": "number"}, "anything": {}}, "required": ["query", "filter"], "$defs": {"Filter": {"type": "object", "properties": {"lang": {"type": "string"}}}}}"""
  )

  /** The schema of the tool `search` for the model: the `$ref` to `Filter` is expanded, and `$defs` goes. */
  private val searchObjectSchema = json(
    """{"type": "object", "properties": {"query": {"type": "string", "description": "The query"}, "limit": {"type": "integer"}, "filter": {"type": "object", "properties": {"lang": {"type": "string"}}}, "tags": {"type": "array", "items": {"type": "string"}}, "exact": {"type": "boolean"}, "score": {"type": "number"}, "anything": {}}, "required": ["query", "filter"]}"""
  )

  private val search = McpToolInfo("search", Some("Search the web."), searchSchema)

  /** A client that keeps each call and answers with `result`. */
  private final class StubClient(
      tools : Vector[McpToolInfo],
      result: Either[DspyError, McpCallResult] = Right(McpCallResult(Vector("ok")))
  ) extends McpToolClient:
    @volatile var calls: Vector[(String, DynamicValue.Record)]                                            = Vector.empty
    override def listTools(): Either[DspyError, Vector[McpToolInfo]]                                      = Right(tools)
    override def callTool(name: String, arguments: DynamicValue.Record): Either[DspyError, McpCallResult] =
      calls = calls :+ (name -> arguments)
      result

  private val imageJson = json("""{"type": "image", "data": "aGk=", "mimeType": "image/png"}""")

  test("a converted tool has the name, the description, and the object schema of the MCP tool") {
    val tool = McpTools.convert(StubClient(Vector(search)), search)
    assertEquals(tool.name, "search")
    assertEquals(tool.description, "Search the web.")
    assertEquals(tool.inputSchema, Some(searchObjectSchema))
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
    val client           = StubClient(Vector(search), Right(McpCallResult(Vector("3 results"))))
    val args             = DynamicValues.record("query" := "cats", "limit" := 3)
    assertEquals(McpTools.convert(client, search).invoke(args), Right(DynamicValues.string("3 results")))
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
    assertEquals(value(McpCallResult(Vector("hello"))), DynamicValues.string("hello"))
  }

  test("more than one text part gives the list of the texts, with no other part") {
    assertEquals(
      value(McpCallResult(Vector("a", "b"), Vector(imageJson))),
      DynamicValues.sequence(Vector("a", "b").map(DynamicValues.string))
    )
  }

  test("a result with no text part gives the list of the other parts") {
    assertEquals(value(McpCallResult(Vector.empty, Vector(imageJson))), DynamicValues.sequence(Vector(imageJson)))
    assertEquals(value(McpCallResult(Vector.empty)), DynamicValues.sequence(Vector.empty))
  }

  test("a single empty text part gives the list of the other parts, as upstream") {
    assertEquals(value(McpCallResult(Vector(""), Vector(imageJson))), DynamicValues.sequence(Vector(imageJson)))
  }

  test("a result that the server marks as an error is a Left with the text") {
    val single = McpTools.resultValue(McpCallResult(Vector("bad input"), isError = true), McpResultMode.Text)
    assertEquals(single.left.map(_.message), Left("Failed to call a MCP tool: bad input"))
    val several = McpTools.resultValue(McpCallResult(Vector("a", "b"), isError = true), McpResultMode.Structured)
    assertEquals(several.left.map(_.message), Left("""Failed to call a MCP tool: ["a", "b"]"""))
  }

  test("the structured mode gives the structured content, else the text") {
    val structured = DynamicValues.record("count" := 3)
    val result     = McpCallResult(Vector("3"), structuredContent = Some(structured))
    assertEquals(value(result, McpResultMode.Structured), structured)
    assertEquals(value(result, McpResultMode.Text), DynamicValues.string("3"))
    assertEquals(value(result.copy(structuredContent = None), McpResultMode.Structured), DynamicValues.string("3"))
  }

  test("a converted tool in the structured mode gives the structured content") {
    given RuntimeContext = RuntimeContext()
    val structured       = DynamicValues.record("count" := 3)
    val client           = StubClient(
      Vector(search),
      Right(McpCallResult(Vector("3"), structuredContent = Some(structured)))
    )
    assertEquals(
      McpTools.convert(client, search, McpResultMode.Structured).invoke(DynamicValue.Record.empty),
      Right(structured)
    )
  }

  test("an error result with a lone surrogate in one of several text parts is a Left, not an exception") {
    val result = McpCallResult(Vector("a", "\ud83d"), isError = true)
    assert(McpTools.resultValue(result, McpResultMode.Text).isLeft)
  }

  test("the structured mode gives each JSON value of the structured content, also a null and an empty value") {
    Vector[DynamicValue](
      DynamicValue.Null,
      DynamicValue.Record.empty,
      DynamicValue.Sequence(Chunk.empty),
      DynamicValues.string(""),
      DynamicValue.Primitive(PrimitiveValue.Int(0)),
      DynamicValue.Primitive(PrimitiveValue.Boolean(false))
    ).foreach { structured =>
      val result = McpCallResult(Vector("fallback"), structuredContent = Some(structured))
      assertEquals(value(result, McpResultMode.Structured), structured)
    }
  }

  // ── The schema of a converted tool ─────────────────────────────────────────────────────────────────────────────

  /** The expected `properties` come from upstream `convert_input_schema_to_tool_args` on this schema (dspy 3.4.0). */
  private def accountInfo =
    McpToolInfo(
      "open_account",
      None,
      json(
        """{"type": "object", "title": "t", "$schema": "x", "additionalProperties": false, "properties": {"account": {"$ref": "#/$defs/Account", "description": "dropped"}, "maybe": {"anyOf": [{"$ref": "#/$defs/Profile"}, {"type": "null"}], "default": null}, "many": {"type": "array", "items": {"$ref": "#/$defs/Profile"}}, "q": {"type": "string", "description": "The query"}}, "required": ["account", "q"], "$defs": {"Profile": {"title": "Profile", "type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}, "Account": {"title": "Account", "type": "object", "properties": {"profile": {"$ref": "#/$defs/Profile"}, "id": {"type": "string"}}, "required": ["profile", "id"]}}}"""
      )
    )

  private def accountParameters =
    json(
      """{"type": "object", "properties": {"account": {"title": "Account", "type": "object", "properties": {"profile": {"title": "Profile", "type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}, "id": {"type": "string"}}, "required": ["profile", "id"]}, "maybe": {"anyOf": [{"title": "Profile", "type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}, {"type": "null"}], "default": null}, "many": {"type": "array", "items": {"title": "Profile", "type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}, "q": {"type": "string", "description": "The query"}}, "required": ["account", "q"]}"""
    )

  private def converted(schema: String): ToolFunction =
    val info = McpToolInfo("tool", None, json(schema))
    McpTools.convert(StubClient(Vector(info)), info)

  test("the schema of a converted tool has each $ref expanded at each depth, and only the upstream keys") {
    val tool = McpTools.convert(StubClient(Vector(accountInfo)), accountInfo)
    assertEquals(tool.inputSchema, Some(accountParameters))
    assertEquals(
      tool.argSchema,
      Vector(
        "account" -> TypeRef.json,
        "maybe"   -> TypeRef("Any"),
        "many"    -> TypeRef.list,
        "q"       -> TypeRef.string
      )
    )
  }

  test("the tool spec has the same schema as the converted tool") {
    assertEquals(McpTools.toolSpec(accountInfo).inputSchema, accountParameters)
  }

  test("a tool with no properties has empty properties and an empty required list, as upstream") {
    val empty = json("""{"type": "object", "properties": {}, "required": []}""")
    assertEquals(converted("""{"type": "object"}""").inputSchema, Some(empty))
    assertEquals(converted("""{}""").inputSchema, Some(empty))
  }

  test("a recursive definition ends with the schema of any value") {
    val tool = converted(
      """{"type": "object", "properties": {"node": {"$ref": "#/$defs/Node"}}, "$defs": {"Node": {"type": "object", "properties": {"next": {"$ref": "#/$defs/Node"}}}}}"""
    )
    assertEquals(
      tool.inputSchema,
      Some(json(
        """{"type": "object", "properties": {"node": {"type": "object", "properties": {"next": {}}}}, "required": []}"""
      ))
    )
    assertEquals(tool.argSchema, Vector("node" -> TypeRef.json))
  }

  test("a $ref with no definition is the schema of any value, when the schema has definitions") {
    val tool = converted(
      """{"type": "object", "properties": {"x": {"$ref": "#/$defs/Missing"}}, "$defs": {"Other": {"type": "string"}}}"""
    )
    assertEquals(
      tool.inputSchema,
      Some(json("""{"type": "object", "properties": {"x": {}}, "required": []}"""))
    )
    assertEquals(tool.argSchema, Vector("x" -> TypeRef("Any")))
  }

  test("a schema with no definitions keeps a $ref, as upstream") {
    val tool = converted("""{"type": "object", "properties": {"x": {"$ref": "#/properties/y", "description": "d"}}}""")
    assertEquals(
      tool.inputSchema,
      Some(json(
        """{"type": "object", "properties": {"x": {"$ref": "#/properties/y", "description": "d"}}, "required": []}"""
      ))
    )
    assertEquals(tool.argSchema, Vector("x" -> TypeRef("Any")))
  }

  // ── Native function calling ────────────────────────────────────────────────────────────────────────────────────

  test("the tool spec of an MCP tool has the name, the description, and the object schema") {
    val spec = McpTools.toolSpec(search)
    assertEquals(spec.name, "search")
    assertEquals(spec.description, Some("Search the web."))
    assertEquals(spec.inputSchema, searchObjectSchema)
  }

  test("the provider tools of a spec with an input schema send that schema as the parameters") {
    val spec       = ToolSpec("search", Some("Search the web."), searchSchema)
    val tools      = ToolSchemaBridge.toOpenAiToolsDynamic(Vector(spec))
    val parameters = tools match
      case DynamicValue.Sequence(elements) => elements.headOption.collect { case tool: DynamicValue.Record => tool }
          .flatMap(DynamicValues.recordGet(_, "function"))
          .collect { case function: DynamicValue.Record => function }
          .flatMap(DynamicValues.recordGet(_, "parameters"))
      case other => fail(s"not a list: $other")
    assertEquals(parameters, Some(searchSchema))
  }
