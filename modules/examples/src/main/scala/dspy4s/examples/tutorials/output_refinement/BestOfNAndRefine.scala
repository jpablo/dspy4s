/** Output Refinement: BestOfN and Refine
  *
  * Source: docs/docs/tutorials/output_refinement/best-of-n-and-refine.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/output_refinement/best-of-n-and-refine.md Status:
  * translated (6/6 snippets)
  *
  * `BestOfN[I, O]` / `Refine[I, O]` wrap an inner program and take a reward `(I, Prediction[O]) => Double`. Wrapping a
  * `ChainOfThought("q -> a")` makes the inner output the augmented named tuple `(reasoning, a)`, so the reward reads
  * `pred.output.<field>`.
  */
package dspy4s.examples.tutorials.output_refinement

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.examples.Demo
import dspy4s.programs.{AttemptCount, FailureCount}
import dspy4s.programs.strategies.{BestOfN, ChainOfThought, Refine}
import dspy4s.signatures.{InputField, OutputField, Signature, Spec}

// Snippet 5 judge signature (top-level for Mirror derivation).
// | class FactualityJudge(dspy.Signature):
// |     """Determine if a statement is factually accurate."""
// |     statement: str = dspy.InputField()
// |     is_factual: bool = dspy.OutputField()
trait FactualityJudge extends Spec:
  def statement: InputField[String]
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
    // --8<-- [start:best-of-n]
    val bestOf3 = BestOfN(
      module = qa,
      n = AttemptCount(3),
      rewardFn = (_, pred) => oneWord(pred.output.answer),
      threshold = 1.0
    )

    def call(question: String)(using RuntimeContext): Either[DspyError, String] =
      bestOf3((question = question)).map(_.output.answer)
    // --8<-- [end:best-of-n]

  // ── Snippet 2 (lines 34–45) — BestOfN with fail_count ──
  // | best_of_3 = dspy.BestOfN(module=qa, N=3, reward_fn=one_word_answer, threshold=1.0, fail_count=1)
  object BestOfNWithFailCount:
    // --8<-- [start:fail-count]
    val bestOf3 = BestOfN(
      module = qa,
      n = AttemptCount(3),
      rewardFn = (_, pred) => oneWord(pred.output.answer),
      threshold = 1.0,
      failCount = Some(FailureCount(1))
    )
    // --8<-- [end:fail-count]

  // ── Snippets 3 + 4 (lines 53–83) — Refine (same shape; sequential refinement) ──
  // | refine = dspy.Refine(module=dspy.ChainOfThought("question -> answer"), N=3,
  // |                      reward_fn=one_word_answer, threshold=1.0[, fail_count=1])
  object OneWordRefine:
    val refine = Refine(
      module = qa,
      n = AttemptCount(3),
      rewardFn = (_, pred) => oneWord(pred.output.answer),
      threshold = 1.0
    )
    // `failCount` limits the failures. A low reward is not a failure. This value takes one failure, and the second
    // failure gives a `Left`.
    val refineWithFailCount = Refine(
      module = qa,
      n = AttemptCount(3),
      rewardFn = (_, pred) => oneWord(pred.output.answer),
      threshold = 1.0,
      failCount = Some(FailureCount(1))
    )

    def call(question: String)(using RuntimeContext): Either[DspyError, String] =
      refine((question = question)).map(_.output.answer)

  // ── Snippet 5 (lines 96–120) — an LLM-judge reward ──
  // | factuality_judge = dspy.ChainOfThought(FactualityJudge)
  // | def factuality_reward(args, pred): return 1.0 if factuality_judge(pred.answer).is_factual else 0.0
  // | refined_qa = dspy.Refine(module=dspy.ChainOfThought("question -> answer"), N=3,
  // |                          reward_fn=factuality_reward, threshold=1.0)
  //
  // The reward calls an LM, so it captures the ambient `RuntimeContext` from `call` (dspy4s reward
  // functions take `(I, Prediction[O])` only — no implicit context is threaded into them).
  object FactualityRefine:
    // --8<-- [start:llm-judge]
    private val judge = ChainOfThought(
      Signature.of[FactualityJudge](instructions = "Determine if a statement is factually accurate.")
    )

    def call(question: String)(using ctx: RuntimeContext): Either[DspyError, String] =
      // An error of the judge is not a reward of 0. The exception counts as a failure of the attempt.
      def reward(answer: String): Double =
        judge((statement = answer)).fold(
          error => throw RuntimeException(error.message),
          r => if r.output.is_factual then 1.0 else 0.0
        )
      val refinedQa = Refine(
        module = qa,
        n = AttemptCount(3),
        rewardFn = (_, pred) => reward(pred.output.answer),
        threshold = 1.0
      )
      refinedQa((question = question)).map(_.output.answer)
    // --8<-- [end:llm-judge]

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

    def optimizedSummarizer(n: AttemptCount) =
      BestOfN(
        module = summarizer,
        n = n,
        rewardFn = (_, pred) => idealLength(pred.output.summary),
        threshold = 0.9
      )

    // The default of `n` is the `N=50` of upstream. The threshold 0.9 needs a summary within 12 words of 75 words. Each
    // attempt has its own rollout id and the temperature 1.0, so the cache does not help. One call can thus make `n`
    // model calls.
    def call(text: String, n: AttemptCount = AttemptCount(50))(using RuntimeContext): Either[DspyError, String] =
      optimizedSummarizer(n)((text = text)).map(_.output.summary)

    /** A text of about 300 words for the `@main`. Upstream has only the placeholder "[Long text to summarize...]". */
    val sampleText: String =
      """A lighthouse is a tower with a strong light at the top. Ships use the light to find their position at night
        |and in bad weather. The first lighthouses were fires on hills or on tall stone towers. Workers burned wood or
        |coal, and they had to feed the fire all night. Later towers used oil lamps with glass around the flame. In the
        |1820s the French engineer Augustin Fresnel designed a new type of lens. The lens had many rings of glass, and
        |each ring bent the light into one narrow beam. A small lamp could then send a beam many miles over the sea.
        |Most lighthouses soon used this lens, and some still use it today.
        |
        |Each lighthouse has its own signal, so a sailor can identify it. One light flashes every five seconds, and
        |another light shows two quick flashes and then a long dark period. The charts of a ship list the signal of
        |each light, and the sailor compares the signal with the chart. A tower also has its own pattern of paint for
        |the day, for example red and white bands. Many lighthouses also had a fog horn, because fog can hide even a
        |strong light.
        |
        |For a long time, each lighthouse had a keeper. The keeper cleaned the lens, filled the lamp, wound the
        |clockwork that turned the lens, and wrote the weather in a log. The work was hard and lonely, mainly on small
        |islands far from land. In the twentieth century, electric lamps and automatic switches replaced most of this
        |work. Today a computer on the shore often watches the light, and a technician visits only a few times each
        |year. Satellite navigation has also reduced the need for lighthouses, but ships still use them as a backup,
        |and many countries keep them in service. Some old towers are now museums, and visitors can climb the stairs
        |to the lamp room.""".stripMargin

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.output_refinement.bestOfNAndRefineMain"
@main def bestOfNAndRefineMain(): Unit =
  Demo.withLm {
    val q          = "What is the capital of Belgium?"
    val summarizer = BestOfNAndRefine.IdealLengthSummarizer
    println("BestOfN: " + BestOfNAndRefine.OneWordBestOfN.call(q))
    println("Refine:  " + BestOfNAndRefine.OneWordRefine.call(q))
    println("Judge:   " + BestOfNAndRefine.FactualityRefine.call("Tell me about Belgium's capital city."))
    // Three attempts here, because 50 attempts can make 50 model calls.
    println("Summary: " + summarizer.call(summarizer.sampleText, n = AttemptCount(3)))
  }
