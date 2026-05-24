/**
 * Streaming
 *
 * Source:   docs/docs/tutorials/streaming/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/streaming/index.md
 * Status:   scaffold (9 python snippets — TODO translate)
 */
package dspy4s.examples.tutorials.streaming

object Streaming {

  // ── Snippet 1 (lines 18–34) ────────────────────
  // | import os
  // |
  // | import dspy
  // |
  // | os.environ["OPENAI_API_KEY"] = "your_api_key"
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // |
  // | predict = dspy.Predict("question->answer")
  // |
  // | # Enable streaming for the 'answer' field
  // | stream_predict = dspy.streamify(
  // |     predict,
  // |     stream_listeners=[dspy.streaming.StreamListener(signature_field_name="answer")],
  // | )
  // TODO translate snippet 1

  // ── Snippet 2 (lines 38–48) ────────────────────
  // | import asyncio
  // |
  // | async def read_output_stream():
  // |     output_stream = stream_predict(question="Why did a chicken cross the kitchen?")
  // |
  // |     async for chunk in output_stream:
  // |         print(chunk)
  // |
  // | asyncio.run(read_output_stream())
  // TODO translate snippet 2

  // ── Snippet 3 (lines 81–97) ────────────────────
  // | import asyncio
  // |
  // | async def read_output_stream():
  // |   output_stream = stream_predict(question="Why did a chicken cross the kitchen?")
  // |
  // |   async for chunk in output_stream:
  // |     return_value = None
  // |     if isinstance(chunk, dspy.streaming.StreamResponse):
  // |       print(f"Output token of field {chunk.signature_field_name}: {chunk.chunk}")
  // |     elif isinstance(chunk, dspy.Prediction):
  // |       return_value = chunk
  // |
  // |
  // | program_output = asyncio.run(read_output_stream())
  // | print("Final output: ", program_output)
  // TODO translate snippet 3

  // ── Snippet 4 (lines 127–172) ────────────────────
  // | import asyncio
  // |
  // | import dspy
  // |
  // | lm = dspy.LM("openai/gpt-4o-mini", cache=False)
  // | dspy.configure(lm=lm)
  // |
  // |
  // | class MyModule(dspy.Module):
  // |     def __init__(self):
  // |         super().__init__()
  // |
  // |         self.predict1 = dspy.Predict("question->answer")
  // |         self.predict2 = dspy.Predict("answer->simplified_answer")
  // |
  // |     def forward(self, question: str, **kwargs):
  // |         answer = self.predict1(question=question)
  // |         simplified_answer = self.predict2(answer=answer)
  // |         return simplified_answer
  // |
  // |
  // | predict = MyModule()
  // | stream_listeners = [
  // |     dspy.streaming.StreamListener(signature_field_name="answer"),
  // |     dspy.streaming.StreamListener(signature_field_name="simplified_answer"),
  // | ]
  // | stream_predict = dspy.streamify(
  // |     predict,
  // |     stream_listeners=stream_listeners,
  // | )
  // |
  // | async def read_output_stream():
  // |     output = stream_predict(question="why did a chicken cross the kitchen?")
  // |
  // |     return_value = None
  // |     async for chunk in output:
  // |         if isinstance(chunk, dspy.streaming.StreamResponse):
  // |             print(chunk)
  // |         elif isinstance(chunk, dspy.Prediction):
  // |             return_value = chunk
  // |     return return_value
  // |
  // | program_output = asyncio.run(read_output_stream())
  // | print("Final output: ", program_output)
  // TODO translate snippet 4

  // ── Snippet 5 (lines 201–246) ────────────────────
  // | import asyncio
  // |
  // | import dspy
  // |
  // | lm = dspy.LM("openai/gpt-4o-mini", cache=False)
  // | dspy.configure(lm=lm)
  // |
  // |
  // | def fetch_user_info(user_name: str):
  // |     """Get user information like name, birthday, etc."""
  // |     return {
  // |         "name": user_name,
  // |         "birthday": "2009-05-16",
  // |     }
  // |
  // |
  // | def get_sports_news(year: int):
  // |     """Get sports news for a given year."""
  // |     if year == 2009:
  // |         return "Usane Bolt broke the world record in the 100m race."
  // |     return None
  // |
  // |
  // | react = dspy.ReAct("question->answer", tools=[fetch_user_info, get_sports_news])
  // |
  // | stream_listeners = [
  // |     # dspy.ReAct has a built-in output field called "next_thought".
  // |     dspy.streaming.StreamListener(signature_field_name="next_thought", allow_reuse=True),
  // | ]
  // | stream_react = dspy.streamify(react, stream_listeners=stream_listeners)
  // |
  // |
  // | async def read_output_stream():
  // |     output = stream_react(question="What sports news happened in the year Adam was born?")
  // |     return_value = None
  // |     async for chunk in output:
  // |         if isinstance(chunk, dspy.streaming.StreamResponse):
  // |             print(chunk)
  // |         elif isinstance(chunk, dspy.Prediction):
  // |             return_value = chunk
  // |     return return_value
  // |
  // |
  // | print(asyncio.run(read_output_stream()))
  // TODO translate snippet 5

  // ── Snippet 6 (lines 256–311) ────────────────────
  // | import asyncio
  // |
  // | import dspy
  // |
  // | lm = dspy.LM("openai/gpt-4o-mini", cache=False)
  // | dspy.configure(lm=lm)
  // |
  // |
  // | class MyModule(dspy.Module):
  // |     def __init__(self):
  // |         super().__init__()
  // |
  // |         self.predict1 = dspy.Predict("question->answer")
  // |         self.predict2 = dspy.Predict("question, answer->answer, score")
  // |
  // |     def forward(self, question: str, **kwargs):
  // |         answer = self.predict1(question=question)
  // |         simplified_answer = self.predict2(answer=answer)
  // |         return simplified_answer
  // |
  // |
  // | predict = MyModule()
  // | stream_listeners = [
  // |     dspy.streaming.StreamListener(
  // |         signature_field_name="answer",
  // |         predict=predict.predict1,
  // |         predict_name="predict1"
  // |     ),
  // |     dspy.streaming.StreamListener(
  // |         signature_field_name="answer",
  // |         predict=predict.predict2,
  // |         predict_name="predict2"
  // |     ),
  // | ]
  // | stream_predict = dspy.streamify(
  // |     predict,
  // |     stream_listeners=stream_listeners,
  // | )
  // |
  // |
  // | async def read_output_stream():
  // |     output = stream_predict(question="why did a chicken cross the kitchen?")
  // |
  // |     return_value = None
  // |     async for chunk in output:
  // |         if isinstance(chunk, dspy.streaming.StreamResponse):
  // |             print(chunk)
  // |         elif isinstance(chunk, dspy.Prediction):
  // |             return_value = chunk
  // |     return return_value
  // |
  // |
  // | program_output = asyncio.run(read_output_stream())
  // | print("Final output: ", program_output)
  // TODO translate snippet 6

  // ── Snippet 7 (lines 343–350) ────────────────────
  // | class MyStatusMessageProvider(dspy.streaming.StatusMessageProvider):
  // |     def lm_start_status_message(self, instance, inputs):
  // |         return f"Calling LM with inputs {inputs}..."
  // |
  // |     def lm_end_status_message(self, outputs):
  // |         return f"Tool finished with output: {outputs}!"
  // TODO translate snippet 7

  // ── Snippet 8 (lines 368–425) ────────────────────
  // | import asyncio
  // |
  // | import dspy
  // |
  // | lm = dspy.LM("openai/gpt-4o-mini", cache=False)
  // | dspy.configure(lm=lm)
  // |
  // |
  // | class MyModule(dspy.Module):
  // |     def __init__(self):
  // |         super().__init__()
  // |
  // |         self.tool = dspy.Tool(lambda x: 2 * x, name="double_the_number")
  // |         self.predict = dspy.ChainOfThought("num1, num2->sum")
  // |
  // |     def forward(self, num, **kwargs):
  // |         num2 = self.tool(x=num)
  // |         return self.predict(num1=num, num2=num2)
  // |
  // |
  // | class MyStatusMessageProvider(dspy.streaming.StatusMessageProvider):
  // |     def tool_start_status_message(self, instance, inputs):
  // |         return f"Calling Tool {instance.name} with inputs {inputs}..."
  // |
  // |     def tool_end_status_message(self, outputs):
  // |         return f"Tool finished with output: {outputs}!"
  // |
  // |
  // | predict = MyModule()
  // | stream_listeners = [
  // |     # dspy.ChainOfThought has a built-in output field called "reasoning".
  // |     dspy.streaming.StreamListener(signature_field_name="reasoning"),
  // | ]
  // | stream_predict = dspy.streamify(
  // |     predict,
  // |     stream_listeners=stream_listeners,
  // |     status_message_provider=MyStatusMessageProvider(),
  // | )
  // |
  // |
  // | async def read_output_stream():
  // |     output = stream_predict(num=3)
  // |
  // |     return_value = None
  // |     async for chunk in output:
  // |         if isinstance(chunk, dspy.streaming.StreamResponse):
  // |             print(chunk)
  // |         elif isinstance(chunk, dspy.Prediction):
  // |             return_value = chunk
  // |         elif isinstance(chunk, dspy.streaming.StatusMessage):
  // |             print(chunk)
  // |     return return_value
  // |
  // |
  // | program_output = asyncio.run(read_output_stream())
  // | print("Final output: ", program_output)
  // TODO translate snippet 8

  // ── Snippet 9 (lines 465–492) ────────────────────
  // | import os
  // |
  // | import dspy
  // |
  // | os.environ["OPENAI_API_KEY"] = "your_api_key"
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // |
  // | predict = dspy.Predict("question->answer")
  // |
  // | # Enable streaming for the 'answer' field
  // | stream_predict = dspy.streamify(
  // |     predict,
  // |     stream_listeners=[dspy.streaming.StreamListener(signature_field_name="answer")],
  // |     async_streaming=False,
  // | )
  // |
  // | output = stream_predict(question="why did a chicken cross the kitchen?")
  // |
  // | program_output = None
  // | for chunk in output:
  // |     if isinstance(chunk, dspy.streaming.StreamResponse):
  // |         print(chunk)
  // |     elif isinstance(chunk, dspy.Prediction):
  // |         program_output = chunk
  // | print(f"Program output: {program_output}")
  // TODO translate snippet 9
}
