/** Model Context Protocol (MCP)
  *
  * Source: docs/docs/learn/programming/mcp.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/mcp.md Status: translated (5/5 snippets,
  * upgrade item I10, PORT_GAPS G-47). The `dspy4s-mcp` module converts the tools of an MCP server to `ToolFunction`s
  * over a client of the MCP Java SDK: `McpTools.fromClient` and `McpTools.convert` are the analogues of
  * `dspy.Tool.from_mcp_tool`. The snippets are the snippets of upstream f1260ff14.
  *
  * The differences: the Java SDK has one client API, so the SDK v1 and SDK v2 forms of Python (snippets 1 and 2) are
  * the same code here. A tool call blocks until the server answers, so there is no `acall` and no `asyncio`. The server
  * of snippet 3 uses the server API of the Java SDK in place of FastMCP. ReActV2 is not ported (PORT_GAPS G-19), so the
  * agent of snippet 4 is a `ReAct`.
  */
package dspy4s.examples.learn.programming

import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, NotFoundError, RuntimeContext, RuntimeError}
import dspy4s.examples.Demo
import dspy4s.mcp.{McpResultMode, McpToolClient, McpToolInfo, McpTools}
import dspy4s.programs.IterationLimit
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.strategies.ReAct
import dspy4s.signatures.{InputField, OutputField, Signature, Spec}
import io.modelcontextprotocol.client.{McpClient, McpSyncClient}
import io.modelcontextprotocol.client.transport.{
  HttpClientStreamableHttpTransport, ServerParameters, StdioClientTransport
}
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.{McpServer, McpSyncServer}
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.{McpClientTransport, McpSchema}
import zio.blocks.schema.DynamicValue

import java.nio.file.Paths
import scala.util.Using

// Snippet 2 signature (top-level for Mirror derivation).
// | class TaskSignature(dspy.Signature):
// |     task: str = dspy.InputField()
// |     result: str = dspy.OutputField()
trait TaskSignature extends Spec:
  def task: InputField[String]
  def result: OutputField[String]

// Snippet 4 signature.
// | class QuestionAnswer(dspy.Signature):
// |     """Answer questions using available tools."""
// |     question: str = dspy.InputField()
// |     answer: int = dspy.OutputField()
trait QuestionAnswer extends Spec:
  def question: InputField[String]
  def answer: OutputField[Int]

object Mcp:

  /** Open a client on `transport`, initialize it, run `body`, and close the client. A converted tool uses the open
    * client for each call, so the agent must run in `body` (upstream: "Keep the agent call inside the open MCP
    * session"). A failure to connect is a `Left`.
    */
  def withClient[A](transport: McpClientTransport)(body: McpSyncClient => Either[DspyError, A]): Either[DspyError, A] =
    Using(McpClient.sync(transport).build()) { client =>
      val _ = client.initialize()
      body(client)
    }.toEither.left.map[DspyError](error => RuntimeError("mcp", s"MCP client failed: ${error.getMessage}")).flatten

  // ── Snippet 1 (lines 33–44): the tools of a remote server ──
  // | import asyncio
  // | import dspy
  // | from mcp.client import Client
  // |
  // | async def main():
  // |     async with Client("http://localhost:8000/mcp") as client:
  // |         response = await client.list_tools()
  // |         dspy_tools = [dspy.Tool.from_mcp_tool(client, tool) for tool in response.tools]
  // |
  // | asyncio.run(main())
  //
  // The streamable HTTP transport of the Java SDK takes the base URI and the endpoint (`/mcp` by default).
  // --8<-- [start:http-tools]
  def httpTransport(baseUri: String = "http://localhost:8000", endpoint: String = "/mcp"): McpClientTransport =
    HttpClientStreamableHttpTransport.builder(baseUri).endpoint(endpoint).build()

  def remoteToolNames(): Either[DspyError, Vector[String]] =
    withClient(httpTransport()) { client =>
      McpTools.fromClient(McpToolClient.fromSync(client)).map(_.map(_.name))
    }
  // --8<-- [end:http-tools]

  // ── Snippet 2 (lines 48–83): a ReAct agent over the tools of a remote server (MCP SDK v1) ──
  // | import asyncio
  // | import dspy
  // | from mcp import ClientSession
  // | from mcp.client.streamable_http import streamablehttp_client
  // |
  // | async def main():
  // |     # Connect to HTTP MCP server
  // |     async with streamablehttp_client("http://localhost:8000/mcp") as (read, write):
  // |         async with ClientSession(read, write) as session:
  // |             # Initialize the session
  // |             await session.initialize()
  // |
  // |             # List and convert tools
  // |             response = await session.list_tools()
  // |             dspy_tools = [
  // |                 dspy.Tool.from_mcp_tool(session, tool)
  // |                 for tool in response.tools
  // |             ]
  // |
  // |             # Create and use ReAct agent
  // |             class TaskSignature(dspy.Signature):
  // |                 task: str = dspy.InputField()
  // |                 result: str = dspy.OutputField()
  // |
  // |             react_agent = dspy.ReAct(
  // |                 signature=TaskSignature,
  // |                 tools=dspy_tools,
  // |                 max_iters=5
  // |             )
  // |
  // |             result = await react_agent.acall(task="Check the weather in Tokyo")
  // |             print(result.result)
  // |
  // | asyncio.run(main())
  // --8<-- [start:http-react]
  def checkWeather(task: String = "Check the weather in Tokyo")(using RuntimeContext): Either[DspyError, String] =
    withClient(httpTransport()) { client =>
      McpTools.fromClient(McpToolClient.fromSync(client)).flatMap { tools =>
        val reactAgent = ReAct(
          baseSignature = Signature.of[TaskSignature],
          tools = tools,
          maxIterations = IterationLimit(5)
        )
        reactAgent((task = task)).map(_.output.result)
      }
    }
  // --8<-- [end:http-react]

  // ── Snippet 3 (lines 91–106): an arithmetic server over stdio ──
  // | try:
  // |     from mcp.server.fastmcp import FastMCP as MCPServer
  // | except ImportError:
  // |     from mcp.server import MCPServer
  // |
  // | server = MCPServer("arithmetic")
  // |
  // | @server.tool()
  // | def add(a: int, b: int) -> int:
  // |     """Add two integers."""
  // |     return a + b
  // |
  // | if __name__ == "__main__":
  // |     server.run()
  //
  // FastMCP derives the input schema from the type hints. The server API of the Java SDK takes the schema as JSON.
  // `mcpArithmeticServer` (below) is the `__main__` part.
  // --8<-- [start:arithmetic-server]
  val addSchema: String =
    """{"type": "object", "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}}, "required": ["a", "b"]}"""

  def arithmeticServer(): McpSyncServer =
    val mapper = McpJsonDefaults.getMapper()
    McpServer
      .sync(new StdioServerTransportProvider(mapper))
      .serverInfo("arithmetic", "1.0.0")
      .capabilities(McpSchema.ServerCapabilities.builder().tools(java.lang.Boolean.FALSE).build())
      .toolCall(
        McpSchema.Tool.builder("add", mapper, addSchema).description("Add two integers.").build(),
        (_, request) =>
          def integer(name: String): Long =
            Option(request.arguments()).flatMap(arguments => Option(arguments.get(name))).collect {
              case number: java.lang.Number => number.longValue
            }.getOrElse(0L)
          McpSchema.CallToolResult.builder().addTextContent((integer("a") + integer("b")).toString).build()
      )
      .build()
  // --8<-- [end:arithmetic-server]

  // ── Snippet 4 (lines 110–166): a ReActV2 agent over the arithmetic server ──
  // | import asyncio
  // | import sys
  // | from pathlib import Path
  // |
  // | import dspy
  // | from mcp import ClientSession, StdioServerParameters
  // | from mcp.client.stdio import stdio_client
  // |
  // | dspy.configure(
  // |     lm=dspy.LM("openai/gpt-4.1-mini"),
  // |     adapter=dspy.ChatAdapter(use_native_function_calling=True),
  // | )
  // |
  // | async def main():
  // |     # Configure the stdio server
  // |     server_params = StdioServerParameters(
  // |         command=sys.executable,
  // |         args=[str(Path(__file__).with_name("mcp_server.py"))],
  // |     )
  // |
  // |     # Connect to the server
  // |     async with stdio_client(server_params) as (read, write):
  // |         async with ClientSession(read, write) as session:
  // |             # Initialize the session
  // |             await session.initialize()
  // |
  // |             # List available tools
  // |             response = await session.list_tools()
  // |
  // |             # Convert MCP tools to DSPy tools
  // |             dspy_tools = [
  // |                 dspy.Tool.from_mcp_tool(session, tool)
  // |                 for tool in response.tools
  // |             ]
  // |
  // |             # Create a ReActV2 agent with the tools
  // |             class QuestionAnswer(dspy.Signature):
  // |                 """Answer questions using available tools."""
  // |                 question: str = dspy.InputField()
  // |                 answer: int = dspy.OutputField()
  // |
  // |             react_agent = dspy.ReActV2(
  // |                 signature=QuestionAnswer,
  // |                 tools=dspy_tools,
  // |                 max_iters=5
  // |             )
  // |
  // |             # Use the agent
  // |             result = await react_agent.acall(
  // |                 question="Use the add tool to calculate 25 + 17."
  // |             )
  // |             print(result.answer)
  // |
  // | # Run the async function
  // | asyncio.run(main())
  //
  // The client starts the server of snippet 3 in a child JVM (`sys.executable` becomes the `java` of this JVM) and
  // stops it when it closes. ReActV2 calls the tools through native function calling. It is not ported (PORT_GAPS
  // G-19), and the `ReAct` of dspy4s uses the text protocol (G-7b), so the adapter keeps its default here.
  // `Demo.withLm` (in `mcpMain`) is the `dspy.configure(lm=...)` part.
  // --8<-- [start:stdio-react]
  def stdioTransport(classPath: String): McpClientTransport =
    val javaBin    = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val parameters = ServerParameters
      .builder(javaBin)
      .args("-cp", classPath, "dspy4s.examples.learn.programming.mcpArithmeticServer")
      .build()
    new StdioClientTransport(parameters, McpJsonDefaults.getMapper())

  def addWithAgent(classPath: String)(using RuntimeContext): Either[DspyError, Int] =
    withClient(stdioTransport(classPath)) { client =>
      McpTools.fromClient(McpToolClient.fromSync(client)).flatMap { tools =>
        val reactAgent = ReAct(
          baseSignature = Signature.of[QuestionAnswer](instructions = "Answer questions using available tools."),
          tools = tools,
          maxIterations = IterationLimit(5)
        )
        reactAgent((question = "Use the add tool to calculate 25 + 17.")).map(_.output.answer)
      }
    }
  // --8<-- [end:stdio-react]

  // ── Snippet 5 (lines 174–189): the conversion of one tool ──
  // | # MCP tool from session
  // | mcp_tool = response.tools[0]
  // |
  // | # Convert to DSPy tool
  // | dspy_tool = dspy.Tool.from_mcp_tool(session, mcp_tool)
  // |
  // | # The DSPy tool preserves:
  // | # - Tool name and description
  // | # - Parameter schemas and types
  // | # - Argument descriptions
  // | # - Async execution support
  // |
  // | # Use it like any DSPy tool
  // | result = await dspy_tool.acall(param1="value", param2=123)
  //
  // The converted tool keeps the name, the description, the input schema of the MCP tool (`inputSchema`), and the type
  // of each argument (`argSchema`). `invoke` blocks, so there is no async form. `McpTools.toolSpec` gives the tool to a
  // model with native function calling, with the full input schema and the description of each argument.
  // --8<-- [start:tool-conversion]
  def callFirstTool(client: McpSyncClient)(using RuntimeContext): Either[DspyError, DynamicValue] =
    val session = McpToolClient.fromSync(client)
    session.listTools().flatMap { response =>
      response.headOption.toRight(NotFoundError("mcp_tool", "The server has no tool.")).flatMap { mcpTool =>
        val dspyTool = McpTools.convert(session, mcpTool)
        dspyTool.invoke(DynamicValues.record("param1" := "value", "param2" := 123))
      }
    }

  def nativeToolSpec(mcpTool: McpToolInfo): ToolSpec = McpTools.toolSpec(mcpTool)
  // --8<-- [end:tool-conversion]

  // By default, a converted tool gives the `content` of the result. `result_mode="structured"` is
  // `McpResultMode.Structured`: the tool gives the `structuredContent` of the result when the server sends one, and
  // else the default value.
  // --8<-- [start:structured]
  def structuredTool(session: McpToolClient, mcpTool: McpToolInfo): ToolFunction =
    McpTools.convert(session, mcpTool, McpResultMode.Structured)
  // --8<-- [end:structured]

/** The server of snippet 3 (`if __name__ == "__main__": server.run()`). The threads of the stdio transport keep the JVM
  * alive. The client of snippet 4 starts this main and stops it.
  */
@main def mcpArithmeticServer(): Unit =
  val _ = Mcp.arithmeticServer()

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.mcpMain <class path>"
// The agent starts `mcpArithmeticServer` in a child JVM with <class path>, the output of
// `sbt -error "export examples/Runtime/fullClasspath"`. (Under `sbt run`, the class path of the JVM is the class path
// of sbt, so the agent cannot use it.)
@main def mcpMain(classPath: String): Unit =
  Demo.withLm {
    println("25 + 17 = " + Mcp.addWithAgent(classPath))
  }
