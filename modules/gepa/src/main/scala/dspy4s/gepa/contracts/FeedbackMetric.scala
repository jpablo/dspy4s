package dspy4s.gepa.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.PythonRepr
import dspy4s.core.data.RawPrediction
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry
import dspy4s.evaluate.contracts.Metric

/** A score paired with natural-language feedback — GEPA's optimization signal.
  *
  * The `feedback` text is the "gradient" of reflective prompt evolution: it is handed (per component) to the reflection
  * LM, which rewrites that component's instruction to address it. Concrete, actionable feedback — what went wrong, the
  * correct answer, a format error — is what makes the search work; a bare score teaches the reflection LM little. See
  * PORT_GAPS G-12.
  *
  * @param objectiveScores
  *   optional scores of the example for named objectives, for example `"accuracy"` and `"cost"` (dspy's
  *   `objective_scores`). GEPA reads them from the program-level feedback only. It keeps them for each candidate, and
  *   it selects candidates with them when [[dspy4s.gepa.GepaConfig.frontierType]] is not `Instance`. The acceptance of
  *   a candidate and the best candidate of a run use `score` only. A larger objective score is better.
  */
final case class ScoreWithFeedback(score: Double, feedback: String, objectiveScores: Map[String, Double] = Map.empty)

/** GEPA's feedback metric — the dspy4s analogue of Python's `GEPAFeedbackMetric`.
  *
  * Where the plain [[dspy4s.evaluate.contracts.Metric]] returns only a `Double`, GEPA needs textual feedback, requested
  * at TWO granularities:
  *
  *   - '''Program level''' (`component = None`, `trace` and `componentTrace` empty): the overall score plus
  *     program-wide feedback for one example. Drives candidate scoring / acceptance. The call never gets the trace of
  *     the run, on each evaluation path, because upstream gives `trace=None` to each such call (`wrapped_metric` in
  *     `dspy/teleprompt/bootstrap_trace.py`, called by `Evaluate` with two arguments). Thus a score does not depend on
  *     the path.
  *   - '''OptimizableLeaf level''' (`component = Some(name)`, `trace` = the trace of the run, `componentTrace` = the
  *     one call of that predictor that GEPA selected from `trace`): feedback aimed at a single named predictor. The
  *     reflection loop assembles each component's reflective dataset from these, then asks the reflection LM for a
  *     better instruction. GEPA asks for this feedback only when the run passed, so `prediction` is the prediction of
  *     the program. A `Left` or an exception of this call skips the proposal task, and the run continues.
  *
  * Extends [[Metric]] so ONE instance serves both roles: it is a drop-in `Metric` for the [[dspy4s.evaluate.Evaluate]]
  * machinery ([[score]] = the program-level feedback's score), AND the source of reflection feedback ([[feedback]]).
  * The GEPA adapter calls [[feedback]] only, because it also reads the objective scores. LM-judged feedback metrics may
  * call a model via the ambient [[RuntimeContext]].
  *
  * Errors: like `Metric`, return `Left` only for an unrecoverable scoring failure. A wrong/empty prediction is a low
  * SCORE with feedback, not a `Left`, because GEPA learns from those. A score that is not finite counts as a failure of
  * the metric: GEPA saves its state as JSON, and JSON has no such number. GEPA drops each objective score that is not
  * finite, and it keeps the finite ones.
  */
trait FeedbackMetric extends Metric:

  /** Score + feedback for one example at the granularity selected by `component` (see the trait doc). */
  def feedback(
      example       : Example,
      prediction    : RawPrediction,
      trace         : Vector[TraceEntry],
      component     : Option[String],
      componentTrace: Vector[TraceEntry]
  )(using RuntimeContext): Either[DspyError, ScoreWithFeedback]

  /** The [[Metric]] score is exactly the program-level feedback's score, so any `FeedbackMetric` is also a valid
    * `Metric`. `final` so implementations define only [[name]] and [[feedback]].
    */
  final override def score(
      example   : Example,
      prediction: RawPrediction,
      trace     : Vector[TraceEntry]
  )(using RuntimeContext): Either[DspyError, Double] =
    feedback(example, prediction, trace, component = None, componentTrace = Vector.empty).map(_.score)

object FeedbackMetric:

  /** The fallback feedback text when a metric has only a score to offer (mirrors Python's
    * `"This trajectory got a score of {score}."`, with the Python text of the score). Use when wrapping a plain
    * score-only metric as a [[FeedbackMetric]] so the reflection LM still receives a (minimal) signal.
    */
  def defaultFeedback(score: Double): String = s"This trajectory got a score of ${PythonRepr.float(score)}."
