/**
 * Examples in DSPy
 *
 * Source:   docs/docs/deep-dive/data-handling/examples.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/deep-dive/data-handling/examples.md
 * Status:   scaffold (6 python snippets — TODO translate)
 */
package dspy4s.examples.deep_dive.data_handling

object Examples {

  // ── Snippet 1 (lines 19–25) ────────────────────
  // | qa_pair = dspy.Example(question="This is a question?", answer="This is an answer.")
  // |
  // | print(qa_pair)
  // | print(qa_pair.question)
  // | print(qa_pair.answer)
  // TODO translate snippet 1

  // ── Snippet 2 (lines 45–51) ────────────────────
  // | # Single Input.
  // | print(qa_pair.with_inputs("question"))
  // |
  // | # Multiple Inputs; be careful about marking your labels as inputs unless you mean it.
  // | print(qa_pair.with_inputs("question", "answer"))
  // TODO translate snippet 2

  // ── Snippet 3 (lines 64–72) ────────────────────
  // | article_summary = dspy.Example(article= "This is an article.", summary= "This is a summary.").with_inputs("article")
  // |
  // | input_key_only = article_summary.inputs()
  // | non_input_key_only = article_summary.labels()
  // |
  // | print("Example object with Input fields only:", input_key_only)
  // | print("Example object with Non-Input fields only:", non_input_key_only)
  // TODO translate snippet 3

  // ── Snippet 4 (lines 82–86) ────────────────────
  // | article_summary = dspy.Example(context="This is an article.", question="This is a question?", answer="This is an answer.", rationale= "This is a rationale.").with_inputs("context", "question")
  // |
  // | print("Example object without answer & rationale keys:", article_summary.without("answer", "rationale"))
  // TODO translate snippet 4

  // ── Snippet 5 (lines 95–97) ────────────────────
  // | article_summary.context = "new context"
  // TODO translate snippet 5

  // ── Snippet 6 (lines 103–106) ────────────────────
  // | for k, v in article_summary.items():
  // |     print(f"{k} = {v}")
  // TODO translate snippet 6
}
