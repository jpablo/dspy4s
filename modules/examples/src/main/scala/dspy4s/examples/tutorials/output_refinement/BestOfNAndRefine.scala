/**
 * Output Refinement: BestOfN and Refine
 *
 * Source:   docs/docs/tutorials/output_refinement/best-of-n-and-refine.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/output_refinement/best-of-n-and-refine.md
 * Status:   translated (6/6 snippets)
 *
 * Typed `BestOfN[I, O]` / `Refine[I, O]` wrap an inner typed program and take a typed reward
 * `(I, Prediction[O]) => Double`. Wrapping a `ChainOfThought("q -> a")` makes the inner output the
 * augmented named tuple `(reasoning, a)`, so the reward reads `pred.output.<field>`.
 */
package dspy4s.examples.tutorials.output_refinement

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.examples.Demo
import dspy4s.programs.{BestOfN, ChainOfThought, Refine}
import dspy4s.typed.{InputField, OutputField, Signature, Spec}

// Snippet 5 judge signature (top-level for Mirror derivation).
// | class FactualityJudge(dspy.Signature):
// |     """Determine if a statement is factually accurate."""
// |     statement: str = dspy.InputField()
// |     is_factual: bool = dspy.OutputField()
trait FactualityJudge extends Spec:
  def statement:  InputField[String]
  def is_factual: OutputField[Boolean]

object BestOfNAndRefine:

  /** Python's `one_word_answer`: 1.0 iff the answer is a single word. */
  private def oneWord(answer: String): Double =
    if answer.trim.split("\\s+").count(_.nonEmpty) == 1 then 1.0 else 0.0

  private def qa = ChainOfThought(Signature.fromString("question -> answer"))

  // ── Snippet 1 (lines 13–28) — BestOfN over ChainOfThought ──
  // | best_of_3 = dspy.BestOfN(module=dspy.ChainOfThought("question -> answer"), N=3,
  // |                          reward_fn=one_word_answer, threshold=1.0)
  // | best_of_3(question="What is the capital of Belgium?").answer  # Brussels
  object OneWordBestOfN:
    val bestOf3 = BestOfN(module = qa, n = 3, rewardFn = (_, pred) => oneWord(pred.output.answer), threshold = 1.0)

    def call(question: String)(using RuntimeContext): Either[DspyError, String] =
      bestOf3.apply((question = question)).map(_.output.answer)

  // ── Snippet 2 (lines 34–45) — BestOfN with fail_count ──
  // | best_of_3 = dspy.BestOfN(module=qa, N=3, reward_fn=one_word_answer, threshold=1.0, fail_count=1)
  object BestOfNWithFailCount:
    val bestOf3 = BestOfN(module = qa, n = 3, rewardFn = (_, pred) => oneWord(pred.output.answer),
                          threshold = 1.0, failCount = Some(1))

  // ── Snippets 3 + 4 (lines 53–83) — Refine (same shape; sequential refinement) ──
  // | refine = dspy.Refine(module=dspy.ChainOfThought("question -> answer"), N=3,
  // |                      reward_fn=one_word_answer, threshold=1.0[, fail_count=1])
  object OneWordRefine:
    val refine = Refine(module = qa, n = 3, rewardFn = (_, pred) => oneWord(pred.output.answer), threshold = 1.0)
    val refineStopOnFirstError =
      Refine(module = qa, n = 3, rewardFn = (_, pred) => oneWord(pred.output.answer), threshold = 1.0, failCount = Some(1))

    def call(question: String)(using RuntimeContext): Either[DspyError, String] =
      refine.apply((question = question)).map(_.output.answer)

  // ── Snippet 5 (lines 96–120) — an LLM-judge reward ──
  // | factuality_judge = dspy.ChainOfThought(FactualityJudge)
  // | def factuality_reward(args, pred): return 1.0 if factuality_judge(pred.answer).is_factual else 0.0
  // | refined_qa = dspy.Refine(module=dspy.ChainOfThought("question -> answer"), N=3,
  // |                          reward_fn=factuality_reward, threshold=1.0)
  //
  // The reward calls an LM, so it captures the ambient `RuntimeContext` from `call` (dspy4s reward
  // functions take `(I, Prediction[O])` only — no implicit context is threaded into them).
  object FactualityRefine:
    private val judge = ChainOfThought(Signature.of[FactualityJudge])

    def call(question: String)(using ctx: RuntimeContext): Either[DspyError, String] =
      def reward(answer: String): Double =
        judge.apply((statement = answer)).map(r => if r.output.is_factual then 1.0 else 0.0).getOrElse(0.0)
      val refinedQa = Refine(module = qa, n = 3, rewardFn = (_, pred) => reward(pred.output.answer), threshold = 1.0)
      refinedQa.apply((question = question)).map(_.output.answer)

  // ── Snippet 6 (lines 124–146) — a tapering length reward ──
  // | def ideal_length_reward(args, pred): d = abs(len(pred.summary.split()) - 75); return max(0, 1 - d/125)
  // | optimized_summarizer = dspy.BestOfN(module=dspy.ChainOfThought("text -> summary"), N=50,
  // |                                     reward_fn=ideal_length_reward, threshold=0.9)
  object IdealLengthSummarizer:
    private val summarizer = ChainOfThought(Signature.fromString("text -> summary"))

    private def idealLength(summary: String): Double =
      val words    = summary.trim.split("\\s+").count(_.nonEmpty)
      val distance = math.abs(words - 75)
      math.max(0.0, 1.0 - distance / 125.0)

    val optimizedSummarizer =
      BestOfN(module = summarizer, n = 50, rewardFn = (_, pred) => idealLength(pred.output.summary), threshold = 0.9)

    def call(text: String)(using RuntimeContext): Either[DspyError, String] =
      optimizedSummarizer.apply((text = text)).map(_.output.summary)

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.output_refinement.bestOfNAndRefineMain"
@main def bestOfNAndRefineMain(): Unit = Demo.withLm {
  val q = "What is the capital of Belgium?"
  println("BestOfN: " + BestOfNAndRefine.OneWordBestOfN.call(q))
  println("Refine:  " + BestOfNAndRefine.OneWordRefine.call(q))
  println("Judge:   " + BestOfNAndRefine.FactualityRefine.call("Tell me about Belgium's capital city."))
  println("Summary: " + BestOfNAndRefine.IdealLengthSummarizer.call("[Long text to summarize...]"))
}
