/**
 * Async DSPy Programming
 *
 * Source:   docs/docs/tutorials/async/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/async/index.md
 * Status:   scaffold (4 python snippets — TODO translate)
 */
package dspy4s.examples.tutorials.async

object Async {

  // ── Snippet 1 (lines 55–72) ────────────────────
  // | import dspy
  // | import asyncio
  // | import os
  // |
  // | os.environ["OPENAI_API_KEY"] = "your_api_key"
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // | predict = dspy.Predict("question->answer")
  // |
  // | async def main():
  // |     # Use acall() for async execution
  // |     output = await predict.acall(question="why did a chicken cross the kitchen?")
  // |     print(output)
  // |
  // |
  // | asyncio.run(main())
  // TODO translate snippet 1

  // ── Snippet 2 (lines 80–100) ────────────────────
  // | import asyncio
  // | import dspy
  // | import os
  // |
  // | os.environ["OPENAI_API_KEY"] = "your_api_key"
  // |
  // | async def foo(x):
  // |     # Simulate an async operation
  // |     await asyncio.sleep(0.1)
  // |     print(f"I get: {x}")
  // |
  // | # Create a tool from the async function
  // | tool = dspy.Tool(foo)
  // |
  // | async def main():
  // |     # Execute the tool asynchronously
  // |     await tool.acall(x=2)
  // |
  // | asyncio.run(main())
  // TODO translate snippet 2

  // ── Snippet 3 (lines 106–125) ────────────────────
  // | import dspy
  // |
  // | async def async_tool(x: int) -> int:
  // |     """An async tool that doubles a number."""
  // |     await asyncio.sleep(0.1)
  // |     return x * 2
  // |
  // | tool = dspy.Tool(async_tool)
  // |
  // | # Option 1: Use context manager for temporary conversion
  // | with dspy.context(allow_tool_async_sync_conversion=True):
  // |     result = tool(x=5)  # Works in sync context
  // |     print(result)  # 10
  // |
  // | # Option 2: Configure globally
  // | dspy.configure(allow_tool_async_sync_conversion=True)
  // | result = tool(x=5)  # Now works everywhere
  // | print(result)  # 10
  // TODO translate snippet 3

  // ── Snippet 4 (lines 137–163) ────────────────────
  // | import dspy
  // | import asyncio
  // | import os
  // |
  // | os.environ["OPENAI_API_KEY"] = "your_api_key"
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // |
  // | class MyModule(dspy.Module):
  // |     def __init__(self):
  // |         self.predict1 = dspy.ChainOfThought("question->answer")
  // |         self.predict2 = dspy.ChainOfThought("answer->simplified_answer")
  // |
  // |     async def aforward(self, question, **kwargs):
  // |         # Execute predictions sequentially but asynchronously
  // |         answer = await self.predict1.acall(question=question)
  // |         return await self.predict2.acall(answer=answer)
  // |
  // |
  // | async def main():
  // |     mod = MyModule()
  // |     result = await mod.acall(question="Why did a chicken cross the kitchen?")
  // |     print(result)
  // |
  // |
  // | asyncio.run(main())
  // TODO translate snippet 4
}
