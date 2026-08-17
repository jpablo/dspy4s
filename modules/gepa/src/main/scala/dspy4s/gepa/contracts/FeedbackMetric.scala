package dspy4s.gepa.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.programs.{ParameterId, ProgramEvent}
import zio.IO

/** A numeric score and the feedback text used for reflective optimization. */
final case class ScoreWithFeedback(score: Double, feedback: String)

/** Effectful GEPA metric over explicit program events. */
trait FeedbackMetric:
  def name: String

  def feedback(
      example        : Example,
      prediction     : RawPrediction,
      events         : Vector[ProgramEvent],
      component      : Option[ParameterId],
      componentEvents: Vector[ProgramEvent]
  ): IO[DspyError, ScoreWithFeedback]

  final def score(
      example   : Example,
      prediction: RawPrediction,
      events    : Vector[ProgramEvent]
  ): IO[DspyError, Double] =
    feedback(example, prediction, events, None, Vector.empty).map(_.score)

object FeedbackMetric:
  def defaultFeedback(score: Double): String = s"This trajectory got a score of $score."
