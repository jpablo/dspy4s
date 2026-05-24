/**
 * Modules
 *
 * Source:   docs/docs/learn/programming/modules.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/modules.md
 * Status:   scaffold (4 python snippets — TODO translate)
 */
package dspy4s.examples.learn.programming

object Modules {

  // ── Snippet 1 (lines 22–33) ────────────────────
  // | sentence = "it's a charming and often affecting journey."  # example from the SST-2 dataset.
  // |
  // | # 1) Declare with a signature.
  // | classify = dspy.Predict('sentence -> sentiment: bool')
  // |
  // | # 2) Call with input argument(s).
  // | response = classify(sentence=sentence)
  // |
  // | # 3) Access the output.
  // | print(response.sentiment)
  // TODO translate snippet 1

  // ── Snippet 2 (lines 45–56) ────────────────────
  // | question = "What's something great about the ColBERT retrieval model?"
  // |
  // | # 1) Declare with a signature, and pass some config.
  // | classify = dspy.ChainOfThought('question -> answer', n=5)
  // |
  // | # 2) Call with input argument.
  // | response = classify(question=question)
  // |
  // | # 3) Access the outputs.
  // | response.completions.answer
  // TODO translate snippet 2

  // ── Snippet 3 (lines 70–73) ────────────────────
  // | print(f"Reasoning: {response.reasoning}")
  // | print(f"Answer: {response.answer}")
  // TODO translate snippet 3

  // ── Snippet 4 (lines 84–86) ────────────────────
  // | response.completions[3].reasoning == response.completions.reasoning[3]
  // TODO translate snippet 4
}
