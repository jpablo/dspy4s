package dspy4s.gepa

import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.programs.ProgramEvent

/** One example execution retained for reflective feedback. */
final case class Trajectory(
    example   : Example,
    prediction: RawPrediction,
    events    : Vector[ProgramEvent],
    score     : Double
)

/** Candidate evaluation results aligned with the input batch. */
final case class EvaluationBatch(
    outputs     : Vector[RawPrediction],
    scores      : Vector[Double],
    trajectories: Option[Vector[Trajectory]]
)
