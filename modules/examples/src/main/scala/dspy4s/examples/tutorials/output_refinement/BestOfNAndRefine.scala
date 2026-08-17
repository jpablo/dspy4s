/** Output Refinement: BestOfN and Refine
  *
  * Source: docs/docs/tutorials/output_refinement/best-of-n-and-refine.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/output_refinement/best-of-n-and-refine.md Status:
  * translated (6/6 snippets).
  *
  * `Program.bestOfN` repeats a program and selects the highest score. `Refine` also runs an explicit critic program.
  * Both constructions keep retry control flow in the typed program syntax.
  */
package dspy4s.examples.tutorials.output_refinement

import dspy4s.core.contracts.DspyError
import dspy4s.examples.Demo
import dspy4s.programs.{ChainOfThought, ParameterId, PredictionBackend, Program, Refine}
import dspy4s.signatures.Signature
import zio.blocks.schema.Schema

final case class FactualityCritiqueInput(statement: String, attempt: Int) derives Schema
final case class FactualityCritique(advice: String) derives Schema

object BestOfNAndRefine:

  private type QaInput  = (question: String)
  private type QaOutput = ChainOfThought.WithReasoning[(answer: String)]

  private val answerId = ParameterId("refinement/answer")
  private val qa = ChainOfThought(
    answerId,
    Signature.fromString("question -> answer")
  )

  private def oneWord(answer: String): Double =
    if answer.trim.split("\\s+").count(_.nonEmpty) == 1 then 1.0 else 0.0

  // ── Snippet 1 — BestOfN over ChainOfThought ──
  // | best_of_3 = dspy.BestOfN(module=dspy.ChainOfThought("question -> answer"), N=3,
  // |                          reward_fn=one_word_answer, threshold=1.0)
  // | best_of_3(question="What is the capital of Belgium?").answer
  object OneWordBestOfN:
    // --8<-- [start:best-of-n]
    val bestOf3 = Program.bestOfN(qa, attempts = 3, threshold = Some(1.0)) { (_, prediction) =>
      Right(oneWord(prediction.output.answer))
    }

    def call(question: String)(using PredictionBackend): Either[DspyError, String] =
      Demo.run(bestOf3, (question = question)).map(_.output.answer)
    // --8<-- [end:best-of-n]

  // ── Snippet 2 — stop after the first failed attempt ──
  // | best_of_3 = dspy.BestOfN(module=qa, N=3, reward_fn=one_word_answer, threshold=1.0, fail_count=1)
  object BestOfNWithFailCount:
    // --8<-- [start:fail-count]
    val bestOf3 = Program.bestOfN(qa, attempts = 3, threshold = Some(1.0), failAfter = 1) { (_, prediction) =>
      Right(oneWord(prediction.output.answer))
    }
    // --8<-- [end:fail-count]

  // ── Snippets 3 + 4 — Refine with explicit critic advice ──
  // | refine = dspy.Refine(module=dspy.ChainOfThought("question -> answer"), N=3,
  // |                      reward_fn=one_word_answer, threshold=1.0[, fail_count=1])
  object OneWordRefine:
    private val critic = Program.lift[Refine.Attempt[QaInput, QaOutput], Refine.Advice] { _ =>
      Refine.Advice(Map(answerId -> "Return only one word."))
    }

    val refine = Refine(qa, critic, maxAttempts = 3, threshold = 1.0) { (_, prediction) =>
      Right(oneWord(prediction.output.answer))
    }

    def call(question: String)(using PredictionBackend): Either[DspyError, String] =
      Demo.run(refine, (question = question)).map(_.output.answer)

  // ── Snippet 5 — an LM critic expressed as a visible child program ──
  // | factuality_judge = dspy.ChainOfThought(FactualityJudge)
  // | refined_qa = dspy.Refine(module=qa, N=3, reward_fn=factuality_reward, threshold=1.0)
  // The current functional `Refine` accepts an effectful critic program and a pure score. The critic can therefore be
  // an LM program. This example asks it for stable-ID advice after each rejected answer.
  object FactualityRefine:
    private val critic = Program
      .predict(
        ParameterId("refinement/factuality-critic"),
        Signature.derived[FactualityCritiqueInput, FactualityCritique](
          "FactualityCritic",
          "Give concise factual correction advice for the answer."
        )
      )
      .contramap[Refine.Attempt[QaInput, QaOutput]](attempt =>
        FactualityCritiqueInput(attempt.prediction.output.answer, attempt.number)
      )
      .map(result => Refine.Advice(Map(answerId -> result.advice)))

    val program = Refine(qa, critic, maxAttempts = 3, threshold = 1.0) { (_, prediction) =>
      Right(if prediction.output.answer.toLowerCase.contains("brussels") then 1.0 else 0.0)
    }

    def call(question: String)(using PredictionBackend): Either[DspyError, String] =
      Demo.run(program, (question = question)).map(_.output.answer)

  // ── Snippet 6 — a tapering length reward ──
  // | def ideal_length_reward(args, pred): d = abs(len(pred.summary.split()) - 75); return max(0, 1 - d/125)
  // | optimized_summarizer = dspy.BestOfN(module=dspy.ChainOfThought("text -> summary"), N=50,
  // |                                     reward_fn=ideal_length_reward, threshold=0.9)
  object IdealLengthSummarizer:
    private val summarizer = ChainOfThought(
      ParameterId("refinement/summary"),
      Signature.fromString("text -> summary")
    )

    private def idealLength(summary: String): Double =
      val words    = summary.trim.split("\\s+").count(_.nonEmpty)
      val distance = math.abs(words - 75)
      math.max(0.0, 1.0 - distance / 125.0)

    val optimizedSummarizer = Program.bestOfN(summarizer, attempts = 50, threshold = Some(0.9)) { (_, prediction) =>
      Right(idealLength(prediction.output.summary))
    }

    def call(text: String)(using PredictionBackend): Either[DspyError, String] =
      Demo.run(optimizedSummarizer, (text = text)).map(_.output.summary)

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.output_refinement.bestOfNAndRefineMain"
@main def bestOfNAndRefineMain(): Unit =
  Demo.withLm {
    val question = "What is the capital of Belgium?"
    println("BestOfN: " + BestOfNAndRefine.OneWordBestOfN.call(question))
    println("Refine:  " + BestOfNAndRefine.OneWordRefine.call(question))
    println("Judge:   " + BestOfNAndRefine.FactualityRefine.call("Tell me about Belgium's capital city."))
    println("Summary: " + BestOfNAndRefine.IdealLengthSummarizer.call("[Long text to summarize...]"))
  }
