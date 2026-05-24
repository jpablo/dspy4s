/**
 * Model Context Protocol (MCP)
 *
 * Source:   docs/docs/learn/programming/mcp.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/mcp.md
 * Status:   scaffold (3 python snippets — TODO translate)
 */
package dspy4s.examples.learn.programming

object Mcp {

  // ── Snippet 1 (lines 33–68) ────────────────────
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
  // TODO translate snippet 1

  // ── Snippet 2 (lines 74–123) ────────────────────
  // | import asyncio
  // | import dspy
  // | from mcp import ClientSession, StdioServerParameters
  // | from mcp.client.stdio import stdio_client
  // |
  // | async def main():
  // |     # Configure the stdio server
  // |     server_params = StdioServerParameters(
  // |         command="python",                    # Command to run
  // |         args=["path/to/your/mcp_server.py"], # Server script path
  // |         env=None,                            # Optional environment variables
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
  // |             # Create a ReAct agent with the tools
  // |             class QuestionAnswer(dspy.Signature):
  // |                 """Answer questions using available tools."""
  // |                 question: str = dspy.InputField()
  // |                 answer: str = dspy.OutputField()
  // |
  // |             react_agent = dspy.ReAct(
  // |                 signature=QuestionAnswer,
  // |                 tools=dspy_tools,
  // |                 max_iters=5
  // |             )
  // |
  // |             # Use the agent
  // |             result = await react_agent.acall(
  // |                 question="What is 25 + 17?"
  // |             )
  // |             print(result.answer)
  // |
  // | # Run the async function
  // | asyncio.run(main())
  // TODO translate snippet 2

  // ── Snippet 3 (lines 129–144) ────────────────────
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
  // TODO translate snippet 3
}
