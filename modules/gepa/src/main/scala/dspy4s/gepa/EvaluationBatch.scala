package dspy4s.gepa

import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.TraceEntry

/** One example's captured trajectory — the unit GEPA reflects on: the program's per-predictor [[TraceEntry]] trace
  * (including failure entries via G-12 P-a) plus the score it earned. The reflective-dataset builder reads these. */
final case class Trajectory(
    example: Example,
    prediction: DynamicPrediction,
    trace: Vector[TraceEntry],
    score: Double
)

/** Result of evaluating a candidate over a batch (the analogue of gepa's `EvaluationBatch`). `trajectories` is
  * `Some` iff traces were captured (the reflective path) and `None` on the scores-only fast path. `outputs`,
  * `scores`, and (when present) `trajectories` are all aligned with the input batch order. */
final case class EvaluationBatch(
    outputs: Vector[DynamicPrediction],
    scores: Vector[Double],
    trajectories: Option[Vector[Trajectory]]
)
