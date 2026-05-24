/**
 * Metrics
 *
 * Source:   docs/docs/learn/evaluation/metrics.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/evaluation/metrics.md
 * Status:   scaffold (7 python snippets — TODO translate)
 */
package dspy4s.examples.learn.evaluation

object Metrics {

  // ── Snippet 1 (lines 29–32) ────────────────────
  // | def validate_answer(example, pred, trace=None):
  // |     return example.answer.lower() == pred.answer.lower()
  // TODO translate snippet 1

  // ── Snippet 2 (lines 41–53) ────────────────────
  // | def validate_context_and_answer(example, pred, trace=None):
  // |     # check the gold label and the predicted answer are the same
  // |     answer_match = example.answer.lower() == pred.answer.lower()
  // |
  // |     # check the predicted answer comes from one of the retrieved contexts
  // |     context_match = any((pred.answer.lower() in c) for c in pred.context)
  // |
  // |     if trace is None: # if we're doing evaluation or optimization
  // |         return (answer_match + context_match) / 2.0
  // |     else: # if we're doing bootstrapping, i.e. self-generating good demonstrations of each step
  // |         return answer_match and context_match
  // TODO translate snippet 2

  // ── Snippet 3 (lines 62–68) ────────────────────
  // | scores = []
  // | for x in devset:
  // |     pred = program(**x.inputs())
  // |     score = metric(x, pred)
  // |     scores.append(score)
  // TODO translate snippet 3

  // ── Snippet 4 (lines 72–80) ────────────────────
  // | from dspy.evaluate import Evaluate
  // |
  // | # Set up the evaluator, which can be re-used in your code.
  // | evaluator = Evaluate(devset=YOUR_DEVSET, num_threads=1, display_progress=True, display_table=5)
  // |
  // | # Launch evaluation.
  // | evaluator(YOUR_PROGRAM, metric=YOUR_METRIC)
  // TODO translate snippet 4

  // ── Snippet 5 (lines 89–97) ────────────────────
  // | # Define the signature for automatic assessments.
  // | class Assess(dspy.Signature):
  // |     """Assess the quality of a tweet along the specified dimension."""
  // |
  // |     assessed_text = dspy.InputField()
  // |     assessment_question = dspy.InputField()
  // |     assessment_answer: bool = dspy.OutputField()
  // TODO translate snippet 5

  // ── Snippet 6 (lines 101–116) ────────────────────
  // | def metric(gold, pred, trace=None):
  // |     question, answer, tweet = gold.question, gold.answer, pred.output
  // |
  // |     engaging = "Does the assessed text make for a self-contained, engaging tweet?"
  // |     correct = f"The text should answer `{question}` with `{answer}`. Does the assessed text contain this answer?"
  // |
  // |     correct =  dspy.Predict(Assess)(assessed_text=tweet, assessment_question=correct)
  // |     engaging = dspy.Predict(Assess)(assessed_text=tweet, assessment_question=engaging)
  // |
  // |     correct, engaging = [m.assessment_answer for m in [correct, engaging]]
  // |     score = (correct + engaging) if correct and (len(tweet) <= 280) else 0
  // |
  // |     if trace is not None: return score >= 2
  // |     return score / 2.0
  // TODO translate snippet 6

  // ── Snippet 7 (lines 134–142) ────────────────────
  // | def validate_hops(example, pred, trace=None):
  // |     hops = [example.question] + [outputs.query for *_, outputs in trace if 'query' in outputs]
  // |
  // |     if max([len(h) for h in hops]) > 100: return False
  // |     if any(dspy.evaluate.answer_exact_match_str(hops[idx], hops[:idx], frac=0.8) for idx in range(2, len(hops))): return False
  // |
  // |     return True
  // TODO translate snippet 7
}
