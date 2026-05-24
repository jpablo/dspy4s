/**
 * Output Refinement: BestOfN and Refine
 *
 * Source:   docs/docs/tutorials/output_refinement/best-of-n-and-refine.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/output_refinement/best-of-n-and-refine.md
 * Status:   scaffold (6 python snippets — TODO translate)
 */
package dspy4s.examples.tutorials.output_refinement

object BestOfNAndRefine {

  // ── Snippet 1 (lines 13–28) ────────────────────
  // | import dspy
  // |
  // | def one_word_answer(args, pred: dspy.Prediction) -> float:
  // |     return 1.0 if len(pred.answer.split()) == 1 else 0.0
  // |
  // | best_of_3 = dspy.BestOfN(
  // |     module=dspy.ChainOfThought("question -> answer"),
  // |     N=3,
  // |     reward_fn=one_word_answer,
  // |     threshold=1.0
  // | )
  // |
  // | result = best_of_3(question="What is the capital of Belgium?")
  // | print(result.answer)  # Brussels
  // TODO translate snippet 1

  // ── Snippet 2 (lines 34–45) ────────────────────
  // | best_of_3 = dspy.BestOfN(
  // |     module=qa,
  // |     N=3,
  // |     reward_fn=one_word_answer,
  // |     threshold=1.0,
  // |     fail_count=1
  // | )
  // |
  // | best_of_3(question="What is the capital of Belgium?")
  // | # raises an error after the first failure
  // TODO translate snippet 2

  // ── Snippet 3 (lines 53–68) ────────────────────
  // | import dspy
  // |
  // | def one_word_answer(args, pred: dspy.Prediction) -> float:
  // |     return 1.0 if len(pred.answer.split()) == 1 else 0.0
  // |
  // | refine = dspy.Refine(
  // |     module=dspy.ChainOfThought("question -> answer"),
  // |     N=3,
  // |     reward_fn=one_word_answer,
  // |     threshold=1.0
  // | )
  // |
  // | result = refine(question="What is the capital of Belgium?")
  // | print(result.answer)  # Brussels
  // TODO translate snippet 3

  // ── Snippet 4 (lines 74–83) ────────────────────
  // | # Stop after the first error
  // | refine = dspy.Refine(
  // |     module=qa,
  // |     N=3,
  // |     reward_fn=one_word_answer,
  // |     threshold=1.0,
  // |     fail_count=1
  // | )
  // TODO translate snippet 4

  // ── Snippet 5 (lines 96–120) ────────────────────
  // | import dspy
  // |
  // | class FactualityJudge(dspy.Signature):
  // |     """Determine if a statement is factually accurate."""
  // |     statement: str = dspy.InputField()
  // |     is_factual: bool = dspy.OutputField()
  // |
  // | factuality_judge = dspy.ChainOfThought(FactualityJudge)
  // |
  // | def factuality_reward(args, pred: dspy.Prediction) -> float:
  // |     statement = pred.answer
  // |     result = factuality_judge(statement)
  // |     return 1.0 if result.is_factual else 0.0
  // |
  // | refined_qa = dspy.Refine(
  // |     module=dspy.ChainOfThought("question -> answer"),
  // |     N=3,
  // |     reward_fn=factuality_reward,
  // |     threshold=1.0
  // | )
  // |
  // | result = refined_qa(question="Tell me about Belgium's capital city.")
  // | print(result.answer)
  // TODO translate snippet 5

  // ── Snippet 6 (lines 124–146) ────────────────────
  // | import dspy
  // |
  // | def ideal_length_reward(args, pred: dspy.Prediction) -> float:
  // |     """
  // |     Reward the summary for being close to 75 words with a tapering off for longer summaries.
  // |     """
  // |     word_count = len(pred.summary.split())
  // |     distance = abs(word_count - 75)
  // |     return max(0.0, 1.0 - (distance / 125))
  // |
  // | optimized_summarizer = dspy.BestOfN(
  // |     module=dspy.ChainOfThought("text -> summary"),
  // |     N=50,
  // |     reward_fn=ideal_length_reward,
  // |     threshold=0.9
  // | )
  // |
  // | result = optimized_summarizer(
  // |     text="[Long text to summarize...]"
  // | )
  // | print(result.summary)
  // TODO translate snippet 6
}
