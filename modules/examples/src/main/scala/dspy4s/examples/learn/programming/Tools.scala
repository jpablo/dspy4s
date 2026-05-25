/**
 * Tools
 *
 * Source:   docs/docs/learn/programming/tools.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/tools.md
 * Status:   scaffold (9 python snippets — TODO translate)
 */
package dspy4s.examples.learn.programming

object Tools {

  // ── Snippet 1 (lines 20–45) ────────────────────
  // | import dspy
  // |
  // | # Define your tools as functions
  // | def get_weather(city: str) -> str:
  // |     """Get the current weather for a city."""
  // |     # In a real implementation, this would call a weather API
  // |     return f"The weather in {city} is sunny and 75°F"
  // |
  // | def search_web(query: str) -> str:
  // |     """Search the web for information."""
  // |     # In a real implementation, this would call a search API
  // |     return f"Search results for '{query}': [relevant information...]"
  // |
  // | # Create a ReAct agent
  // | react_agent = dspy.ReAct(
  // |     signature="question -> answer",
  // |     tools=[get_weather, search_web],
  // |     max_iters=5
  // | )
  // |
  // | # Use the agent
  // | result = react_agent(question="What's the weather like in Tokyo?")
  // | print(result.answer)
  // | print("Tool calls made:", result.trajectory)
  // TODO translate snippet 1

  // ── Snippet 2 (lines 57–63) ────────────────────
  // | react_agent = dspy.ReAct(
  // |     signature="question -> answer",  # Input/output specification
  // |     tools=[tool1, tool2, tool3],     # List of available tools
  // |     max_iters=10                     # Maximum number of tool call iterations
  // | )
  // TODO translate snippet 2

  // ── Snippet 3 (lines 74–118) ────────────────────
  // | import dspy
  // |
  // | class ToolSignature(dspy.Signature):
  // |     """SignatureLayout for manual tool handling."""
  // |     question: str = dspy.InputField()
  // |     tools: list[dspy.Tool] = dspy.InputField()
  // |     outputs: dspy.ToolCalls = dspy.OutputField()
  // |
  // | def weather(city: str) -> str:
  // |     """Get weather information for a city."""
  // |     return f"The weather in {city} is sunny"
  // |
  // | def calculator(expression: str) -> str:
  // |     """Evaluate a mathematical expression."""
  // |     try:
  // |         result = eval(expression)  # Note: Use safely in production
  // |         return f"The result is {result}"
  // |     except:
  // |         return "Invalid expression"
  // |
  // | # Create tool instances
  // | tools = {
  // |     "weather": dspy.Tool(weather),
  // |     "calculator": dspy.Tool(calculator)
  // | }
  // |
  // | # Create predictor
  // | predictor = dspy.Predict(ToolSignature)
  // |
  // | # Make a prediction
  // | response = predictor(
  // |     question="What's the weather in New York?",
  // |     tools=list(tools.values())
  // | )
  // |
  // | # Execute the tool calls
  // | for call in response.outputs.tool_calls:
  // |     # Execute the tool call
  // |     result = call.execute()
  // |     # For versions earlier than 3.0.4b2, use: result = tools[call.name](**call.args)
  // |     print(f"Tool: {call.name}")
  // |     print(f"Args: {call.args}")
  // |     print(f"Result: {result}")
  // TODO translate snippet 3

  // ── Snippet 4 (lines 124–137) ────────────────────
  // | def my_function(param1: str, param2: int = 5) -> str:
  // |     """A sample function with parameters."""
  // |     return f"Processed {param1} with value {param2}"
  // |
  // | # Create a tool
  // | tool = dspy.Tool(my_function)
  // |
  // | # Tool properties
  // | print(tool.name)        # "my_function"
  // | print(tool.desc)        # The function's docstring
  // | print(tool.args)        # Parameter schema
  // | print(str(tool))        # Full tool description
  // TODO translate snippet 4

  // ── Snippet 5 (lines 146–168) ────────────────────
  // | # After getting a response with tool calls
  // | for call in response.outputs.tool_calls:
  // |     print(f"Tool name: {call.name}")
  // |     print(f"Arguments: {call.args}")
  // |
  // |     # Execute individual tool calls with different options:
  // |
  // |     # Option 1: Automatic discovery (finds functions in locals/globals)
  // |     result = call.execute()  # Automatically finds functions by name
  // |
  // |     # Option 2: Pass tools as a dict (most explicit)
  // |     result = call.execute(functions={"weather": weather, "calculator": calculator})
  // |
  // |     # Option 3: Pass Tool objects as a list
  // |     result = call.execute(functions=[dspy.Tool(weather), dspy.Tool(calculator)])
  // |
  // |     # Option 4: For versions earlier than 3.0.4b2 (manual tool lookup)
  // |     # tools_dict = {"weather": weather, "calculator": calculator}
  // |     # result = tools_dict[call.name](**call.args)
  // |
  // |     print(f"Result: {result}")
  // TODO translate snippet 5

  // ── Snippet 6 (lines 191–202) ────────────────────
  // | import dspy
  // |
  // | # ChatAdapter with native function calling enabled
  // | chat_adapter_native = dspy.ChatAdapter(use_native_function_calling=True)
  // |
  // | # JSONAdapter with native function calling disabled
  // | json_adapter_manual = dspy.JSONAdapter(use_native_function_calling=False)
  // |
  // | # Configure DSPy to use the adapter
  // | dspy.configure(lm=dspy.LM(model="openai/gpt-4o"), adapter=chat_adapter_native)
  // TODO translate snippet 6

  // ── Snippet 7 (lines 224–238) ────────────────────
  // | import asyncio
  // | import dspy
  // |
  // | async def async_weather(city: str) -> str:
  // |     """Get weather information asynchronously."""
  // |     await asyncio.sleep(0.1)  # Simulate async API call
  // |     return f"The weather in {city} is sunny"
  // |
  // | tool = dspy.Tool(async_weather)
  // |
  // | # Use acall for async tools
  // | result = await tool.acall(city="New York")
  // | print(result)
  // TODO translate snippet 7

  // ── Snippet 8 (lines 244–260) ────────────────────
  // | import asyncio
  // | import dspy
  // |
  // | async def async_weather(city: str) -> str:
  // |     """Get weather information asynchronously."""
  // |     await asyncio.sleep(0.1)
  // |     return f"The weather in {city} is sunny"
  // |
  // | tool = dspy.Tool(async_weather)
  // |
  // | # Enable async-to-sync conversion
  // | with dspy.context(allow_tool_async_sync_conversion=True):
  // |     # Now you can use __call__ on async tools
  // |     result = tool(city="New York")
  // |     print(result)
  // TODO translate snippet 8

  // ── Snippet 9 (lines 270–288) ────────────────────
  // | def good_tool(city: str, units: str = "celsius") -> str:
  // |     """
  // |     Get weather information for a specific city.
  // |
  // |     Args:
  // |         city: The name of the city to get weather for
  // |         units: Temperature units, either 'celsius' or 'fahrenheit'
  // |
  // |     Returns:
  // |         A string describing the current weather conditions
  // |     """
  // |     # Implementation with proper error handling
  // |     if not city.strip():
  // |         return "Error: City name cannot be empty"
  // |
  // |     # Weather logic here...
  // |     return f"Weather in {city}: 25°{units[0].upper()}, sunny"
  // TODO translate snippet 9
}
