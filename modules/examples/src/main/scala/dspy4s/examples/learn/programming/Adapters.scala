/**
 * Understanding DSPy Adapters
 *
 * Source:   docs/docs/learn/programming/adapters.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/adapters.md
 * Status:   scaffold (6 python snippets — TODO translate)
 */
package dspy4s.examples.learn.programming

object Adapters {

  // ── Snippet 1 (lines 24–31) ────────────────────
  // | import dspy
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // |
  // | predict = dspy.Predict("question -> answer")
  // | result = predict(question="What is the capital of France?")
  // TODO translate snippet 1

  // ── Snippet 2 (lines 33–43) ────────────────────
  // | import dspy
  // |
  // | dspy.configure(
  // |     lm=dspy.LM("openai/gpt-4o-mini"),
  // |     adapter=dspy.ChatAdapter(),  # This is the default value
  // | )
  // |
  // | predict = dspy.Predict("question -> answer")
  // | result = predict(question="What is the capital of France?")
  // TODO translate snippet 2

  // ── Snippet 3 (lines 58–66) ────────────────────
  // | # Simplified flow example
  // | signature = dspy.Signature("question -> answer")
  // | inputs = {"question": "What is 2+2?"}
  // | demos = [{"question": "What is 1+1?", "answer": "2"}]
  // |
  // | adapter = dspy.ChatAdapter()
  // | print(adapter.format(signature, demos, inputs))
  // TODO translate snippet 3

  // ── Snippet 4 (lines 79–85) ────────────────────
  // | import dspy
  // |
  // | signature = dspy.Signature("question -> answer")
  // | system_message = dspy.ChatAdapter().format_system_message(signature)
  // | print(system_message)
  // TODO translate snippet 4

  // ── Snippet 5 (lines 117–141) ────────────────────
  // | import dspy
  // | import pydantic
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"), adapter=dspy.ChatAdapter())
  // |
  // |
  // | class ScienceNews(pydantic.BaseModel):
  // |     text: str
  // |     scientists_involved: list[str]
  // |
  // |
  // | class NewsQA(dspy.Signature):
  // |     """Get news about the given science field"""
  // |
  // |     science_field: str = dspy.InputField()
  // |     year: int = dspy.InputField()
  // |     num_of_outputs: int = dspy.InputField()
  // |     news: list[ScienceNews] = dspy.OutputField(desc="science news")
  // |
  // |
  // | predict = dspy.Predict(NewsQA)
  // | predict(science_field="Computer Theory", year=2022, num_of_outputs=1)
  // | dspy.inspect_history()
  // TODO translate snippet 5

  // ── Snippet 6 (lines 232–256) ────────────────────
  // | import dspy
  // | import pydantic
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"), adapter=dspy.JSONAdapter())
  // |
  // |
  // | class ScienceNews(pydantic.BaseModel):
  // |     text: str
  // |     scientists_involved: list[str]
  // |
  // |
  // | class NewsQA(dspy.Signature):
  // |     """Get news about the given science field"""
  // |
  // |     science_field: str = dspy.InputField()
  // |     year: int = dspy.InputField()
  // |     num_of_outputs: int = dspy.InputField()
  // |     news: list[ScienceNews] = dspy.OutputField(desc="science news")
  // |
  // |
  // | predict = dspy.Predict(NewsQA)
  // | predict(science_field="Computer Theory", year=2022, num_of_outputs=1)
  // | dspy.inspect_history()
  // TODO translate snippet 6
}
