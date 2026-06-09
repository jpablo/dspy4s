package dspy4s.gepa

import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.Predictors
import dspy4s.optimize.Runnable

/** User-facing GEPA optimizer — the dspy4s analogue of dspy's `GEPA` teleprompter. It wires the
  * [[GepaAdapter]] + [[GepaEngine]] and runs reflective prompt evolution over a student program.
  *
  * Unlike COPRO/MIPROv2 (which take a plain `Metric`), GEPA needs a [[dspy4s.gepa.contracts.FeedbackMetric]]
  * (score PLUS reflection feedback) and a separate `reflectionLm`. See PORT_GAPS G-12.
  *
  * @param metric       the feedback metric — scores examples and yields the reflection feedback
  * @param reflectionLm the (usually stronger) model that rewrites instructions from reflective feedback
  * @param config       budget + search settings ([[GepaConfig]]) */
final class Gepa[P](
    metric: FeedbackMetric,
    reflectionLm: LanguageModel,
    config: GepaConfig
)(using Predictors[P], Runnable[P]):

  val name: String = "gepa"

  /** Optimize `student`'s predictor instructions over `trainset` (reflective minibatches) and `valset` (Pareto
    * frontier scoring), returning the best candidate and the program it yields. */
  def compile(student: P, trainset: Vector[Example], valset: Vector[Example])(using RuntimeContext): GepaResult[P] =
    val adapter = new GepaAdapter(student, metric, config.failureScore)
    new GepaEngine(adapter, reflectionLm, config).optimize(Candidate.seed(student), trainset, valset)
