package dspy4s.gepa.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry
import dspy4s.evaluate.contracts.Metric

/** A score paired with natural-language feedback — GEPA's optimization signal.
  *
  * The `feedback` text is the "gradient" of reflective prompt evolution: it is handed (per component) to the
  * reflection LM, which rewrites that component's instruction to address it. Concrete, actionable feedback — what
  * went wrong, the correct answer, a format error — is what makes the search work; a bare score teaches the
  * reflection LM little. See PORT_GAPS G-12. */
final case class ScoreWithFeedback(score: Double, feedback: String)

/** GEPA's feedback metric — the dspy4s analogue of Python's `GEPAFeedbackMetric`.
  *
  * Where the plain [[dspy4s.evaluate.contracts.Metric]] returns only a `Double`, GEPA needs textual feedback,
  * requested at TWO granularities:
  *
  *   - '''Program level''' (`component = None`, `componentTrace` empty): the overall score plus program-wide
  *     feedback for one example. Drives candidate scoring / acceptance.
  *   - '''Predictor level''' (`component = Some(name)`, `componentTrace` = the slice of `trace` produced by that
  *     predictor): feedback aimed at a single named predictor. The reflection loop assembles each component's
  *     reflective dataset from these, then asks the reflection LM for a better instruction.
  *
  * Extends [[Metric]] so ONE instance serves both roles: it is a drop-in `Metric` for the
  * [[dspy4s.evaluate.Evaluate]] machinery the GEPA adapter reuses for scoring ([[score]] = the program-level
  * feedback's score), AND the source of reflection feedback ([[feedback]]). LM-judged feedback metrics may call a
  * model via the ambient [[RuntimeContext]].
  *
  * Errors: like `Metric`, return `Left` only for an unrecoverable scoring failure. A wrong/empty prediction is a
  * low SCORE with feedback, not a `Left` — GEPA learns from those. */
trait FeedbackMetric extends Metric:

  /** Score + feedback for one example at the granularity selected by `component` (see the trait doc). */
  def feedback(
      example: Example,
      prediction: DynamicPrediction,
      trace: Vector[TraceEntry],
      component: Option[String],
      componentTrace: Vector[TraceEntry]
  )(using RuntimeContext): Either[DspyError, ScoreWithFeedback]

  /** The [[Metric]] score is exactly the program-level feedback's score, so any `FeedbackMetric` is also a valid
    * `Metric`. `final` so implementations define only [[name]] and [[feedback]]. */
  final override def score(
      example: Example,
      prediction: DynamicPrediction,
      trace: Vector[TraceEntry]
  )(using RuntimeContext): Either[DspyError, Double] =
    feedback(example, prediction, trace, component = None, componentTrace = Vector.empty).map(_.score)

object FeedbackMetric:

  /** The fallback feedback text when a metric has only a score to offer (mirrors Python's
    * `"This trajectory got a score of {score}."`). Use when wrapping a plain score-only metric as a
    * [[FeedbackMetric]] so the reflection LM still receives a (minimal) signal. */
  def defaultFeedback(score: Double): String = s"This trajectory got a score of $score."
