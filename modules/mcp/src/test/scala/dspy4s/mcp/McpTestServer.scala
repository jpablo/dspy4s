package dspy4s.mcp

import io.modelcontextprotocol.json.{McpJsonDefaults, McpJsonMapper, TypeRef}
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema

/** A small MCP server of the MCP Java SDK for [[McpSdkClientSuite]]. The suite starts it in a child JVM, and the server
  * reads its requests from stdin and writes its answers to stdout. Its tools:
  *   - `echo` gives the text `text`, `times` times (a text result);
  *   - `fail` gives a result that it marks as an error;
  *   - `count` gives the number of words of `text` as structured content, and the same value as text;
  *   - `pixel` gives an image.
  */
object McpTestServer:

  /** The keys are not in the order of a `HashMap`, so a test sees whether the client keeps the order of the server. */
  val echoSchema: String =
    """{"properties": {"text": {"type": "string", "description": "The text to echo"}, "times": {"type": "integer"}},
      | "required": ["text"], "type": "object"}""".stripMargin

  val countSchema: String = """{"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}"""

  val countOutputSchema: String = """{"type": "object",
      | "properties": {"count": {"type": "integer"}, "words": {"type": "array", "items": {"type": "string"}}},
      | "required": ["count", "words"]}""".stripMargin

  val emptySchema: String = """{"type": "object"}"""

  /** The base64 data of the image of `pixel`. */
  val pixelData: String = "aGk="

  def main(args: Array[String]): Unit =
    val mapper = McpJsonDefaults.getMapper()
    val _      = McpServer
      .sync(new StdioServerTransportProvider(mapper))
      .serverInfo("dspy4s-test-server", "1.0.0")
      .capabilities(McpSchema.ServerCapabilities.builder().tools(java.lang.Boolean.FALSE).build())
      .toolCall(
        tool(mapper, "echo", "Echo the text.", echoSchema),
        (_, request) =>
          val text  = stringArgument(request, "text")
          val times = argument(request, "times").collect { case n: java.lang.Number => n.intValue }
          McpSchema.CallToolResult.builder().addTextContent(Vector.fill(times.getOrElse(1))(text).mkString(" ")).build()
      )
      .toolCall(
        tool(mapper, "fail", "Fail with a message.", emptySchema),
        (_, _) =>
          McpSchema.CallToolResult.builder().addTextContent("the input is bad").isError(java.lang.Boolean.TRUE).build()
      )
      .toolCall(
        McpSchema.Tool
          .builder("count", jsonObject(mapper, countSchema))
          .description("Count the words of the text.")
          .outputSchema(jsonObject(mapper, countOutputSchema))
          .build(),
        (_, request) =>
          val words      = stringArgument(request, "text").split(' ').toVector.filter(_.nonEmpty)
          val structured = new java.util.LinkedHashMap[String, AnyRef]()
          val _          = structured.put("count", java.lang.Integer.valueOf(words.size))
          val _          = structured.put("words", java.util.List.of(words*))
          McpSchema.CallToolResult
            .builder()
            .addTextContent(words.size.toString)
            .structuredContent(structured)
            .build()
      )
      .toolCall(
        tool(mapper, "pixel", "Draw a pixel.", emptySchema),
        (_, _) =>
          McpSchema.CallToolResult
            .builder()
            .addContent(McpSchema.ImageContent.builder(pixelData, "image/png").build())
            .build()
      )
      .build()
    // The threads of the transport keep the JVM alive. The client stops the process when it closes.

  private def tool(mapper: McpJsonMapper, name: String, description: String, schema: String): McpSchema.Tool =
    McpSchema.Tool.builder(name, jsonObject(mapper, schema)).description(description).build()

  /** The JSON object of `json` with the keys in the order of the text. (`Tool.builder(name, mapper, json)` of the SDK
    * reads the schema into a `HashMap`, so the server would send the keys in an other order.)
    */
  private def jsonObject(mapper: McpJsonMapper, json: String): java.util.Map[String, AnyRef] =
    mapper.readValue(json, new TypeRef[java.util.LinkedHashMap[String, AnyRef]] {})

  private def argument(request: McpSchema.CallToolRequest, name: String): Option[AnyRef] =
    Option(request.arguments()).flatMap(arguments => Option(arguments.get(name)))

  private def stringArgument(request: McpSchema.CallToolRequest, name: String): String =
    argument(request, name).fold("")(String.valueOf)
