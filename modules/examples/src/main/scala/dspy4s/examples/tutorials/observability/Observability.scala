/**
 * Tutorial: Debugging and Observability in DSPy
 *
 * Source:   docs/docs/tutorials/observability/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/observability/index.md
 * Status:   scaffold (6 python snippets — TODO translate)
 */
package dspy4s.examples.tutorials.observability

object Observability {

  // ── Snippet 1 (lines 15–33) ────────────────────
  // | import dspy
  // | import os
  // |
  // | os.environ["OPENAI_API_KEY"] = "{your_openai_api_key}"
  // |
  // | lm = dspy.LM("openai/gpt-4o-mini")
  // | colbert = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")
  // | dspy.configure(lm=lm)
  // |
  // |
  // | def retrieve(query: str):
  // |     """Retrieve top 3 relevant information from ColBert"""
  // |     results = colbert(query, k=3)
  // |     return [x["text"] for x in results]
  // |
  // |
  // | agent = dspy.ReAct("question -> answer", tools=[retrieve], max_iters=3)
  // TODO translate snippet 1

  // ── Snippet 2 (lines 37–40) ────────────────────
  // | prediction = agent(question="Which baseball team does Shohei Ohtani play for in June 2025?")
  // | print(prediction.answer)
  // TODO translate snippet 2

  // ── Snippet 3 (lines 52–55) ────────────────────
  // | # Print out 5 LLM calls
  // | dspy.inspect_history(n=5)
  // TODO translate snippet 3

  // ── Snippet 4 (lines 112–137) ────────────────────
  // | import dspy
  // | import os
  // | import mlflow
  // |
  // | os.environ["OPENAI_API_KEY"] = "{your_openai_api_key}"
  // |
  // | # Tell MLflow about the server URI.
  // | mlflow.set_tracking_uri("http://127.0.0.1:5000")
  // | # Create a unique name for your experiment.
  // | mlflow.set_experiment("DSPy")
  // |
  // | lm = dspy.LM("openai/gpt-4o-mini")
  // | colbert = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")
  // | dspy.configure(lm=lm)
  // |
  // |
  // | def retrieve(query: str):
  // |     """Retrieve top 3 relevant information from ColBert"""
  // |     results = colbert(query, k=3)
  // |     return [x["text"] for x in results]
  // |
  // |
  // | agent = dspy.ReAct("question -> answer", tools=[retrieve], max_iters=3)
  // | print(agent(question="Which baseball team does Shohei Ohtani play for?"))
  // TODO translate snippet 4

  // ── Snippet 5 (lines 154–175) ────────────────────
  // | from tavily import TavilyClient
  // | import dspy
  // | import mlflow
  // |
  // | # Tell MLflow about the server URI.
  // | mlflow.set_tracking_uri("http://127.0.0.1:5000")
  // | # Create a unique name for your experiment.
  // | mlflow.set_experiment("DSPy")
  // |
  // | search_client = TavilyClient(api_key="<YOUR_TAVILY_API_KEY>")
  // |
  // | def web_search(query: str) -> list[str]:
  // |     """Run a web search and return the content from the top 5 search results"""
  // |     response = search_client.search(query)
  // |     return [r["content"] for r in response["results"]]
  // |
  // | agent = dspy.ReAct("question -> answer", tools=[web_search])
  // |
  // | prediction = agent(question="Which baseball team does Shohei Ohtani play for?")
  // | print(agent.answer)
  // TODO translate snippet 5

  // ── Snippet 6 (lines 211–231) ────────────────────
  // | import dspy
  // | from dspy.utils.callback import BaseCallback
  // |
  // | # 1. Define a custom callback class that extends BaseCallback class
  // | class AgentLoggingCallback(BaseCallback):
  // |
  // |     # 2. Implement on_module_end handler to run a custom logging code.
  // |     def on_module_end(self, call_id, outputs, exception):
  // |         step = "Reasoning" if self._is_reasoning_output(outputs) else "Acting"
  // |         print(f"== {step} Step ===")
  // |         for k, v in outputs.items():
  // |             print(f"  {k}: {v}")
  // |         print("\n")
  // |
  // |     def _is_reasoning_output(self, outputs):
  // |         return any(k.startswith("Thought") for k in outputs.keys())
  // |
  // | # 3. Set the callback to DSPy setting so it will be applied to program execution
  // | dspy.configure(callbacks=[AgentLoggingCallback()])
  // TODO translate snippet 6
}
